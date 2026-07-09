package com.arhand.depth

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * PlaneFitter — RANSAC floor / wall / ceiling detection.
 *
 * Converts the depth grid into a 3D point cloud (camera-space), then applies
 * RANSAC to find up to [MAX_PLANES] dominant planes. Each plane is labelled
 * FLOOR, WALL, or CEILING based on its normal direction.
 *
 * ## Coordinate system
 *
 * Camera space: +X right, +Y up, −Z into the scene. Depth values are treated
 * as metric when [metresToDepthUnit] = 1.0, or normalised when < 1.0.
 *
 * ## RANSAC
 *
 * Each iteration samples 3 non-collinear depth points, fits a plane, and counts
 * inliers within [INLIER_THRESH]. The best plane (most inliers) is accepted when
 * it exceeds [MIN_INLIER_RATIO]. Inliers are masked before fitting the next plane.
 */
class PlaneFitter {

    companion object {
        private const val RANSAC_ITERS     = 80     // iterations per plane search
        private const val INLIER_THRESH    = 0.04f  // max distance to plane (depth units)
        private const val MIN_INLIER_RATIO = 0.15f  // minimum inlier fraction to accept a plane
        private const val MAX_PLANES       = 3
        private const val COLLINEAR_EPS    = 1e-4f  // cross-product length threshold for collinearity

        // Colours for visualisation (ARGB)
        private val FLOOR_COLOR   = Color.argb(180, 100, 200, 100)   // green
        private val WALL_COLOR    = Color.argb(180, 100, 150, 255)   // blue
        private val CEILING_COLOR = Color.argb(180, 255, 200, 80)    // yellow

        private val rng = Random(42L)
    }

    /**
     * A plane detected in the depth grid.
     *
     * @param nx          X component of the unit normal (camera space)
     * @param ny          Y component
     * @param nz          Z component
     * @param d           Plane equation constant: dot(N, P) + d = 0
     * @param label       "FLOOR" | "WALL" | "CEILING"
     * @param color       ARGB colour for overlay rendering
     * @param inlierCount Number of depth blocks that lie on this plane
     */
    data class DetectedPlane(
        val nx: Float, val ny: Float, val nz: Float, val d: Float,
        val label: String,
        val color: Int,
        val inlierCount: Int
    )

    /**
     * Fit planes in [depthGrid] ([bW]×[bH], row-major).
     *
     * @param depthGrid             Depth values (0 = invalid)
     * @param bW                    Grid width
     * @param bH                    Grid height
     * @param fovXDeg               Horizontal FOV in degrees (used to project blocks to 3D)
     * @param fovYDeg               Vertical FOV in degrees
     * @param metresToDepthUnit     Conversion factor; 1.0 if depth is already metric
     * @return                      Up to [MAX_PLANES] detected planes, largest first
     */
    fun fit(
        depthGrid: FloatArray,
        bW: Int, bH: Int,
        fovXDeg: Float = 60f,
        fovYDeg: Float = 45f,
        metresToDepthUnit: Float = 1f
    ): List<DetectedPlane> {
        // Project blocks to 3D points (camera space)
        val pts = mutableListOf<FloatArray>()  // each entry: [x, y, z]
        val tanHX = Math.tan(fovXDeg / 2.0 * Math.PI / 180.0).toFloat()
        val tanHY = Math.tan(fovYDeg / 2.0 * Math.PI / 180.0).toFloat()

        for (j in 0 until bH) {
            for (i in 0 until bW) {
                val z = depthGrid[j * bW + i] * metresToDepthUnit
                if (z <= 0f) continue
                val fx = ((i + 0.5f) / bW - 0.5f) * 2f * tanHX
                val fy = (0.5f - (j + 0.5f) / bH) * 2f * tanHY
                pts.add(floatArrayOf(fx * z, fy * z, -z))
            }
        }

        if (pts.size < 3) return emptyList()

        val planes   = mutableListOf<DetectedPlane>()
        val inlierMask = BooleanArray(pts.size) { false }  // true = already used by a prior plane

        repeat(MAX_PLANES) {
            val candidates = pts.indices.filter { !inlierMask[it] }
            if (candidates.size < 3) return@repeat

            var bestNx = 0f; var bestNy = 0f; var bestNz = 0f; var bestD = 0f
            var bestInliers = emptyList<Int>()

            repeat(RANSAC_ITERS) {
                val i0 = candidates[rng.nextInt(candidates.size)]
                val i1 = candidates[rng.nextInt(candidates.size)]
                val i2 = candidates[rng.nextInt(candidates.size)]
                if (i0 == i1 || i1 == i2) return@repeat

                val p0 = pts[i0]; val p1 = pts[i1]; val p2 = pts[i2]
                // Plane normal = (p1-p0) × (p2-p0)
                val ex = p1[0] - p0[0]; val ey = p1[1] - p0[1]; val ez = p1[2] - p0[2]
                val fx = p2[0] - p0[0]; val fy = p2[1] - p0[1]; val fz = p2[2] - p0[2]
                val cx = ey * fz - ez * fy; val cy = ez * fx - ex * fz; val cz = ex * fy - ey * fx
                val cLen = sqrt(cx * cx + cy * cy + cz * cz)
                if (cLen < COLLINEAR_EPS) return@repeat

                val nx = cx / cLen; val ny = cy / cLen; val nz = cz / cLen
                val d  = -(nx * p0[0] + ny * p0[1] + nz * p0[2])

                val inliers = candidates.filter { idx ->
                    val p = pts[idx]
                    abs(nx * p[0] + ny * p[1] + nz * p[2] + d) < INLIER_THRESH
                }

                if (inliers.size > bestInliers.size) {
                    bestNx = nx; bestNy = ny; bestNz = nz; bestD = d
                    bestInliers = inliers
                }
            }

            val minInliers = (pts.size * MIN_INLIER_RATIO).toInt()
            if (bestInliers.size < minInliers) return@repeat

            // Mark inliers as used
            bestInliers.forEach { inlierMask[it] = true }

            val label = planeLabel(bestNx, bestNy, bestNz)
            val color = when (label) {
                "FLOOR"   -> FLOOR_COLOR
                "CEILING" -> CEILING_COLOR
                else      -> WALL_COLOR
            }
            planes.add(DetectedPlane(bestNx, bestNy, bestNz, bestD, label, color, bestInliers.size))
        }

        return planes.sortedByDescending { it.inlierCount }
    }

    private fun planeLabel(nx: Float, ny: Float, nz: Float): String {
        val ayAbs = abs(ny)
        return when {
            ayAbs > 0.7f && ny > 0f  -> "FLOOR"    // normal pointing up → floor
            ayAbs > 0.7f && ny < 0f  -> "CEILING"  // normal pointing down → ceiling
            else                      -> "WALL"
        }
    }
}
