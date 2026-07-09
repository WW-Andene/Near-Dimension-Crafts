package com.arhand.depth

import android.graphics.Bitmap

/**
 * FLARE — lens flare / blown-highlight confidence detector, feeds [CrossChannelArbiter]'s
 * CH_FLARE channel.
 *
 * Scope: this detects the dominant, cheap-to-measure symptom of flare — large blown-out
 * (near-saturated) highlight regions, which wash out the contrast that structured light,
 * photometric stereo, and DA2 all depend on. It does not attempt to recognise flare's
 * full phenomenology (radial streaks, ghosting, chromatic rings) — that needs pattern
 * matching against the light source position and is a much larger undertaking than a
 * per-frame confidence gate warrants. If those artefacts appear without broad saturation,
 * this will miss them.
 *
 * Integration: call [analyse] once per camera frame; feed the returned confidence into
 * the arbiter's CH_FLARE channel via `updateSourceSignal` (frame-level — this samples the
 * whole frame, not per-block regions).
 */
class FlareDetector {

    companion object {
        /** Luma (0–255) above which a pixel is considered a blown highlight. */
        const val SATURATION_LUMA = 245f
        /**
         * Fraction of sampled pixels saturated at which confidence bottoms out at 0.
         * Chosen so a normal frame with a small bright object (e.g. a window, a light
         * bulb) doesn't trigger this — only broad, flare-scale overexposure does.
         */
        const val FRACTION_CAP = 0.15f
    }

    /** Fraction of sampled pixels that were saturated in the most recent [analyse] call. */
    @Volatile var lastSaturatedFraction: Float = 0f
        private set

    /**
     * Analyse [bitmap] and return a confidence in [0,1]: 1 = no meaningful saturation,
     * 0 = at or beyond [FRACTION_CAP] of the frame blown out.
     *
     * Samples every 8th pixel, matching [DarkRoomAdaptiveSL]'s sampling density.
     */
    fun analyse(bitmap: Bitmap): Float {
        var saturated = 0; var count = 0
        for (y in 0 until bitmap.height step 8) {
            for (x in 0 until bitmap.width step 8) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8)  and 0xFF
                val b =  px         and 0xFF
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                if (luma >= SATURATION_LUMA) saturated++
                count++
            }
        }
        val fraction = if (count == 0) 0f else saturated.toFloat() / count
        lastSaturatedFraction = fraction
        return (1f - fraction / FRACTION_CAP).coerceIn(0f, 1f)
    }
}
