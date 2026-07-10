package com.arhand.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * DA2 — Depth Anything v2 monocular depth estimator.
 *
 * Loads a Depth Anything v2 ONNX model from [ASSET_PATH] and runs per-frame
 * inference on [Dispatchers.Default]. The model produces a relative depth map
 * which is then affine-calibrated to metric using the XR anchor offset supplied
 * via [setXrAnchor].
 *
 * ## Model expectation
 *
 * Input tensor:  "image"  — float32 [1, 3, INPUT_H, INPUT_W], RGB normalised to [0, 1]
 * Output tensor: "depth"  — float32 [1, 1, INPUT_H, INPUT_W], relative inverse depth
 *
 * Place the ONNX file at: src/main/assets/depth_anything_v2_small.ort
 *
 * If the model file is absent, [isAvailable] returns false and all outputs remain null —
 * the channel weight in [CrossChannelArbiter] will be zeroed automatically.
 *
 * ## XR affine calibration
 *
 * When ARCore is tracking, the caller supplies a (scale, shift) pair that maps the
 * model's inverse depth to absolute metres:
 *   depth_metric[i] = scale / (raw_inv_depth[i] + shift)
 *
 * This loop is updated each frame from [FusedDepthSource] using the ARCore point cloud.
 */
class DepthAnythingSource(private val context: Context) {

    /** True when the ONNX model loaded successfully. */
    @Volatile var isAvailable: Boolean = false
        private set

    /**
     * Dense (128×96) affine-calibrated depth map, row-major. Null until first inference.
     * Written from a background coroutine; read from any thread via [sampleAtNormalized].
     */
    @Volatile var denseDepth: FloatArray? = null
        private set

    /**
     * Per-block ([BLOCK_W]×[BLOCK_H]) affine-calibrated metric depth (metres), or null.
     * Downsampled from [denseDepth] for backward compatibility with legacy consumers.
     */
    @Volatile var depthBlocks: FloatArray? = null
        private set

    /** Signal confidence [0–1] based on relative depth variance across the last output. */
    @Volatile var confidence: Float = 0f
        private set

    // Affine calibration: metric = xrScale / (invDepth + xrShift)
    @Volatile private var xrScale: Float = 1f
    @Volatile private var xrShift: Float = 0f
    @Volatile private var xrCalibrated: Boolean = false

    private var env:     OrtEnvironment? = null
    private var session: OrtSession?     = null

    private val inputBuf = FloatBuffer.allocate(3 * INPUT_H * INPUT_W)

    // Temporal EMA state for the dense map
    private val emaMap  = FloatArray(DENSE_W * DENSE_H)
    private var emaWarm = false

    /**
     * S4.1/S4.2 — SlamLite optical-flow reading for exactly one bitmap, passed as a
     * parameter through [processAsync] instead of read from shared mutable fields.
     *
     * DA2 inference can take longer than one camera frame; when it does,
     * [processAsync] drops the overlapping frame and the earlier one keeps running.
     * If the flow reading were a shared field (as it was before), a later frame's
     * SlamLite output would silently overwrite it while the earlier frame's
     * inference was still in flight, so the flow-warp/motion-adaptive-EMA math
     * would apply a *different* frame's camera motion to this frame's depth map
     * (ENGINE_ARCHITECTURE.md §5.2). Bundling it with the bitmap at enqueue time
     * makes that impossible — each in-flight inference keeps the flow reading valid
     * for the exact frame it was computed from, however long it runs.
     */
    data class FlowSnapshot(val mag: Float, val nx: Float, val ny: Float) {
        companion object { val ZERO = FlowSnapshot(0f, 0f, 0f) }
    }

    // S3.4 — Outdoor scene context: multiply DA2 inv-depth by 0.35 before calibration
    @Volatile var outdoorMode: Boolean = false

    // S1.2 — Dual-anchor log-linear calibration: metric_z = exp(llA × inv + llB)
    @Volatile private var llA: Float = 0f
    @Volatile private var llB: Float = 0f
    @Volatile private var llCalibrated: Boolean = false

    // S4.2 — Previous EMA frame for flow-warped blending; pre-allocated to avoid GC
    private val prevEmaMap = FloatArray(DENSE_W * DENSE_H)

    // S2.4 — Stationary multi-frame noise averaging state
    private var stationaryFrameCount = 0
    private var accumCount           = 0
    private val accumBuf             = FloatArray(DENSE_W * DENSE_H)

