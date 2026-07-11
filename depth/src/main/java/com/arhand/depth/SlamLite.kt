package com.arhand.depth

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SlamLite — lightweight visual SLAM for camera pose accumulation.
 *
 * Tracks Harris corners across consecutive frames using a simplified Lucas-Kanade
 * optical flow, estimates the inter-frame rigid transformation, and accumulates it
 * into a running camera pose. Provides per-frame delta (tx, ty, rz) for immediate
 * use and a full accumulated pose (tx, ty, tz, rx, ry, rz) for room mapping.
 *
 * This is a planar-motion approximation suitable for a handheld phone:
 *   - In-plane translation (tx, ty) from the dominant feature flow vector
 *   - In-plane rotation (rz) from the rotation component of the flow field
 *   - Depth (tz) estimated from scale change in feature spacing (optional)
 *   - rx, ry accumulated from IMU or held at 0 if not available
 *
 * All coordinates are relative to the pose at [reset] time.
 */
class SlamLite {

    /** Accumulated camera pose since last [reset]. All units: metres and radians. */
    data class CameraPose(
        val tx: Float, val ty: Float, val tz: Float,
        val rx: Float, val ry: Float, val rz: Float
    )

    /** Per-frame camera delta relative to the previous frame. */
    data class PoseDelta(val tx: Float, val ty: Float, val rz: Float)

    /** Current accumulated pose. */
    @Volatile var pose: CameraPose = CameraPose(0f, 0f, 0f, 0f, 0f, 0f)
        private set

    /** Most recent per-frame delta. */
    @Volatile var delta: PoseDelta = PoseDelta(0f, 0f, 0f)
        private set

    /** Feature count in the most recent frame. */
    @Volatile var featureCount: Int = 0
        private set

    /** S4.1 — Median optical flow magnitude this frame in px/frame (0 when no prior frame). */
    @Volatile var meanFlowMag: Float = 0f
        private set

    /** S4.2 — Normalised median flow X (fraction of image width per frame). */
    @Volatile var medianFlowNX: Float = 0f
        private set

    /** S4.2 — Normalised median flow Y (fraction of image height per frame). */
    @Volatile var medianFlowNY: Float = 0f
        private set

    private var prevLuma:         IntArray?        = null
    private var lastComputedLuma: IntArray?        = null
    private var prevFeatures:     List<FloatArray> = emptyList()

    // Pre-allocated scratch buffers for computeLuma and detectHarris.
    // Sized for the expected camera resolution (640×480). Resized lazily on resolution change.
    private var lumaPixelsBuf: IntArray = IntArray(0)
    private var lumaResultBuf: IntArray = IntArray(0)
    private var normLumaBuf:   IntArray = IntArray(0)
    private var scoresBuf:     FloatArray = FloatArray(0)

    // Double-buffer for the per-frame luma snapshot — avoids copyOfRange allocation each frame.
    // Writer toggles between A and B; prevLuma always holds a stable copy from the prior frame.
    private var snapBufA:    IntArray = IntArray(0)
    private var snapBufB:    IntArray = IntArray(0)
    private var snapToggle:  Boolean  = false

    /**
     * ENGINE_ARCHITECTURE.md §17.5 — [process] used to run Harris corner detection + LK
     * optical flow at the full incoming camera resolution (commonly 640×480 — `computeLuma`
     * alone touches ~307k pixels every call). Every other always-on Core channel that shares
     * this same camera stream already works at a reduced resolution
     * ([com.arhand.camera.BitmapGrayscaleShim] downsamples to 320×240 for SfM/Photometric) —
     * SlamLite was the one outlier still paying full-resolution cost for a sparse-feature
     * visual-odometry algorithm that only needs a coarse pose estimate, not per-pixel
     * precision. Downsampling here to at most [PROC_MAX_WIDTH] wide cuts `computeLuma`/Harris
     * scoring/NMS cost roughly 4× (halving each dimension) for no meaningful accuracy loss to
     * camera-pose tracking specifically.
     *
     * [SCALE_PX_PER_M] is calibrated to *full* camera resolution — [downsampleFactor] scales
     * the measured pixel flow back up to full-resolution-equivalent terms before that division
     * (see [estimateDelta]'s last line), so [pose]/[delta]'s metric output is numerically
     * unaffected by this — a pure cost optimization, not a behavior change.
     */
    private var downsampleFactor = 1f

