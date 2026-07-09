package com.arhand.depth

import android.graphics.Bitmap

/**
 * MOIRE — moiré-interference confidence detector, feeds [CrossChannelArbiter]'s
 * CH_MOIRE channel.
 *
 * Moiré appears when a fine, regular pattern in the scene (a screen's pixel grid, woven
 * fabric, window blinds) aliases against the camera sensor's own pixel grid, producing a
 * luma oscillation that flips sign almost every sampled pixel — far more often than real
 * image content, where brightness changes are comparatively smooth or edge-like rather
 * than a per-pixel zigzag. This is a real, if modest, texture heuristic (a sign-change
 * density measure), not full moiré-frequency analysis — it will miss subtle moiré and can
 * be fooled by genuinely fine natural texture (dense foliage, fabric weave close up), but
 * those are rare in the hand/body scanning frame this pipeline actually processes.
 *
 * Integration: call [analyse] once per camera frame; feed the returned confidence into the
 * arbiter's CH_MOIRE channel via `updateSourceSignal` (frame-level, like [FlareDetector]).
 */
class MoireDetector {

    companion object {
        /**
         * Expected sign-change ("zigzag") rate for ordinary image content sampled at
         * this stride — natural gradients and edges alternate less than half the time.
         * Confidence only starts dropping once the measured rate exceeds this baseline.
         */
        const val BASELINE_ALTERNATION_RATE = 0.55f
        private const val SCANLINES     = 4
        private const val SAMPLE_STRIDE = 2
    }

    /** Sign-change rate of the most recent [analyse] call's scanlines. */
    @Volatile var lastAlternationRate: Float = 0f
        private set

    /**
     * Analyse [bitmap] and return a confidence in [0,1]: 1 = alternation rate at or below
     * [BASELINE_ALTERNATION_RATE] (ordinary content), 0 = every sampled step flips sign
     * (the degenerate high-frequency case moiré aliasing produces).
     *
     * Samples a handful of horizontal scanlines at 2px pitch — moiré's per-pixel
     * oscillation only needs adjacent-sample comparison, not the 8px stride used
     * elsewhere for coarse luma/saturation stats.
     */
    fun analyse(bitmap: Bitmap): Float {
        val w = bitmap.width; val h = bitmap.height
        val samplesPerLine = w / SAMPLE_STRIDE
        if (samplesPerLine < 3 || h < SCANLINES) return 1f

        var flips = 0
        var deltaPairs = 0

        for (line in 1..SCANLINES) {
            val y = (h * line) / (SCANLINES + 1)

            // Luma at each sampled x position along this scanline
            var prevLuma = lumaAt(bitmap, 0, y)
            var prevDelta = 0f
            var haveDelta = false

            var sampleX = SAMPLE_STRIDE
            while (sampleX < w) {
                val luma  = lumaAt(bitmap, sampleX, y)
                val delta = luma - prevLuma
                if (delta != 0f) {
                    if (haveDelta && prevDelta != 0f) {
                        deltaPairs++
                        if ((delta > 0f) != (prevDelta > 0f)) flips++
                    }
                    prevDelta  = delta
                    haveDelta  = true
                }
                prevLuma = luma
                sampleX += SAMPLE_STRIDE
            }
        }

        val rate = if (deltaPairs == 0) 0f else flips.toFloat() / deltaPairs
        lastAlternationRate = rate
        if (rate <= BASELINE_ALTERNATION_RATE) return 1f
        return (1f - (rate - BASELINE_ALTERNATION_RATE) / (1f - BASELINE_ALTERNATION_RATE)).coerceIn(0f, 1f)
    }

    private fun lumaAt(bitmap: Bitmap, x: Int, y: Int): Float {
        val px = bitmap.getPixel(x, y)
        val r = (px shr 16) and 0xFF
        val g = (px shr 8)  and 0xFF
        val b =  px         and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}
