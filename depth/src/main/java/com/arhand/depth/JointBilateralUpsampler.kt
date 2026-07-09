package com.arhand.depth

import android.graphics.Bitmap
import kotlin.math.exp

/**
 * JBU — Joint Bilateral Upsampler.
 *
 * Upsamples a low-resolution depth grid to 2× resolution using a full-resolution
 * camera frame as a guide image. The guide prevents blurring across depth
 * discontinuities: pixels on the same surface as the query pixel receive high weight
 * (small luma difference); pixels across an edge receive near-zero weight.
 *
 * Algorithm per output pixel p:
 *   depth(p) = Σ_q [ w_s(dist(p,q)) × w_r(ΔLuma(p,q)) × depth(q) ]
 *            / Σ_q [ w_s(dist(p,q)) × w_r(ΔLuma(p,q)) ]
 *
 * Reference: Kopf et al., "Joint Bilateral Upsampling", SIGGRAPH 2007.
 */
class JointBilateralUpsampler {

    companion object {
        private const val SIGMA_SPATIAL = 1.2f  // spatial Gaussian σ (low-res pixel units)
        private const val SIGMA_RANGE   = 25f   // luma range Gaussian σ (0–255)
        private const val SEARCH_RADIUS = 2     // neighbourhood radius in low-res pixels
    }

    private val invS2 = 1f / (2f * SIGMA_SPATIAL * SIGMA_SPATIAL)
    private val invR2 = 1f / (2f * SIGMA_RANGE   * SIGMA_RANGE)

    /**
     * Upsample [depthGrid] (row-major [inW]×[inH]) to [outW]×[outH] = 2×[inW] × 2×[inH].
     *
     * @param depthGrid Low-res depth values (normalised 0–1 or metric; 0 = invalid)
     * @param inW       Input width  (number of depth grid columns)
     * @param inH       Input height (number of depth grid rows)
     * @param guide     Full-resolution camera frame used for range weighting
     * @param outW      Output width  (defaults to inW × 2)
     * @param outH      Output height (defaults to inH × 2)
     * @return          Upsampled depth grid ([outW]×[outH], row-major)
     */
    fun upsample(
        depthGrid: FloatArray,
        inW: Int, inH: Int,
        guide: Bitmap,
        outW: Int = inW * 2,
        outH: Int = inH * 2
    ): FloatArray {
        val out     = FloatArray(outW * outH)
        val gW      = guide.width
        val gH      = guide.height
        val scaleGX = gW.toFloat() / outW
        val scaleGY = gH.toFloat() / outH

        for (oy in 0 until outH) {
            for (ox in 0 until outW) {
                // Guide pixel at this output location
                val gx      = (ox * scaleGX).toInt().coerceIn(0, gW - 1)
                val gy      = (oy * scaleGY).toInt().coerceIn(0, gH - 1)
                val pLuma   = lumaOf(guide.getPixel(gx, gy))

                // Corresponding low-res coordinates (fractional)
                val lrX     = ox * inW.toFloat()  / outW
                val lrY     = oy * inH.toFloat()  / outH

                var wSum    = 0f; var dSum = 0f
                val x0 = (lrX - SEARCH_RADIUS).toInt().coerceAtLeast(0)
                val x1 = (lrX + SEARCH_RADIUS).toInt().coerceAtMost(inW - 1)
                val y0 = (lrY - SEARCH_RADIUS).toInt().coerceAtLeast(0)
                val y1 = (lrY + SEARCH_RADIUS).toInt().coerceAtMost(inH - 1)

                for (qy in y0..y1) {
                    for (qx in x0..x1) {
                        val depth = depthGrid[qy * inW + qx]
                        if (depth <= 0f) continue

                        val dx = lrX - qx; val dy = lrY - qy
                        val wSpatial = exp(-(dx * dx + dy * dy) * invS2).toFloat()

                        val qgx  = (qx.toFloat() / inW * gW).toInt().coerceIn(0, gW - 1)
                        val qgy  = (qy.toFloat() / inH * gH).toInt().coerceIn(0, gH - 1)
                        val qLuma = lumaOf(guide.getPixel(qgx, qgy))
                        val dl    = pLuma - qLuma
                        val wRange = exp(-(dl * dl) * invR2).toFloat()

                        val w = wSpatial * wRange
                        wSum += w; dSum += w * depth
                    }
                }
                out[oy * outW + ox] = if (wSum > 0f) dSum / wSum else 0f
            }
        }
        return out
    }

    private fun lumaOf(px: Int): Float {
        val r = (px shr 16) and 0xFF
        val g = (px shr 8)  and 0xFF
        val b =  px         and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}
