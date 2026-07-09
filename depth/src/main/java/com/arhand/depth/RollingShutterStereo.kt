package com.arhand.depth

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * RS-STEREO — Rolling-Shutter Stereo depth estimator.
 *
 * CMOS sensors expose rows sequentially (top → bottom). When the camera is moving
 * horizontally, rows captured at different times see the scene from slightly different
 * positions — effectively forming a stereo pair within a single frame. The baseline
 * (in pixels) equals:
 *
 *   baseline = lateral_velocity_px_per_frame × row_delay_sec × rows_between_strips
 *
 * This source processes consecutive frames to estimate lateral velocity, then computes
 * per-column disparity between the top and bottom strips of the current frame using
 * Sum-of-Absolute-Differences (SAD) block matching.
 *
 * Depth is estimated as:   z = baseline × focal_px / disparity
 *
 * Output resolution matches the SL grid (8×6 blocks by default).
 */
class RollingShutterStereo {

    companion object {
        // Fraction of frame height used as top/bottom strips for disparity matching
        private const val STRIP_FRACTION  = 0.15f
        // Row-scan time (seconds) for a typical 30fps CMOS sensor
        private const val ROW_DELAY_SEC   = 1f / 30f / 480f
        // SAD block matching half-size (pixels)
        private const val PATCH_HALF      = 4
        // Maximum horizontal disparity search range
        private const val MAX_DISP        = 32
        // Minimum disparity required to accept a depth estimate
        private const val MIN_DISPARITY   = 1.0f
        // Approximate focal length (pixels) for a ~320-wide view at ~60° HFOV
        private const val FOCAL_PX        = 265f
    }

    /** Per-block (8×6) normalised depth [0–1] from the most recent [process] call, or null. */
    @Volatile var lastDepthBlocks: FloatArray? = null
        private set

    /** Estimated horizontal velocity (px/frame) used for the latest baseline estimate. */
    @Volatile var lastVelocityPx: Float = 0f
        private set

    private var prevBitmap: Bitmap? = null

    /**
     * Process [bitmap]. Estimates lateral camera velocity against the previous frame,
     * computes top/bottom strip disparity, and writes [lastDepthBlocks].
     *
     * @param bitmap  Current camera frame
     * @param blockW  Output grid width  (default 8)
     * @param blockH  Output grid height (default 6)
     */
    fun process(bitmap: Bitmap, blockW: Int = 8, blockH: Int = 6) {
        val prev = prevBitmap
        prevBitmap = bitmap

        if (prev == null || prev.width != bitmap.width || prev.height != bitmap.height) {
            lastDepthBlocks = null
            return
        }

        val w = bitmap.width; val h = bitmap.height

        // Estimate horizontal velocity from the top strip of consecutive frames
        val stripH = (h * STRIP_FRACTION).toInt().coerceAtLeast(PATCH_HALF * 2 + 1)
        val vx = estimateHorizontalShift(prev, bitmap, 0, stripH, w)
        lastVelocityPx = vx

        if (abs(vx) < MIN_DISPARITY) {
            lastDepthBlocks = null
            return
        }

        val topY = 0
        val botY = h - stripH
        // Physical baseline in pixels
        val baselinePx = abs(vx) * ROW_DELAY_SEC * (h - stripH).toFloat()
        if (baselinePx < 0.01f) { lastDepthBlocks = null; return }

        val depth  = FloatArray(blockW * blockH)
        val colW   = w.toFloat() / blockW
        val sign   = if (vx > 0) 1 else -1

        for (bx in 0 until blockW) {
            val cx     = ((bx + 0.5f) * colW).toInt().coerceIn(0, w - 1)
            val topY2  = (topY + stripH / 2).coerceIn(0, h - 1)
            val botY2  = (botY + stripH / 2).coerceIn(0, h - 1)
            val disp   = computeSADDisparity(bitmap, cx, topY2, botY2, w, h, sign)

            val z = if (disp >= MIN_DISPARITY) (baselinePx * FOCAL_PX / disp) else 0f
            // Normalise: assume max metric depth = 3 m
            val zNorm = (z / 3f).coerceIn(0f, 1f)
            for (by in 0 until blockH) depth[by * blockW + bx] = zNorm
        }

        lastDepthBlocks = depth
    }

    /** Estimate horizontal pixel shift between top strips of two frames via SAD. */
    private fun estimateHorizontalShift(
        prev: Bitmap, curr: Bitmap,
        stripY: Int, stripH: Int, w: Int
    ): Float {
        var bestShift = 0; var bestSad = Int.MAX_VALUE
        val cx = w / 2
        for (shift in -MAX_DISP..MAX_DISP) {
            var sad = 0
            for (y in stripY until (stripY + stripH) step 4) {
                for (dx in -PATCH_HALF..PATCH_HALF step 2) {
                    val px = (cx + dx).coerceIn(0, w - 1)
                    val sx = (cx + dx + shift).coerceIn(0, w - 1)
                    sad  += abs(lumaOf(prev.getPixel(px, y)) - lumaOf(curr.getPixel(sx, y)))
                }
            }
            if (sad < bestSad) { bestSad = sad; bestShift = shift }
        }
        return bestShift.toFloat()
    }

    /** SAD block matching between top and bottom strip at column [cx]. */
    private fun computeSADDisparity(
        bmp: Bitmap, cx: Int, topY: Int, botY: Int, w: Int, h: Int, sign: Int
    ): Float {
        var bestDisp = 0; var bestSad = Int.MAX_VALUE
        for (d in 0..MAX_DISP) {
            var sad = 0
            for (dy in -PATCH_HALF..PATCH_HALF) {
                for (dx in -PATCH_HALF..PATCH_HALF) {
                    val tx = (cx + dx).coerceIn(0, w - 1)
                    val ty = (topY + dy).coerceIn(0, h - 1)
                    val bx = (cx + dx + sign * d).coerceIn(0, w - 1)
                    val by = (botY + dy).coerceIn(0, h - 1)
                    sad  += abs(lumaOf(bmp.getPixel(tx, ty)) - lumaOf(bmp.getPixel(bx, by)))
                }
            }
            if (sad < bestSad) { bestSad = sad; bestDisp = d }
        }
        return bestDisp.toFloat()
    }

    private fun lumaOf(px: Int): Int {
        val r = (px shr 16) and 0xFF
        val g = (px shr 8)  and 0xFF
        val b =  px         and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b).toInt()
    }

    /** Reset inter-frame state (call on camera switch or resume). */
    fun reset() {
        prevBitmap   = null
        lastDepthBlocks = null
        lastVelocityPx  = 0f
    }
}
