package com.arhand.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Converts CameraX ImageProxy (RGBA_8888 — see [CameraController]'s
 * `setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)`) to Bitmap and emits via SharedFlow.
 *
 * CameraX does the YUV -> RGBA conversion itself via its own internal (hardware-
 * accelerated) path before the analyzer ever sees the frame, so this class no longer
 * does a ~300K-iteration manual YUV math loop on every single camera frame — the common
 * case is one bulk [Bitmap.copyPixelsFromBuffer] call. That YUV loop ran unconditionally
 * on every frame regardless of any inference throttling, making it one of the largest
 * always-on per-frame CPU costs in the whole tracking pipeline.
 *
 * Uses proxy.imageInfo.rotationDegrees for per-frame rotation metadata supplied
 * by CameraX — no manual sensor-orientation + display-rotation calculation needed.
 *
 * Pre-allocated pixel buffers are reused every frame after warm-up to avoid
 * per-frame heap allocation, but rotated through a small [POOL_SIZE] pool rather
 * than a single shared instance: with [_frames]'s buffer capacity of 2, up to
 * `2 + 1` (buffered + in-flight collector) Bitmaps can be alive at once, so
 * reusing just one buffer let a slow collector read pixels being overwritten by
 * the next frame(s). Cycling through [POOL_SIZE] buffers keeps each emitted
 * Bitmap stable until the pool wraps back around to it.
 */
class CameraFrameProvider {
    private companion object {
        // extraBufferCapacity (2) + 1 in-flight collector reference.
        const val POOL_SIZE = 3
    }

    private val _frames = MutableSharedFlow<Bitmap>(extraBufferCapacity = 2)
    val frames: SharedFlow<Bitmap> = _frames

    /** Set by CameraController on each bind — used by SpatialFrameProducer for mirror logic. */
    @Volatile var isFrontCamera: Boolean = true

    private val argbPixelsPool  = arrayOfNulls<IntArray>(POOL_SIZE)
    private val outputBitmapPool = arrayOfNulls<Bitmap>(POOL_SIZE)
    private var lastWidth  = 0
    private var lastHeight = 0
    private var bitmapPoolIndex = 0

    private val rotatedBitmapPool = arrayOfNulls<Bitmap>(POOL_SIZE)
    private val rotatedCanvasPool = arrayOfNulls<Canvas>(POOL_SIZE)
    private var rotatedPoolIndex = 0

    fun onImageProxy(proxy: ImageProxy) {
        try {
            val image  = proxy.image ?: return
            val rotDeg = proxy.imageInfo.rotationDegrees
            val bitmap = rgbaToBitmap(image)
            val toEmit = if (rotDeg != 0) {
                val (rw, rh) = if (rotDeg == 90 || rotDeg == 270)
                    bitmap.height to bitmap.width else bitmap.width to bitmap.height

                rotatedPoolIndex = (rotatedPoolIndex + 1) % POOL_SIZE
                val rot = rotatedBitmapPool[rotatedPoolIndex]?.takeIf { it.width == rw && it.height == rh }
                    ?: Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888).also {
                        rotatedBitmapPool[rotatedPoolIndex] = it
                        rotatedCanvasPool[rotatedPoolIndex] = Canvas(it)
                    }
                val canvas = rotatedCanvasPool[rotatedPoolIndex]!!
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

    private fun rgbaToBitmap(image: android.media.Image): Bitmap {
        val width  = image.width
        val height = image.height

        if (width != lastWidth || height != lastHeight || outputBitmapPool[0] == null) {
            for (i in 0 until POOL_SIZE) {
                outputBitmapPool[i] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            lastWidth  = width
            lastHeight = height
        }
        bitmapPoolIndex = (bitmapPoolIndex + 1) % POOL_SIZE
        val bitmap = outputBitmapPool[bitmapPoolIndex]!!

        val plane       = image.planes[0]
        val buffer      = plane.buffer
        val rowStride   = plane.rowStride
        val pixelStride = plane.pixelStride
        buffer.rewind()

        if (rowStride == width * pixelStride) {
            // Tightly packed — RGBA_8888's byte layout (R,G,B,A per pixel) matches
            // Bitmap.Config.ARGB_8888's in-memory byte layout exactly, so this is a
            // single bulk copy instead of a per-pixel loop.
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // Row padding present (stride > width*4, seen on some devices/resolutions) —
            // copy row by row. Still just a byte reorder, no YUV chroma math.
            val pixels = argbPixelsPool[bitmapPoolIndex] ?: IntArray(width * height).also {
                argbPixelsPool[bitmapPoolIndex] = it
            }
            for (row in 0 until height) {
                var pos = row * rowStride
                for (col in 0 until width) {
                    val r = buffer.get(pos).toInt()     and 0xFF
                    val g = buffer.get(pos + 1).toInt() and 0xFF
                    val b = buffer.get(pos + 2).toInt() and 0xFF
                    val a = buffer.get(pos + 3).toInt() and 0xFF
                    pixels[row * width + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    pos += pixelStride
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        return bitmap
    }
}