    // Pre-allocated double-buffer for denseDepth publication — avoids per-frame copyOf() on the
    // hot path. Two buffers allow lock-free hand-off: writer fills denseBufA/B alternately.
    private val denseBufA = FloatArray(DENSE_W * DENSE_H)
    private val denseBufB = FloatArray(DENSE_W * DENSE_H)
    private var denseBufToggle = false  // false → A is current, true → B is current

    // Pre-allocated buffer for the stationary averaged output — avoids allocation every 16 frames
    private val averagedDepthBuf = FloatArray(DENSE_W * DENSE_H)
    /** Non-null when device is stationary and 16-frame average is ready. Lower noise than EMA. */
    @Volatile var averagedDepth: FloatArray? = null
        private set

    // Scratch buffers used inside process() — avoids per-inference heap allocations on the hot path
    private val denseScratchBuf    = FloatArray(DENSE_W * DENSE_H)   // bilinear downsample output
    private val fillPixelsBuf      = IntArray(INPUT_W * INPUT_H)     // getPixels scratch for model input
    private val depthBlocksScratch = FloatArray(PYRAMID_BLOCKS)      // pyramid accumulator
    // Double-buffer for depthBlocks publication (same pattern as denseDepth)
    private val depthBlocksBufA    = FloatArray(PYRAMID_BLOCKS)
    private val depthBlocksBufB    = FloatArray(PYRAMID_BLOCKS)
    private var depthBlocksToggle  = false
    // Pre-allocated flatten buffer — avoids FloatArray(rows*cols) allocation inside flattenOutput each inference.
    private val flattenBuf         = FloatArray(INPUT_H * INPUT_W)

    /** Load the ONNX model. Call once on a background thread during startup. */
    fun init() {
        try {
            val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
            env       = OrtEnvironment.getEnvironment()
            session   = env!!.createSession(bytes, OrtSession.SessionOptions())
            isAvailable = true
        } catch (_: Exception) {
            isAvailable = false
        }
    }

    /**
     * Set the XR anchor calibration from ARCore.
     *
     * @param scale  Affine scale: metric depth ≈ scale / (invDepth + shift)
     * @param shift  Affine shift
     */
    fun setXrAnchor(scale: Float, shift: Float) {
        xrScale      = scale
        xrShift      = shift
        xrCalibrated = true
    }

    /**
     * S1.2 — Dual-anchor log-linear calibration from two metric reference points.
     * Fits metric_z = exp(llA × inv_depth + llB) using two (z, inv) pairs.
     * Supersedes the single-anchor affine when called; handles outdoor/long-range depth.
     */
    fun setDualAnchor(zNear: Float, invNear: Float, zFar: Float, invFar: Float) {
        if (zNear <= 0f || zFar <= 0f || zNear >= zFar || kotlin.math.abs(invNear - invFar) < 1e-4f) return
        llA = (ln(zFar) - ln(zNear)) / (invFar - invNear)
        llB = ln(zNear) - llA * invNear
        llCalibrated = true
    }

    // process() mutates shared scratch buffers (denseScratchBuf, emaMap, ...) with
    // no synchronization. Dispatchers.Default is a thread pool, not a single thread,
    // so if a frame's inference is still running when the next one is submitted,
    // two process() calls could run concurrently on different pool threads and
    // corrupt each other's buffers. Guard with a busy flag and drop the overlapping
    // frame instead — consistent with the rest of the pipeline's "keep only latest,
    // drop if backed up" backpressure (e.g. CameraX's STRATEGY_KEEP_ONLY_LATEST).
    private val processing = AtomicBoolean(false)

    /**
     * Run inference on [bitmap] asynchronously on [Dispatchers.Default].
     * Results are written to [depthBlocks] when complete. Drops the frame instead
     * of overlapping if a previous call is still running.
     *
     * @param flow The SlamLite optical-flow reading for this exact [bitmap] — see [FlowSnapshot].
     */
    fun processAsync(bitmap: Bitmap, flow: FlowSnapshot, scope: CoroutineScope) {
        if (!isAvailable) return
        if (!processing.compareAndSet(false, true)) return   // previous frame still in flight
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        scope.launch(Dispatchers.Default) {
            try {
                process(copy, flow)
            } finally {
                processing.set(false)
            }
        }
    }

    /** Synchronous inference — call from a background thread. */
    fun process(bitmap: Bitmap, flow: FlowSnapshot = FlowSnapshot.ZERO) {
        val sess = session ?: return

        // Resize to model input size
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_W, INPUT_H, true)

        // Preprocess: HWC → CHW, normalise
        fillInputBuffer(scaled)
        scaled.recycle()

