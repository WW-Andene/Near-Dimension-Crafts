package com.arhand.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * S3.1 — Dual-Lens Stereo Depth Source.
 *
 * Probes the device's Camera2 API for a secondary physical camera (telephoto or
 * ultra-wide) with a known physical baseline relative to the main camera. When one
 * is found, opens it as a slave and captures single frames on demand via
 * [requestCapture]. Computes block-level stereo disparity using SAD block matching
 * and converts to metric depth via: `depth = (baseline_m × focal_px) / disparity_px`.
 *
 * ## Activation conditions
 * - Device exposes ≥ 2 physical cameras via `CameraManager.getCameraIdList()`
 * - At least one secondary camera has `LENS_FACING_BACK` and a characterised focal length
 * - [FusedDepthSource] calls [start] at session open and [requestCapture] each frame
 *   (only when `lastLux ≤ 15000` — stereo not useful in direct sunlight where ARCore
 *   dominates)
 *
 * ## Degradation
 * When the device lacks a usable secondary camera, [isAvailable] = false and all
 * outputs are null. [FusedDepthSource] passes [zeroBlocks] to the arbiter for
 * CH_STEREO, leaving its base weight (0.01) inert.
 *
 * ## Block layout (S3.2 compatible)
 * Output [depthBlocks] has [STEREO_BLOCK_COUNT] = 52 entries, matching the
 * 3-tier pyramid layout used by [DepthAnythingSource] and [FusedDepthSource]:
 *   Tier 0 (2×2):   full frame, 4 blocks,  idx 0–3
 *   Tier 1 (4×4):   full frame, 16 blocks, idx 4–19
 *   Tier 2 (8×4):   center 60%, 32 blocks, idx 20–51
 */
class StereoDepthSource(private val context: Context) {

    /** True when a suitable secondary camera was found and opened. */
    @Volatile var isAvailable: Boolean = false
        private set

    /** Mean stereo confidence [0..1] across all valid disparity estimates. */
    @Volatile var confidence: Float = 0f
        private set

    /**
     * Per-block metric depth array ([STEREO_BLOCK_COUNT] entries, S3.2 pyramid layout).
     * Written from a background coroutine; null until the first successful capture.
     */
    @Volatile var depthBlocks: FloatArray? = null
        private set

    // Camera2 state for the secondary (slave) camera
    private var cameraManager:   CameraManager? = null
    private var slaveCameraId:   String?        = null
    private var slaveDevice:     CameraDevice?  = null
    private var captureSession:  CameraCaptureSession? = null
    private var imageReader:     ImageReader?   = null
    private var cameraThread:    HandlerThread? = null
    private var cameraHandler:   Handler?       = null

    // Intrinsics read from CameraCharacteristics
    private var baselineM:     Float = DEFAULT_BASELINE_M
    private var slaveFocalPx:  Float = 0f

    // Scratch buffer for luma extraction (reused across frames)
    private val slaveLuma  = IntArray(STEREO_W * STEREO_H)
    private val mainLuma   = IntArray(STEREO_W * STEREO_H)

    // Pre-allocated scratch buffers for computeDisparity — avoids per-call heap allocation
    // on the hot path (called every captured slave frame, up to 30 fps).
    private val dispMapScratch = FloatArray(STEREO_W * STEREO_H) { Float.NaN }
    private val blocksScratch  = FloatArray(STEREO_BLOCK_COUNT)
    private val countsScratch  = IntArray(STEREO_BLOCK_COUNT)
    private val bitmapLumaScratch = IntArray(STEREO_W * STEREO_H)

    // Guards computeDisparity's shared scratch buffers (mainLuma, slaveLuma,
    // dispMapScratch, ...) from overlapping coroutine launches — see requestCapture.
    private val disparityComputing = AtomicBoolean(false)

    // Double-buffer for depthBlocks publication — avoids blocks.copyOf() every stereo call.
    private val depthBlocksOutA = FloatArray(STEREO_BLOCK_COUNT)
    private val depthBlocksOutB = FloatArray(STEREO_BLOCK_COUNT)
    private var depthOutToggle  = false

