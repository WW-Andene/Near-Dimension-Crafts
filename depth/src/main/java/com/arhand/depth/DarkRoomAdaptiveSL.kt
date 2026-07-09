package com.arhand.depth

import android.graphics.Bitmap

/**
 * DRASL — Dark-Room Adaptive Structured Light.
 *
 * Computes mean frame luma and returns a SL channel weight multiplier for
 * [CrossChannelArbiter]. In darkness, SL contrast-to-noise improves while
 * DA2 and SfM optical flow degrade, so the arbiter should up-weight SL.
 *
 * Integration: call [analyse] once per camera frame inside FusedDepthSource's
 * bitmap shim callback. Pass the returned multiplier to the arbiter's SL channel
 * base weight before calling [CrossChannelArbiter.compute].
 */
class DarkRoomAdaptiveSL {

    companion object {
        /** Mean luma (0–255) below which the SL boost begins. */
        const val DARK_LUMA_THRESHOLD = 40f
        /** Maximum SL weight multiplier at full darkness. */
        const val MAX_BOOST           = 2.5f
    }

    /** Mean luma of the most recently processed frame, range 0–255. */
    @Volatile var lastMeanLuma: Float = 128f
        private set

    /** True when the scene is dark enough to activate the boost. */
    val isDark: Boolean get() = lastMeanLuma < DARK_LUMA_THRESHOLD

    /**
     * Analyse [bitmap] and return the SL channel weight multiplier for this frame.
     *
     * Returns 1.0 in normal lighting; up to [MAX_BOOST] at zero luma.
     * Samples every 8th pixel (~1 200 samples on a 320×240 frame).
     */
    fun analyse(bitmap: Bitmap): Float {
        var sum = 0L; var count = 0
        for (y in 0 until bitmap.height step 8) {
            for (x in 0 until bitmap.width step 8) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8)  and 0xFF
                val b =  px         and 0xFF
                sum  += (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                count++
            }
        }
        val luma = if (count == 0) 128f else sum.toFloat() / count
        lastMeanLuma = luma
        if (luma >= DARK_LUMA_THRESHOLD) return 1f
        val dark = 1f - (luma / DARK_LUMA_THRESHOLD)
        return 1f + (MAX_BOOST - 1f) * dark
    }
}