        val env2 = env ?: return
        val shape = longArrayOf(1, 3, INPUT_H.toLong(), INPUT_W.toLong())
        val tensor = OnnxTensor.createTensor(env2, inputBuf, shape)
        val rawFlat = try {
            val result = sess.run(mapOf("image" to tensor))
            try {
                val output = result[0].value as? Array<*> ?: return
                flattenOutput(output) ?: return
            } finally {
                result.close()
            }
        } finally {
            tensor.close()
        }

        // Bilinear downsample from 518×518 model output to 128×96 dense map (reuse scratch buf)
        val dense = denseScratchBuf
        var minV  = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE

        for (dy in 0 until DENSE_H) {
            val srcY = dy.toFloat() * (INPUT_H - 1) / (DENSE_H - 1)
            val y0   = srcY.toInt().coerceIn(0, INPUT_H - 2)
            val fy   = srcY - y0
            for (dx in 0 until DENSE_W) {
                val srcX = dx.toFloat() * (INPUT_W - 1) / (DENSE_W - 1)
                val x0   = srcX.toInt().coerceIn(0, INPUT_W - 2)
                val fx   = srcX - x0
                val v    = rawFlat[y0 * INPUT_W + x0]         * (1-fx) * (1-fy) +
                           rawFlat[y0 * INPUT_W + x0 + 1]     * fx     * (1-fy) +
                           rawFlat[(y0+1) * INPUT_W + x0]     * (1-fx) * fy     +
                           rawFlat[(y0+1) * INPUT_W + x0 + 1] * fx     * fy
                dense[dy * DENSE_W + dx] = v
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
        }

        val range = maxV - minV
        confidence = if (range > 0f) (range / 10f).coerceIn(0f, 1f) else 0f

        // S3.4: Outdoor scene context — shift DA2 working range from [0.3–3 m] to [1–10 m]
        // before calibration. 0.35× on inv-depth is a prior: outdoor scenes are ~3× deeper.
        if (outdoorMode) {
            for (i in dense.indices) dense[i] *= 0.35f
        }

        // Convert raw inverse depth to metric
        when {
            llCalibrated -> {
                // S1.2: Log-linear dual-anchor — accurate for outdoor / long-range depth
                for (i in dense.indices) {
                    dense[i] = exp(llA * dense[i] + llB).coerceIn(0f, 15f)
                }
            }
            xrCalibrated && xrScale > 0f -> {
                for (i in dense.indices) {
                    val inv = dense[i]
                    dense[i] = if (inv + xrShift != 0f) (xrScale / (inv + xrShift)).coerceIn(0f, 10f) else 0f
                }
            }
            range > 0f -> {
                for (i in dense.indices) dense[i] = (dense[i] - minV) / range
            }
        }

        // S4.1: Motion-adaptive EMA α — fast update when subject is moving, heavy smoothing
        // when stationary. α_min=0.20 (max smoothing), α_max=0.80 (near-instant), thresh=15px
        val emaAlpha = run {
            val t = (flow.mag / 15f).coerceIn(0f, 1f)
            0.20f + (0.80f - 0.20f) * t
        }

        // S4.2: Flow-warped temporal EMA — warp the previous EMA frame by the camera's
        // optical flow before blending, eliminating ghost trails at moving object boundaries.
        if (!emaWarm) {
            dense.copyInto(emaMap)
            emaWarm = true
        } else {
            emaMap.copyInto(prevEmaMap)
            val warpX = flow.nx * (DENSE_W - 1)
            val warpY = flow.ny * (DENSE_H - 1)
            for (dy in 0 until DENSE_H) {
                for (dx in 0 until DENSE_W) {
                    val wx = (dx.toFloat() - warpX).coerceIn(0f, (DENSE_W - 1).toFloat())
                    val wy = (dy.toFloat() - warpY).coerceIn(0f, (DENSE_H - 1).toFloat())
                    val wx0 = wx.toInt().coerceIn(0, DENSE_W - 2)
                    val wy0 = wy.toInt().coerceIn(0, DENSE_H - 2)
                    val fx = wx - wx0; val fy = wy - wy0
                    val warped = prevEmaMap[wy0 * DENSE_W + wx0]         * (1-fx) * (1-fy) +
                                 prevEmaMap[wy0 * DENSE_W + wx0 + 1]     * fx     * (1-fy) +
                                 prevEmaMap[(wy0+1) * DENSE_W + wx0]     * (1-fx) * fy     +
                                 prevEmaMap[(wy0+1) * DENSE_W + wx0 + 1] * fx     * fy
                    emaMap[dy * DENSE_W + dx] = warped * (1f - emaAlpha) + dense[dy * DENSE_W + dx] * emaAlpha
                }
            }
        }

        // S2.4: Stationary multi-frame noise averaging.
        // When optical flow < 2px for 10+ confirmed frames, accumulate 16 frames and
        // compute arithmetic mean (4× SNR improvement vs single frame).
        if (flow.mag < STATIONARY_FLOW_THRESH) {
            stationaryFrameCount++
            if (stationaryFrameCount >= STATIONARY_CONFIRM_FRAMES) {
                for (i in emaMap.indices) accumBuf[i] += emaMap[i]
                accumCount++
                if (accumCount >= N_AVG) {
                    val inv = 1f / N_AVG
                    // Reuse pre-allocated buffer rather than allocating every 16 frames
                    for (i in averagedDepthBuf.indices) averagedDepthBuf[i] = accumBuf[i] * inv
                    averagedDepth = averagedDepthBuf
                    accumBuf.fill(0f)
                    accumCount = 0
                }
            }
        } else {
            stationaryFrameCount = 0
            averagedDepth        = null
            accumBuf.fill(0f)
            accumCount           = 0
        }
        // Double-buffer swap: copy the source map into the inactive buffer, then publish it.
        // This avoids a per-frame FloatArray allocation while still providing a stable snapshot
        // that readers can safely observe without a lock.
        val src = averagedDepth ?: emaMap
        denseBufToggle = !denseBufToggle
        val dest = if (denseBufToggle) denseBufB else denseBufA
        src.copyInto(dest)
        denseDepth = dest

        // S3.2: Hierarchical depth grid — 3-tier spatial pyramid.
        // Tier 0 (coarse 2×2, full frame):       4 blocks  → idx  0–3
        // Tier 1 (medium 4×4, full frame):       16 blocks  → idx  4–19
        // Tier 2 (fine   8×4, center 60% width): 32 blocks  → idx 20–51
        depthBlocksScratch.fill(0f)
        val blocks = depthBlocksScratch

        // Tier 0: 2×2 coarse, full frame
        for (by in 0 until 2) {
            for (bx in 0 until 2) {
                val bi = by * 2 + bx
                val x0 = bx * DENSE_W / 2; val x1 = (bx + 1) * DENSE_W / 2
                val y0 = by * DENSE_H / 2; val y1 = (by + 1) * DENSE_H / 2
                var sum = 0f; var cnt = 0
                for (y in y0 until y1) for (x in x0 until x1) { sum += emaMap[y * DENSE_W + x]; cnt++ }
                blocks[bi] = if (cnt > 0) sum / cnt else 0f
            }
        }

        // Tier 1: 4×4 medium, full frame
        for (by in 0 until 4) {
            for (bx in 0 until 4) {
                val bi = 4 + by * 4 + bx
                val x0 = bx * DENSE_W / 4; val x1 = (bx + 1) * DENSE_W / 4
                val y0 = by * DENSE_H / 4; val y1 = (by + 1) * DENSE_H / 4
                var sum = 0f; var cnt = 0
                for (y in y0 until y1) for (x in x0 until x1) { sum += emaMap[y * DENSE_W + x]; cnt++ }
                blocks[bi] = if (cnt > 0) sum / cnt else 0f
            }
        }

        // Tier 2: 8×4 fine, center 60% of frame width (nx ≈ 0.20–0.80)
        val cxStart = (DENSE_W * 0.20f).toInt()           // ≈ pixel 25
        val cxEnd   = (DENSE_W * 0.80f).toInt()           // ≈ pixel 102
        val cxRange = cxEnd - cxStart
        for (by in 0 until 4) {
            for (bx in 0 until 8) {
                val bi = 20 + by * 8 + bx
                val x0 = cxStart + bx * cxRange / 8
                val x1 = (cxStart + (bx + 1) * cxRange / 8).coerceAtMost(DENSE_W)
                val y0 = by * DENSE_H / 4; val y1 = (by + 1) * DENSE_H / 4
                var sum = 0f; var cnt = 0
                for (y in y0 until y1) for (x in x0 until x1) { sum += emaMap[y * DENSE_W + x]; cnt++ }
                blocks[bi] = if (cnt > 0) sum / cnt else 0f
            }
        }
        // Double-buffer publish: copy accumulator into the inactive output buffer
        depthBlocksToggle = !depthBlocksToggle
        val depthDest = if (depthBlocksToggle) depthBlocksBufA else depthBlocksBufB
        blocks.copyInto(depthDest)
        depthBlocks = depthDest
    }

