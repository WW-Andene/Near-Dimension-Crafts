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
 * Output is the same S3.2 52-entry 3-tier spatial pyramid every other channel in
 * [com.arhand.depth.fusion.FusedDepthSource] uses (see [StereoDepthSource]'s identical
 * [depthToBlockIdx] for the tier geometry) — RS-stereo only resolves depth per column
 * (rows within a column share one estimate, since top/bottom strip disparity is
 * inherently 1D horizontal), so each column's estimate is broadcast across every
 * pyramid block whose (nx, ny, depth) it could plausibly land in.
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
        // S3.2 pyramid size — must match FusedDepthSource.BLOCK_COUNT / StereoDepthSource.STEREO_BLOCK_COUNT
        private const val BLOCK_COUNT     = 52
        // Vertical samples used to broadcast each column's single depth estimate
        // across the pyramid's ny-resolution (this source has no real per-row detail)
        private const val NY_SAMPLES      = 8
    }

    /** Per-block (S3.2 52-entry pyramid) normalised depth [0–1] from the most recent [process] call, or null. */
    @Volatile var lastDepthBlocks: FloatArray? = null
        private set

    /** Estimated horizontal velocity (px/frame) used for the latest baseline estimate. */
    @Volatile var lastVelocityPx: Float = 0f
        private set

    private var prevBitmap: Bitmap? = null

    /**
     * Process [bitmap]. Estimates lateral camera velocity against the previous frame,
     * computes top/bottom strip disparity, and writes [lastDepthBlocks] as a 52-entry
     * S3.2 pyramid (see class doc).
     *
     * @param bitmap  Current camera frame
     * @param blockW  Number of columns sampled for disparity estimation (default 8) —
     *                not the output block count, which is always [BLOCK_COUNT].
     */
    fun process(bitmap: Bitmap, blockW: Int = 8) {
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

        val blocks = FloatArray(BLOCK_COUNT)
        val counts = IntArray(BLOCK_COUNT)
        val colW   = w.toFloat() / blockW
        val sign   = if (vx > 0) 1 else -1

        for (bx in 0 until blockW) {
            val cx     = ((bx + 0.5f) * colW).toInt().coerceIn(0, w - 1)
            val topY2  = (topY + stripH / 2).coerceIn(0, h - 1)
            val botY2  = (botY + stripH / 2).coerceIn(0, h - 1)
            val disp   = computeSADDisparity(bitmap, cx, topY2, botY2, w, h, sign)
            if (disp < MIN_DISPARITY) continue

            val z = baselinePx * FOCAL_PX / disp
            // Normalise: assume max metric depth = 3 m
            val zNorm = (z / 3f).coerceIn(0f, 1f)
            val nx = (bx + 0.5f) / blockW

            // No real per-row detail (top/bottom-strip disparity is 1D horizontal) —
            // broadcast this column's estimate across the pyramid's ny-resolution so
            // every block this column could land in (across near/mid/far tiers) gets it.
            for (nySample in 0 until NY_SAMPLES) {
                val ny = (nySample + 0.5f) / NY_SAMPLES
                val bi = depthToBlockIdx(nx, ny, z)
                blocks[bi] += zNorm
                counts[bi]++
            }
        }

        for (i in 0 until BLOCK_COUNT) {
            if (counts[i] > 0) blocks[i] /= counts[i].toFloat()
        }
        lastDepthBlocks = blocks
    }

    /**
     * Map a depth sample at normalised (nx, ny) to the S3.2 pyramid block index.
     * Identical geometry to [StereoDepthSource.depthToBlockIdx] / [FusedDepthSource]'s
     * mapPointToBlockIndex — kept as a local copy since each depth source computes its
     * own (nx, ny, depth) samples independently and there's no shared point type to
     * hang a common helper off without introducing a cross-source dependency.
     */
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
