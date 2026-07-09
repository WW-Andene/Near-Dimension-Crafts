package com.arhand.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

/**
 * Camera2 pipeline for DepthLab depth modes.
 *
 * Delivers grayscale [FloatArray] frames (BT.601 Y-plane extraction, no chroma needed)
 * at [TARGET_W]×[TARGET_H] to a [FrameListener].
 *
 * Separate from Handy's CameraController/CameraFrameProvider which produce full ARGB
 * Bitmaps for MediaPipe. Here we skip the chroma planes entirely — ~3× less data
 * to copy per frame, no Bitmap allocation.
 *
 * Width/height are chosen to match lidar.html's PW=320, PH=240 so the Harris/LK
 * constants (FX ≈ 265) are directly portable.
 */
class GrayscaleCamera(private val context: Context) {

    companion object {
        const val TARGET_W = 320
        const val TARGET_H = 240
    }

    fun interface FrameListener {
        /** Called on camera handler thread. gray is Y-plane [0..1], size = W×H. */
        fun onFrame(gray: FloatArray, width: Int, height: Int, timestampMs: Long)
    }

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    // Pre-allocated gray buffer — reused every frame (zero heap after first frame)
    private var grayBuf: FloatArray? = null

    @SuppressLint("MissingPermission")
    fun start(listener: FrameListener, facingBack: Boolean = true) {
        cameraThread = HandlerThread("DepthLabCamera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facing = if (facingBack) CameraCharacteristics.LENS_FACING_BACK
                     else            CameraCharacteristics.LENS_FACING_FRONT
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        } ?: manager.cameraIdList.first()

        imageReader = ImageReader.newInstance(TARGET_W, TARGET_H, ImageFormat.YUV_420_888, 4)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val yPlane    = image.planes[0]
                val yBuf      = yPlane.buffer
                val rowStride = yPlane.rowStride
                val w = image.width
                val h = image.height
                val tsMs = image.timestamp / 1_000_000L

                val gray = grayBuf?.takeIf { it.size == w * h }
                    ?: FloatArray(w * h).also { grayBuf = it }

                // Extract Y plane — each row may have padding (rowStride >= width)
                for (row in 0 until h) {
                    for (col in 0 until w) {
                        gray[row * w + col] = (yBuf.get(row * rowStride + col).toInt() and 0xFF) / 255f
                    }
                }
                listener.onFrame(gray, w, h, tsMs)
            } finally {
                image.close()
            }
        }, cameraHandler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                cameraDevice = cam
                val surface = imageReader!!.surface
                cam.createCaptureSession(listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    android.util.Range(15, 30))
                            }.build()
                            session.setRepeatingRequest(req, null, cameraHandler)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, cameraHandler)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close() }
            override fun onError(cam: CameraDevice, error: Int) { cam.close() }
        }, cameraHandler)
    }

    fun stop() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice   = null
        imageReader?.close();    imageReader    = null
        cameraThread?.quitSafely(); cameraThread = null; cameraHandler = null
        grayBuf = null
    }

    /**
     * Torch control — rear camera only, no-op on front.
     * Call after [start].
     */
    fun setTorch(on: Boolean) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull { cid ->
            manager.getCameraCharacteristics(cid)
                .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
        } ?: return
        try { manager.setTorchMode(id, on) } catch (_: Throwable) {}
    }
}
