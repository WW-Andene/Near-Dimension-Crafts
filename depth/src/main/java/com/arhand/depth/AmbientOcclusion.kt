package com.arhand.depth

import kotlin.math.max

/**
 * AmbientOcclusion — proxy AO from neighbour depth comparison.
 *
 * For each depth block, samples its neighbourhood within [radius] blocks and
 * computes what fraction of neighbours have depth values significantly less than
 * the current block (i.e., neighbours that are closer to the camera occlude the
 * current block from ambient light arriving from those directions).
 *
 * This is a coarse approximation of screen-space AO at block resolution. It
 * correctly darkens concave regions (e.g. finger creases) and corners.
 *
 * AO value ∈ [0, 1]:  0 = fully occluded, 1 = fully lit.
 */
class AmbientOcclusion {

    companion object {
        /** Depth difference (normalised units) that counts as an occluder. */
        private const val OCCLUDE_THRESH = 0.05f
    }

    /**
     * Compute per-block ambient occlusion from [depthGrid] ([bW]×[bH], row-major).
     *
     * @param depthGrid Normalised depth [0–1]; 0 = invalid
     * @param bW        Grid width
     * @param bH        Grid height
     * @param radius    Neighbourhood search radius in blocks (default 1)
     * @return          Per-block AO [0–1], same layout as [depthGrid]
     */
    fun compute(depthGrid: FloatArray, bW: Int, bH: Int, radius: Int = 1): FloatArray {
        val ao = FloatArray(bW * bH) { 1f }  // default fully lit

        for (j in 0 until bH) {
            for (i in 0 until bW) {
                val idx   = j * bW + i
                val depth = depthGrid[idx]
                if (depth <= 0f) { ao[idx] = 0f; continue }

                var occluded = 0; var total = 0
                for (dj in -radius..radius) {
                    for (di in -radius..radius) {
                        if (di == 0 && dj == 0) continue
                        val ni = i + di; val nj = j + dj
                        if (ni < 0 || ni >= bW || nj < 0 || nj >= bH) continue
                        val nDepth = depthGrid[nj * bW + ni]
                        if (nDepth <= 0f) continue
                        total++
                        // Neighbour closer to camera → potential occluder
                        if (depth - nDepth > OCCLUDE_THRESH) occluded++
                    }
                }
                ao[idx] = if (total > 0) max(0f, 1f - occluded.toFloat() / total) else 1f
            }
        }
        return ao
    }
}
