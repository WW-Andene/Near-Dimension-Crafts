package com.arhand.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Converts CameraX ImageProxy (YUV_420_888) to Bitmap and emits via SharedFlow.
 *
 * Uses proxy.imageInfo.rotationDegrees for per-frame rotation metadata supplied
 * by CameraX — no manual sensor-orientation + display-rotation calculation needed.
 *
 * Pre-allocated pixel buffers are reused every frame after warm-up to avoid
 * per-frame heap allocation.
 */
class CameraFrameProvider {
    private val _frames = MutableSharedFlow<Bitmap>(extraBufferCapacity = 2)
    val frames: SharedFlow<Bitmap> = _frames

    /** Set by CameraController on each bind — used by SpatialFrameProducer for mirror logic. */
    @Volatile var isFrontCamera: Boolean = true

    private var argbPixels:  IntArray? = null
    private var outputBitmap: Bitmap?  = null
    private var lastWidth  = 0
    private var lastHeight = 0
    private var rotatedBitmap: Bitmap? = null
    private var rotatedCanvas: Canvas? = null

    fun onImageProxy(proxy: ImageProxy) {
        try {
            val image  = proxy.image ?: return
            val rotDeg = proxy.imageInfo.rotationDegrees
            val bitmap = yuv420ToBitmap(image)
            val toEmit = if (rotDeg != 0) {
                val (rw, rh) = if (rotDeg == 90 || rotDeg == 270)
                    bitmap.height to bitmap.width else bitmap.width to bitmap.height
                val rot = rotatedBitmap?.takeIf { it.width == rw && it.height == rh }
                    ?: Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888).also {
                        rotatedBitmap = it
                        rotatedCanvas = Canvas(it)
                    }
                val canvas = rotatedCanvas!!
                val matrix = Matrix()
                matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
                matrix.postRotate(rotDeg.toFloat())
                matrix.postTranslate(rw / 2f, rh / 2f)
                canvas.drawBitmap(bitmap, matrix, null)
                rot
            } else {
                bitmap
            }
            _frames.tryEmit(toEmit)
        } finally {
            proxy.close()
        }
    }

    private fun yuv420ToBitmap(image: android.media.Image): Bitmap {
        val width  = image.width
        val height = image.height

        if (width != lastWidth || height != lastHeight || argbPixels == null) {
            argbPixels   = IntArray(width * height)
            outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            lastWidth    = width
            lastHeight   = height
        }
        val pixels = argbPixels!!
        val bitmap = outputBitmap!!

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf          = yPlane.buffer
        val uBuf          = uPlane.buffer
        val vBuf          = vPlane.buffer
        val yRowStride    = yPlane.rowStride
        val uvRowStride   = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        for (row in 0 until height) {
            val uvRow = row shr 1
            for (col in 0 until width) {
                val uvCol = col shr 1
                val yPos  = row * yRowStride + col
                val y     = (yBuf.get(yPos).toInt() and 0xFF) - 16
                val uvPos = uvRow * uvRowStride + uvCol * uvPixelStride
                val u     = (uBuf.get(uvPos).toInt() and 0xFF) - 128
                val v     = (vBuf.get(uvPos).toInt() and 0xFF) - 128
                val yScaled = 298 * y + 128
                val r = minOf(255, maxOf(0, (yScaled + 409 * v)           shr 8))
                val g = minOf(255, maxOf(0, (yScaled - 100 * u - 208 * v) shr 8))
                val b = minOf(255, maxOf(0, (yScaled + 516 * u)           shr 8))
                pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