    /** Reset all accumulated state (EMA, outputs). */
    fun reset() {
        denseDepth   = null
        depthBlocks  = null
        emaMap.fill(0f)
        prevEmaMap.fill(0f)
        emaWarm      = false
        confidence   = 0f
        xrCalibrated = false
        llCalibrated = false
        stationaryFrameCount = 0
        accumCount           = 0
        accumBuf.fill(0f)
        averagedDepth        = null
        denseBufToggle       = false
        denseBufA.fill(0f)
        denseBufB.fill(0f)
        depthBlocksToggle    = false
        depthBlocksBufA.fill(0f)
        depthBlocksBufB.fill(0f)
        flattenBuf.fill(0f)
    }

    /**
     * Sample the EMA-smoothed dense depth map at normalised screen coordinates (nx, ny)
     * using bilinear interpolation.
     *
     * @param nx Normalised X [0..1], left→right
     * @param ny Normalised Y [0..1], top→bottom
     * @return Bilinear-interpolated depth value, or 0f if map is not yet available
     */
    fun sampleAtNormalized(nx: Float, ny: Float): Float {
        val map = denseDepth ?: return 0f
        val srcX = (nx * (DENSE_W - 1)).coerceIn(0f, (DENSE_W - 1).toFloat())
        val srcY = (ny * (DENSE_H - 1)).coerceIn(0f, (DENSE_H - 1).toFloat())
        val x0 = srcX.toInt().coerceIn(0, DENSE_W - 2)
        val y0 = srcY.toInt().coerceIn(0, DENSE_H - 2)
        val fx = srcX - x0; val fy = srcY - y0
        return map[y0 * DENSE_W + x0]         * (1-fx) * (1-fy) +
               map[y0 * DENSE_W + x0 + 1]     * fx     * (1-fy) +
               map[(y0+1) * DENSE_W + x0]     * (1-fx) * fy     +
               map[(y0+1) * DENSE_W + x0 + 1] * fx     * fy
    }

