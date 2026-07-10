package com.arhand.camera

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Feeds Handy's existing [CameraFrameProvider] Bitmap stream into [SfMDepthSource] and
 * [PhotometricDepthSource] without opening a second camera session.
 *
 * Problem: both SfM and Photometric run their own [GrayscaleCamera] internally, which
 * opens a second Camera2 session on the same physical camera. While Android 9+ permits
 * concurrent sessions at different resolutions, it's not guaranteed on all devices and
 * adds complexity.
 *
 * Solution: [SfMDepthSource] and [PhotometricDepthSource] each accept a
 * [GrayscaleCamera.FrameListener]. This shim converts the Bitmap frames that
 * [CameraFrameProvider] already produces into the `FloatArray (Y-plane, [0..1])` format
 * those listeners expect — at 320×240 — without any additional camera resources.
 *
 * Usage in AppViewModel:
 * ```kotlin
 * val shim = BitmapGrayscaleShim()
 * sfmSource.attachShim(shim)          // must be called before sfmSource.start()
 * frameProvider.frames.collect { bmp ->
 *     shim.onBitmap(bmp, System.currentTimeMillis())
 * }
 * ```
 *
 * ## Multiple listeners (ENGINE_ARCHITECTURE.md §4.12)
 *
 * SfM is always-on for the whole app session; Photometric additionally attaches only
 * while a scan's reconstruction sources are active (`FusedDepthSource.setReconstructionActive`).
 * Both attach to the *same* shim instance. A single-slot `setListener` here previously meant
 * Photometric's `start()` silently clobbered SfM's registration the moment a scan began, and
 * `stop()` cleared the slot entirely — SfM never re-registered itself afterward, so it went
 * permanently silent (no more grounding fallback) from the first scan of the session onward.
 * [addListener]/[removeListener] let both coexist without clobbering each other.
 *
 * Thread safety: [onBitmap] is called from the camera coroutine; listeners are called
 * synchronously on the same thread, so a plain list needs no synchronization. The grayscale
 * conversion runs once per frame regardless of listener count — dispatching to N listeners
 * doesn't multiply the conversion cost, only the (cheap) callback invocation.
 */
class BitmapGrayscaleShim {

    companion object {
        const val OUT_W = GrayscaleCamera.TARGET_W  // 320
        const val OUT_H = GrayscaleCamera.TARGET_H  // 240
    }

    private val listeners = ArrayList<GrayscaleCamera.FrameListener>(2)
    private var grayBuf = FloatArray(OUT_W * OUT_H)

    // Scale matrix — computed once, reused every frame
    private var scaleMatrix: Matrix? = null
    private var lastSrcW = 0
    private var lastSrcH = 0
    // Pre-allocated pixel scratch — avoids per-frame IntArray allocation
    private val pixelBuf = IntArray(OUT_W * OUT_H)

    fun addListener(l: GrayscaleCamera.FrameListener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: GrayscaleCamera.FrameListener) {
        listeners.remove(l)
    }

    /**
     * Convert [bitmap] to a 320×240 grayscale FloatArray and dispatch to every listener.
     * Skips processing entirely if no listener is attached.
     *
     * The input Bitmap may be any size — it is scaled to 320×240 then Y-extracted.
     * Uses a single [Bitmap.getPixels] bulk read (~30× faster than per-pixel getPixel).
     * BT.601 luma: Y ≈ 0.299R + 0.587G + 0.114B.
     */
    fun onBitmap(bitmap: Bitmap, timestampMs: Long) {
        if (listeners.isEmpty()) return

        val scaled: Bitmap = if (bitmap.width == OUT_W && bitmap.height == OUT_H) {
            bitmap
        } else {
            if (scaleMatrix == null || bitmap.width != lastSrcW || bitmap.height != lastSrcH) {
                scaleMatrix = Matrix().apply {
                    setScale(OUT_W.toFloat() / bitmap.width, OUT_H.toFloat() / bitmap.height)
                }
                lastSrcW = bitmap.width; lastSrcH = bitmap.height
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, scaleMatrix, false)
        }

        // Single bulk read into pre-allocated buffer
        scaled.getPixels(pixelBuf, 0, OUT_W, 0, 0, OUT_W, OUT_H)

        val gray = grayBuf
        for (i in pixelBuf.indices) {
            val px = pixelBuf[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8)  and 0xFF
            val b =  px         and 0xFF
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        if (scaled !== bitmap) scaled.recycle()

        for (i in listeners.indices) listeners[i].onFrame(gray, OUT_W, OUT_H, timestampMs)
    }
}