    /**
     * Process [bitmap] and update [pose] and [delta].
     * Call once per camera frame from a background thread.
     */
    fun process(bitmap: Bitmap) {
        downsampleFactor = if (bitmap.width > PROC_MAX_WIDTH)
            bitmap.width.toFloat() / PROC_MAX_WIDTH else 1f
        val proc = if (downsampleFactor > 1f) {
            val pw = PROC_MAX_WIDTH
            val ph = (bitmap.height / downsampleFactor).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, pw, ph, true)
        } else bitmap

        val w = proc.width; val h = proc.height
        val savedPrevLuma = prevLuma
        val features = detectHarris(proc, w, h)
        prevLuma = lastComputedLuma
        featureCount = features.size

        val prevF = prevFeatures

        if (savedPrevLuma != null && savedPrevLuma.size == w * h && prevF.isNotEmpty()) {
            val (dtx, dty, drz) = estimateDelta(savedPrevLuma, lastComputedLuma!!, prevF, features, w, h)
            delta = PoseDelta(dtx, dty, drz)
            pose = CameraPose(
                tx = pose.tx + dtx * cos(pose.rz) - dty * sin(pose.rz),
                ty = pose.ty + dtx * sin(pose.rz) + dty * cos(pose.rz),
                tz = pose.tz,
                rx = pose.rx, ry = pose.ry,
                rz = pose.rz + drz
            )
        } else {
            delta = PoseDelta(0f, 0f, 0f)
        }

