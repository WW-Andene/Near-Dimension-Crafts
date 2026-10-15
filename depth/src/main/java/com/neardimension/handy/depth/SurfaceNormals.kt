package com.arhand.depth

import kotlin.math.sqrt

/**
 * SurfaceNormals — per-block surface normal computation from a depth grid.
 *
 * Computes finite-difference depth gradients over the 8×6 block grid and
 * converts them to unit surface normals using the camera's FOV projection.
 * Normals are in camera space: +X right, +Y up, −Z into the scene.
 *
 * Algorithm per interior block (i, j):
 *   ∂z/∂x ≈ (depth[i+1,j] − depth[i-1,j]) / (2 × blockWidth_m)
 *   ∂z/∂y ≈ (depth[i,j+1] − depth[i,j-1]) / (2 × blockHeight_m)
 *   N = normalize(−∂z/∂x,  −∂z/∂y,  1)
 *
 * Border blocks use one-sided differences.
 */
class SurfaceNormals {

    companion object {
        // Approximate angular block size (radians) for a ~60° HFOV 8-block grid
        private const val BLOCK_ANGLE_X = (60f * Math.PI / 180f / 8f).toFloat()
        private const val BLOCK_ANGLE_Y = (45f * Math.PI / 180f / 6f).toFloat()
    }

    /**
     * Per-block normal map.
     *
     * @param nx   X component of the unit normal, length [bW × bH]
     * @param ny   Y component
     * @param nz   Z component (negative into scene)
     * @param bW   Grid width
     * @param bH   Grid height
     */
    data class NormalMap(
        val nx: FloatArray,
        val ny: FloatArray,
        val nz: FloatArray,
        val bW: Int,
        val bH: Int
    )

    /**
     * Compute surface normals from [depthGrid] (row-major [bW]×[bH]).
     *
     * @param depthGrid Normalised depth values [0–1] or metric (any unit; only gradients matter)
     * @param bW        Grid width
     * @param bH        Grid height
     * @return          [NormalMap] with unit normals at each block
     */
    fun compute(depthGrid: FloatArray, bW: Int, bH: Int): NormalMap {
        val nx = FloatArray(bW * bH)
        val ny = FloatArray(bW * bH)
        val nz = FloatArray(bW * bH)

        for (j in 0 until bH) {
            for (i in 0 until bW) {
                val idx = j * bW + i

                val zL  = if (i > 0)      depthGrid[j * bW + (i - 1)] else depthGrid[idx]
                val zR  = if (i < bW - 1) depthGrid[j * bW + (i + 1)] else depthGrid[idx]
                val zU  = if (j > 0)      depthGrid[(j - 1) * bW + i] else depthGrid[idx]
                val zD  = if (j < bH - 1) depthGrid[(j + 1) * bW + i] else depthGrid[idx]

                val stepX = if (i > 0 && i < bW - 1) 2f * BLOCK_ANGLE_X else BLOCK_ANGLE_X
                val stepY = if (j > 0 && j < bH - 1) 2f * BLOCK_ANGLE_Y else BLOCK_ANGLE_Y

                val dzdx = (zR - zL) / stepX
                val dzdy = (zD - zU) / stepY

                val ux = -dzdx; val uy = -dzdy; val uz = 1f
                val len = sqrt(ux * ux + uy * uy + uz * uz)
                if (len > 0f) {
                    nx[idx] = ux / len; ny[idx] = uy / len; nz[idx] = uz / len
                } else {
                    nx[idx] = 0f; ny[idx] = 0f; nz[idx] = 1f
                }
            }
        }
        return NormalMap(nx, ny, nz, bW, bH)
    }
}