    fun close() {
        session?.close(); session = null
        env?.close();     env     = null
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun fillInputBuffer(bmp: Bitmap) {
        inputBuf.rewind()
        val pixels = fillPixelsBuf
        bmp.getPixels(pixels, 0, INPUT_W, 0, 0, INPUT_W, INPUT_H)
        // CHW layout: channel 0 (R), channel 1 (G), channel 2 (B)
        val n = INPUT_W * INPUT_H
        for (c in 0..2) {
            val mean = MEAN[c]; val std = STD[c]
            for (i in 0 until n) {
                val ch = when (c) {
                    0 -> (pixels[i] shr 16) and 0xFF
                    1 -> (pixels[i] shr 8)  and 0xFF
                    else ->  pixels[i]       and 0xFF
                }
                inputBuf.put((ch / 255f - mean) / std)
            }
        }
        inputBuf.rewind()
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenOutput(output: Array<*>): FloatArray? {
        // Output shape: [1, 1, H, W] → flatten innermost two dims into pre-allocated flattenBuf.
        return try {
            val l1 = output[0] as? Array<*> ?: return null
            val l2 = l1[0]    as? Array<*> ?: return null
            val rows = l2.size
            val cols = (l2[0] as? FloatArray)?.size ?: return null
            if (rows * cols > flattenBuf.size) return null  // guard: model output larger than expected
            for (r in 0 until rows) {
                val row = l2[r] as? FloatArray ?: continue
                row.copyInto(flattenBuf, r * cols)
            }
            flattenBuf
        } catch (_: Exception) { null }
    }

    companion object {
        private const val ASSET_PATH = "depth_anything_v2_small.ort"
        private const val INPUT_W    = 518   // model native width
        private const val INPUT_H    = 518   // model native height
        /** Dense output width — 16× more columns than the legacy 8-block grid. */
        const val DENSE_W   = 128
        /** Dense output height — 16× more rows than the legacy 6-block grid. */
        const val DENSE_H   = 96
        private const val BLOCK_W    = 8     // kept for backward compat downsampling
        private const val BLOCK_H    = 6
        private const val STATIONARY_FLOW_THRESH   = 2f   // px/frame threshold for stationary
        private const val STATIONARY_CONFIRM_FRAMES = 10  // frames before activating averaging
        private const val N_AVG                    = 16   // frames to average
        /** Total block count for the 3-tier depth pyramid (S3.2). */
        const val PYRAMID_BLOCKS = 52
        // ImageNet normalisation constants (Depth Anything v2 training)
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD  = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