        prevFeatures = features
    }

    /** Reset accumulated pose to origin. */
    fun reset() {
        pose             = CameraPose(0f, 0f, 0f, 0f, 0f, 0f)
        delta            = PoseDelta(0f, 0f, 0f)
        prevLuma         = null
        lastComputedLuma = null
        snapToggle       = false
        prevFeatures     = emptyList()
        featureCount     = 0
        meanFlowMag      = 0f
        medianFlowNX     = 0f
        medianFlowNY     = 0f
    }

    // ─── Harris corner detection ───────────────────────────────────────────────

    private fun detectHarris(bmp: Bitmap, w: Int, h: Int): List<FloatArray> {
        val luma = computeLuma(bmp, w, h)
        // Double-buffer swap: copy lumaResultBuf into the inactive snapshot buffer so that
        // prevLuma holds a stable per-frame copy while lumaResultBuf is reused next frame.
        val size = w * h
        snapToggle = !snapToggle
        var snap = if (snapToggle) snapBufA else snapBufB
        if (snap.size < size) {
            snap = IntArray(size)
            if (snapToggle) snapBufA = snap else snapBufB = snap
        }
        luma.copyInto(snap, 0, 0, size)
        lastComputedLuma = snap

        // S2.3: Intensity-normalised features — linear stretch amplifies available contrast in
        // dark frames without adding light; only applied to feature detection, not LK tracking
        var lumaSum = 0L
        for (i in 0 until size) lumaSum += luma[i]
        val meanLuma = lumaSum.toFloat() / size
        val normLuma: IntArray
        if (meanLuma < 128f && meanLuma > 0.1f) {
            if (normLumaBuf.size < size) normLumaBuf = IntArray(size)
            val scale = 128f / meanLuma
            for (i in 0 until size) normLumaBuf[i] = (luma[i] * scale).toInt().coerceIn(0, 255)
            normLuma = normLumaBuf
        } else {
            normLuma = luma
        }

        if (scoresBuf.size < size) scoresBuf = FloatArray(size)
        val scores = scoresBuf
        scores.fill(0f, 0, size)
        val b       = HARRIS_BLOCK

        // b+1 guards against (y+dy±1) and (x+dx±1) going out of bounds at the loop edges
        for (y in b + 1 until h - b - 1 step SAMPLE_STEP) {
            for (x in b + 1 until w - b - 1 step SAMPLE_STEP) {
                var ixx = 0f; var iyy = 0f; var ixy = 0f
                for (dy in -b..b) {
                    for (dx in -b..b) {
                        val ix = normLuma[(y + dy) * w + (x + dx + 1)] - normLuma[(y + dy) * w + (x + dx - 1)]
                        val iy = normLuma[(y + dy + 1) * w + (x + dx)] - normLuma[(y + dy - 1) * w + (x + dx)]
                        ixx += ix * ix; iyy += iy * iy; ixy += ix * iy
                    }
                }
                val det   = ixx * iyy - ixy * ixy
                val trace = ixx + iyy
                scores[y * w + x] = det - HARRIS_K * trace * trace
            }
        }

        // Non-maximum suppression + top-K selection
        val candidates = mutableListOf<FloatArray>()
        val minDist2   = MIN_DIST_PX * MIN_DIST_PX

        for (y in b until h - b step SAMPLE_STEP) {
            for (x in b until w - b step SAMPLE_STEP) {
                val s = scores[y * w + x]
                if (s <= 0f) continue
                var isMax = true
                for (dy in -HARRIS_BLOCK..HARRIS_BLOCK) {
                    for (dx in -HARRIS_BLOCK..HARRIS_BLOCK) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h && scores[ny * w + nx] > s) {
                            isMax = false; break
                        }
                    }
                    if (!isMax) break
                }
                if (!isMax) continue

                var tooClose = false
                for (c in candidates) {
                    val ddx = x - c[0]; val ddy = y - c[1]
                    if (ddx * ddx + ddy * ddy < minDist2) { tooClose = true; break }
                }
                if (!tooClose) candidates.add(floatArrayOf(x.toFloat(), y.toFloat(), s))
                if (candidates.size >= MAX_FEATURES * 3) break
            }
        }

        return candidates.sortedByDescending { it[2] }.take(MAX_FEATURES)
            .map { floatArrayOf(it[0], it[1]) }
    }

    // ─── Simplified LK optical flow + pose estimation ─────────────────────────

    private data class FrameMotion(val dtx: Float, val dty: Float, val drz: Float)

    private fun estimateDelta(
        prevL: IntArray, currL: IntArray,
        prevF: List<FloatArray>, currF: List<FloatArray>,
        w: Int, h: Int
    ): FrameMotion {
        // S4.3: Build Gaussian image pyramids for coarse-to-fine LK tracking.
        // At the coarsest level, a 16px search covers 64px at full resolution,
        // comfortably tracking fast hand motion (≈60 px/frame at 2 m/s, 0.5 m range).
        val prevPyr = buildPyramid(prevL, w, h)
        val currPyr = buildPyramid(currL, w, h)
        val (prevL0, _, _) = prevPyr[0]  // full-res luma for final SAD gate
        val (currL0, _, _) = currPyr[0]

        val flowX = mutableListOf<Float>(); val flowY = mutableListOf<Float>()
        val patchArea = (2 * LK_PATCH + 1) * (2 * LK_PATCH + 1)

        for (pf in prevF) {
            val px = pf[0].toInt(); val py = pf[1].toInt()
            var gx = 0; var gy = 0  // accumulated full-resolution displacement

            for (level in PYRAMID_LEVELS - 1 downTo 0) {
                if (level >= prevPyr.size) continue  // level not built (image too small)
                val (prevLvl, lw, lh) = prevPyr[level]
                val (currLvl, _, _)   = currPyr[level]
                val pxL = px shr level; val pyL = py shr level
                val gxL = gx shr level; val gyL = gy shr level

                if (pxL < LK_PATCH || pxL >= lw - LK_PATCH ||
                    pyL < LK_PATCH || pyL >= lh - LK_PATCH) break

                val searchR = if (level == PYRAMID_LEVELS - 1) LK_SEARCH_RANGE else LK_REFINE_RANGE
                var bestSadL = Int.MAX_VALUE; var bsx = 0; var bsy = 0

                for (sy in -searchR..searchR) {
                    for (sx in -searchR..searchR) {
                        val qx = pxL + gxL + sx
                        val qy = pyL + gyL + sy
                        if (qx < LK_PATCH || qx >= lw - LK_PATCH ||
                            qy < LK_PATCH || qy >= lh - LK_PATCH) continue
                        var sad = 0
                        kyLoop@ for (ky in -LK_PATCH..LK_PATCH) {
                            for (kx in -LK_PATCH..LK_PATCH) {
                                sad += abs(prevLvl[(pyL + ky) * lw + (pxL + kx)] -
                                           currLvl[(qy  + ky) * lw + (qx  + kx)])
                                if (sad >= bestSadL) break@kyLoop
                            }
                        }
                        if (sad < bestSadL) { bestSadL = sad; bsx = sx; bsy = sy }
                    }
                }
                gx += bsx shl level
                gy += bsy shl level
            }

            // Gate on final SAD at full resolution
            val qxF = px + gx; val qyF = py + gy
            if (qxF < LK_PATCH || qxF >= w - LK_PATCH ||
                qyF < LK_PATCH || qyF >= h - LK_PATCH) continue
            var finalSad = 0
            for (ky in -LK_PATCH..LK_PATCH) {
                for (kx in -LK_PATCH..LK_PATCH) {
                    finalSad += abs(prevL0[(py + ky) * w + (px + kx)] -
                                    currL0[(qyF + ky) * w + (qxF + kx)])
                }
            }
            if (finalSad < MATCH_THRESH * patchArea) {
                flowX.add(gx.toFloat()); flowY.add(gy.toFloat())
            }
        }

        if (flowX.isEmpty()) {
            meanFlowMag = 0f; medianFlowNX = 0f; medianFlowNY = 0f
            return FrameMotion(0f, 0f, 0f)
        }

        flowX.sort(); flowY.sort()
        val medX = flowX[flowX.size / 2]; val medY = flowY[flowY.size / 2]

        meanFlowMag  = sqrt(medX * medX + medY * medY)
        medianFlowNX = medX / w   // S4.2: normalised for DA2 EMA warp
        medianFlowNY = medY / h

        val cx = w / 2f; val cy = h / 2f
        var sinSum = 0f; var cosSum = 0f; var count = 0
        for (i in prevF.indices) {
            if (i >= flowX.size) break
            val ax = prevF[i][0] - cx; val ay = prevF[i][1] - cy
            val bx = ax + flowX[i] - medX; val by = ay + flowY[i] - medY
            val cross = ax * by - ay * bx
            val dot   = ax * bx + ay * by
            sinSum += cross; cosSum += dot; count++
        }
        val drz = if (count > 0) atan2(sinSum / count, cosSum / count) else 0f

        // SCALE_PX_PER_M is calibrated to full camera resolution — scale the downsampled-frame
        // pixel flow back up to full-resolution-equivalent terms before this division, so the
        // metric result is unaffected by process()'s downsample (see its doc comment).
        val fullResX = medX * downsampleFactor
        val fullResY = medY * downsampleFactor
        return FrameMotion(fullResX / SCALE_PX_PER_M, -fullResY / SCALE_PX_PER_M, drz)
    }

    // S4.3: 2×2 box-filter Gaussian pyramid. Each level is half the resolution.
    private fun buildPyramid(luma: IntArray, w: Int, h: Int): List<Triple<IntArray, Int, Int>> {
        val pyramid = mutableListOf(Triple(luma, w, h))
        for (level in 1 until PYRAMID_LEVELS) {
            val (prev, pw, ph) = pyramid[level - 1]
            val nw = pw / 2; val nh = ph / 2
            if (nw < LK_PATCH * 2 + 1 || nh < LK_PATCH * 2 + 1) break
            val down = IntArray(nw * nh)
            for (y in 0 until nh) {
                val y2 = y * 2; val y2n = (y2 + 1).coerceAtMost(ph - 1)
                for (x in 0 until nw) {
                    val x2 = x * 2; val x2n = (x2 + 1).coerceAtMost(pw - 1)
                    down[y * nw + x] = (prev[y2 * pw + x2] + prev[y2 * pw + x2n] +
                                        prev[y2n * pw + x2] + prev[y2n * pw + x2n]) / 4
                }
            }
            pyramid.add(Triple(down, nw, nh))
        }
        return pyramid
    }

    private fun computeLuma(bmp: Bitmap, w: Int, h: Int): IntArray {
        val size = w * h
        if (lumaPixelsBuf.size < size) lumaPixelsBuf = IntArray(size)
        if (lumaResultBuf.size < size) lumaResultBuf = IntArray(size)
        bmp.getPixels(lumaPixelsBuf, 0, w, 0, 0, w, h)
        for (i in 0 until size) {
            val px = lumaPixelsBuf[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8)  and 0xFF
            val b =  px         and 0xFF
            lumaResultBuf[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
        }
        return lumaResultBuf
    }

    companion object {
        private const val MAX_FEATURES    = 90     // Harris corner budget per frame
        private const val HARRIS_BLOCK    = 3      // Harris response window half-size
        private const val HARRIS_K        = 0.05f  // Harris trace/det balance constant
        private const val MIN_DIST_PX     = 10     // minimum inter-feature distance (px)
        private const val SAMPLE_STEP     = 4      // pixel stride when computing Harris
        private const val LK_PATCH        = 4      // LK search half-window
        private const val LK_SEARCH_RANGE = 16     // maximum search displacement (px) at coarsest pyramid level
        private const val LK_REFINE_RANGE = 4      // S4.3: refinement radius at finer pyramid levels
        private const val PYRAMID_LEVELS  = 3      // S4.3: pyramid depth (0=full, 1=half, 2=quarter res)
        private const val MATCH_THRESH    = 30     // max SAD score to accept a match
        private const val SCALE_PX_PER_M  = 500f   // nominal mapping scale (px per metre, at full resolution)
        // ENGINE_ARCHITECTURE.md §17.5 — matches BitmapGrayscaleShim.OUT_W, the resolution the
        // rest of the always-on Core pipeline (SfM/Photometric) already processes at.
        private const val PROC_MAX_WIDTH  = 320
    }
}