    // Pre-allocated Canvas+Bitmap for extractBitmapLuma — avoids Bitmap.createScaledBitmap
    // allocation every slave frame. Recreated only on resolution change.
    private var scaledBmp:    Bitmap? = null
    private var scaledCanvas: Canvas? = null

    /**
     * Probe Camera2 for a usable secondary camera and open it.
     * Safe to call when no secondary camera is present — sets [isAvailable] = false.
     */
    fun start() {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            cameraManager = cm

            // Find back-facing cameras; the first ID is usually the main camera
            val ids = cm.cameraIdList.filter { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (ids.size < 2) return  // no secondary back camera

            // Use the second back-facing camera as slave
            slaveCameraId = ids[1]
            val chars = cm.getCameraCharacteristics(ids[1])

            // Estimate focal length in pixels from physical focal length + sensor size
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize   = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixelArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            if (focalLengths != null && sensorSize != null && pixelArraySize != null) {
                val fMm     = focalLengths[0]
                val sensorW = sensorSize.width  // mm
                val arrayW  = pixelArraySize.width.toFloat()  // px
                slaveFocalPx = fMm * arrayW / sensorW  // px/mm × mm = px
            } else {
                slaveFocalPx = 500f  // fallback: ~24mm lens on APS-C equiv.
            }

            // Estimate baseline: difference in translation from primary camera pose.
            // Camera2 doesn't expose this directly; use DEFAULT_BASELINE_M as fallback.
            // Devices that support LENS_POSE_TRANSLATION expose the extrinsic translation.
            val mainChars   = cm.getCameraCharacteristics(ids[0])
            val mainTrans   = mainChars.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
            val slaveTrans  = chars.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
            baselineM = if (mainTrans != null && slaveTrans != null) {
                val dx = slaveTrans[0] - mainTrans[0]
                val dy = slaveTrans[1] - mainTrans[1]
                val dz = slaveTrans[2] - mainTrans[2]
                sqrt(dx*dx + dy*dy + dz*dz).coerceAtLeast(0.005f)
            } else {
                DEFAULT_BASELINE_M
            }

            // Scale focal length to the STEREO_W capture resolution
            slaveFocalPx = slaveFocalPx * STEREO_W / (pixelArraySize?.width?.toFloat() ?: STEREO_W.toFloat())

            // Start camera background thread and open the slave device
            val ht = HandlerThread("StereoDepth").also { it.start() }
            cameraThread  = ht
            cameraHandler = Handler(ht.looper)

            val reader = ImageReader.newInstance(STEREO_W, STEREO_H, ImageFormat.YUV_420_888, 2)
            imageReader = reader

            cm.openCamera(ids[1], object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    slaveDevice = device
                    createCaptureSession(device, reader)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); slaveDevice = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); slaveDevice = null }
            }, cameraHandler)

        } catch (_: CameraAccessException) {
            isAvailable = false
        } catch (_: SecurityException) {
            isAvailable = false
        }
    }

    private fun createCaptureSession(device: CameraDevice, reader: ImageReader) {
        try {
            device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        isAvailable    = true
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        isAvailable = false
                    }
                },
                cameraHandler
            )
        } catch (_: CameraAccessException) { isAvailable = false }
    }

    /**
     * Request a single slave-camera frame and compute stereo disparity asynchronously.
     * Results written to [depthBlocks] and [confidence] when the capture completes.
     *
     * @param scope      CoroutineScope for the disparity computation coroutine
     * @param mainBitmap Current main-camera frame for stereo matching (may be null —
     *                   stereo result from previous cycle is held)
     */
    fun requestCapture(scope: CoroutineScope, mainBitmap: Bitmap? = null) {
        val session = captureSession ?: return
        val reader  = imageReader    ?: return
        val device  = slaveDevice    ?: return
        if (!isAvailable) return

        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()

            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    extractLuma(img, slaveLuma)
                    if (mainBitmap != null) {
                        extractBitmapLuma(mainBitmap, mainLuma)
                        // computeDisparity mutates shared scratch buffers (mainLuma,
                        // slaveLuma, dispMapScratch, ...) with no synchronization — drop
                        // this capture's disparity pass if a previous one is still
                        // running instead of letting them race on the same buffers.
                        if (disparityComputing.compareAndSet(false, true)) {
                            scope.launch(Dispatchers.Default) {
                                try {
                                    computeDisparity(mainLuma, slaveLuma)
                                } finally {
                                    disparityComputing.set(false)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // handled by caller polling isAvailable / stats — no rethrow
                } finally {
                    // Single close point: previously this ran unconditionally after
                    // extractLuma AND again in the catch block, double-closing an
                    // already-closed Image and throwing IllegalStateException whenever
                    // extractBitmapLuma (or anything after the first close) threw.
                    img.close()
                }
            }, cameraHandler)

            session.capture(req, null, cameraHandler)
        } catch (_: CameraAccessException) { isAvailable = false }
    }

    fun stop() {
        captureSession?.close(); captureSession = null
        slaveDevice?.close();    slaveDevice    = null
        imageReader?.close();    imageReader    = null
        cameraThread?.quitSafely(); cameraThread = null
        scaledBmp?.recycle();    scaledBmp    = null
        scaledCanvas             = null
        isAvailable = false
    }

    fun close() = stop()

    // ─── Stereo disparity computation ────────────────────────────────────────

    /**
     * Compute per-block metric depth from the main (left) and slave (right) luma maps.
     * Uses SAD block matching with a [DISP_SEARCH_RANGE]-pixel horizontal search window.
     * Block depth = (baseline × focalPx) / disparity_px.
     *
     * Output written to [depthBlocks] as 52-entry S3.2 pyramid.
     */
    private fun computeDisparity(mainL: IntArray, slaveL: IntArray) {
        val w = STEREO_W; val h = STEREO_H
        val bs = BLOCK_MATCH_SIZE
        // Reuse pre-allocated scratch buffers — reset to NaN/0 before use
        dispMapScratch.fill(Float.NaN)
        val dispMap = dispMapScratch
        var validCount = 0; var totalCount = 0

        // Sparse SAD block matching — sample at block-centre positions only
        val stride = bs * 2
        var y = bs
        while (y < h - bs) {
            var x = bs + DISP_SEARCH_RANGE
            while (x < w - bs) {
                var bestSad = Int.MAX_VALUE; var bestDisp = -1
                for (d in MIN_VALID_DISPARITY..DISP_SEARCH_RANGE) {
                    val rx = x - d
                    if (rx < bs) break
                    var sad = 0
                    sadLoop@ for (ky in -bs..bs) {
                        for (kx in -bs..bs) {
                            sad += abs(mainL[(y + ky) * w + (x + kx)] -
                                       slaveL[(y + ky) * w + (rx + kx)])
                            if (sad >= bestSad) break@sadLoop
                        }
                    }
                    if (sad < bestSad) { bestSad = sad; bestDisp = d }
                }
                totalCount++
                if (bestDisp >= MIN_VALID_DISPARITY) {
                    dispMap[y * w + x] = baselineM * slaveFocalPx / bestDisp
                    validCount++
                }
                x += stride
            }
            y += stride
        }

        confidence = if (totalCount > 0) validCount.toFloat() / totalCount else 0f
        if (validCount == 0) return

        // Aggregate disparity map into the S3.2 52-block pyramid — reuse scratch buffers
        blocksScratch.fill(0f)
        countsScratch.fill(0)
        val blocks = blocksScratch
        val counts = countsScratch

        for (py in 0 until h) {
            val ny = py.toFloat() / h
            for (px in 0 until w) {
                val depth = dispMap[py * w + px]
                if (depth.isNaN()) continue
                val nx = px.toFloat() / w
                val bi = depthToBlockIdx(nx, ny, depth)
                if (bi in 0 until STEREO_BLOCK_COUNT) { blocks[bi] += depth; counts[bi]++ }
            }
        }

        for (i in 0 until STEREO_BLOCK_COUNT) {
            val cnt = counts[i]
            if (cnt > 0) blocks[i] = blocks[i] / cnt.toFloat()
        }
        // Double-buffer swap: copy scratch into the inactive output buffer, then publish.
        depthOutToggle = !depthOutToggle
        val dest = if (depthOutToggle) depthBlocksOutA else depthBlocksOutB
        blocks.copyInto(dest)
        depthBlocks = dest
    }

    /** Map a depth sample at normalised (nx, ny) to the S3.2 pyramid block index. */
    private fun depthToBlockIdx(nx: Float, ny: Float, depth: Float): Int {
        return when {
            depth < 2f && nx >= 0.20f && nx <= 0.80f -> {
                val cxFrac = (nx - 0.20f) / 0.60f
                val bx = (cxFrac * 8f).toInt().coerceIn(0, 7)
                val by = (ny * 4f).toInt().coerceIn(0, 3)
                20 + by * 8 + bx
            }
            depth < 5f -> {
                val bx = (nx * 4f).toInt().coerceIn(0, 3)
                val by = (ny * 4f).toInt().coerceIn(0, 3)
                4 + by * 4 + bx
            }
            else -> {
                val bx = (nx * 2f).toInt().coerceIn(0, 1)
                val by = (ny * 2f).toInt().coerceIn(0, 1)
                by * 2 + bx
            }
        }
    }

    // ─── Luma extraction ─────────────────────────────────────────────────────

    private fun extractLuma(image: android.media.Image, out: IntArray) {
        val yPlane   = image.planes[0]
        val yBuf     = yPlane.buffer
        val rowStride = yPlane.rowStride
        for (row in 0 until STEREO_H) {
            val srcOff = row * rowStride
            val dstOff = row * STEREO_W
            for (col in 0 until STEREO_W) {
                out[dstOff + col] = (yBuf.get(srcOff + col).toInt() and 0xFF)
            }
        }
    }

    private fun extractBitmapLuma(bmp: Bitmap, out: IntArray) {
        // Reuse a pre-allocated Bitmap+Canvas rather than calling createScaledBitmap each frame.
        if (scaledBmp == null || scaledBmp!!.width != STEREO_W || scaledBmp!!.height != STEREO_H) {
            scaledBmp?.recycle()
            scaledBmp    = Bitmap.createBitmap(STEREO_W, STEREO_H, Bitmap.Config.ARGB_8888)
            scaledCanvas = Canvas(scaledBmp!!)
        }
        scaledCanvas!!.drawBitmap(bmp, null, RectF(0f, 0f, STEREO_W.toFloat(), STEREO_H.toFloat()), null)
        val pixels = bitmapLumaScratch
        scaledBmp!!.getPixels(pixels, 0, STEREO_W, 0, 0, STEREO_W, STEREO_H)
        for (i in pixels.indices) {
            val px = pixels[i]
            out[i] = ((((px shr 16) and 0xFF) * 77 +
                       ((px shr  8) and 0xFF) * 150 +
                       ( px         and 0xFF) * 29) shr 8)
        }
    }

    companion object {
        private const val STEREO_W            = 320    // capture resolution
        private const val STEREO_H            = 240
        private const val BLOCK_MATCH_SIZE    = 8      // SAD block half-size (pixels)
        private const val DISP_SEARCH_RANGE   = 64     // max disparity search range (pixels)
        private const val MIN_VALID_DISPARITY = 2      // below this → depth undefined
        private const val STEREO_BLOCK_COUNT  = 52     // matches FusedDepthSource.BLOCK_COUNT (S3.2)

        // Fallback physical baseline when inter-lens distance can't be read from EXIF (metres)
        private const val DEFAULT_BASELINE_M  = 0.015f
    }
}
