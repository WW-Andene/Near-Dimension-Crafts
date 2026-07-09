package com.arhand.depth

import com.arhand.util.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * HAND-8 — Truncated Signed Distance Function (TSDF) voxel volume.
 *
 * Replaces [com.arhand.util.PointCloudStore]'s raw point accumulation with a
 * surface-aware volumetric representation. Each voxel stores a weighted average
 * of signed depth values — negative inside the hand, positive outside —
 * which can be meshed at any resolution via marching cubes.
 *
 * ## Advantages over raw point clouds
 *
 * - **Naturally smooth**: the weighted-average SDF is inherently smooth across voxels
 *   without requiring a separate `MeshSmoother` pass.
 * - **Correct occlusion**: later frames occlude earlier frames because the signed
 *   distance function represents the _surface_, not a set of points. A new depth
 *   reading behind the surface correctly pushes it forward.
 * - **Directly meshable**: `extractMesh()` runs marching cubes directly on the TSDF,
 *   bypassing the `DepthApiCarver` shell-SDF step.
 * - **Memory efficient**: fixed 48³ grid (110 592 voxels × 8 bytes = ~864 KB)
 *   regardless of frame count.
 *
 * ## KinectFusion-style integration
 *
 * For each depth point (wx, wy, wz) with confidence c:
 *   1. Find the corresponding voxel.
 *   2. Compute the signed distance to the voxel centre along the ray direction:
 *      `sdf = depth_to_voxel - depth_to_surface`  (negative inside, positive outside).
 *   3. Truncate: if `|sdf| > T`, skip (too far from surface to be reliable).
 *   4. Running weighted average: `F_new = (F_old × W_old + sdf × c) / (W_old + c)`.
 *
 * ## Usage
 *
 * ```kotlin
 * val tsdf = TSDFVolume()
 * tsdf.integrate(depthPoints, confidence = 0.8f)  // call each frame
 * val mesh = tsdf.extractMesh()                   // run marching cubes on the SDF
 * val cloud = tsdf.extractSurfacePoints()          // for PointCloudRenderer display
 * tsdf.reset()                                     // clear for new scan
 * ```
 *
 * Thread safety: not thread-safe. All calls must come from the same thread
 * (the depth source callback thread or a dedicated integration coroutine).
 */
class TSDFVolume(
    /** Grid side length (voxels). Volume is N³. Default = DepthCarver's effectiveGridN. */
    private val N: Int = DepthCarver.effectiveGridN,
    /** Physical grid half-extent (metres). Voxel size = GRID_SIZE × 2 / N. */
    private val gridHalfSize: Float = DepthCarver.GRID_SIZE
) {
    companion object {
        /** Truncation distance in multiples of voxel size. */
        const val TRUNCATION_CELLS = 2.5f
        /** Maximum accumulated weight per voxel (prevents old data from dominating). */
        const val MAX_WEIGHT = 20f
    }

    private val cellSize: Float = gridHalfSize * 2f / N
    private val truncDist: Float = cellSize * TRUNCATION_CELLS

    // TSDF and weight grids — allocated once, reset per scan
    private val tsdf:   FloatArray = FloatArray(N * N * N) { truncDist }
    private val weight: FloatArray = FloatArray(N * N * N) { 0f }

    // Grid origin: set on first integrate() call from point cloud centroid
    private var originX: Float = 0f; private var originY: Float = 0f; private var originZ: Float = 0f
    private var originSet: Boolean = false

    /** Number of frames integrated so far. */
    var frameCount: Int = 0
        private set

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Integrate a list of world-space depth points into the TSDF.
     *
     * @param points     List of [Vec3] surface points in world space.
     * @param confidence Per-batch confidence weight (0–1). Higher = more trust in this frame.
     */
    fun integrate(points: List<Vec3>, confidence: Float) {
        if (points.isEmpty()) return

        // Set grid origin from centroid on first call
        if (!originSet) {
            var cx = 0f; var cy = 0f; var cz = 0f
            for (p in points) { cx += p.x; cy += p.y; cz += p.z }
            cx /= points.size; cy /= points.size; cz /= points.size
            originX = cx - gridHalfSize
            originY = cy - gridHalfSize
            originZ = cz - gridHalfSize
            originSet = true
        }

        // For each incoming depth point, update the voxels along the ray
        for (p in points) {
            integratePoint(p.x, p.y, p.z, confidence)
        }
        frameCount++
    }

    /**
     * Extract the current TSDF surface as a triangle mesh via marching cubes.
     *
     * Returns an empty array if fewer than 2 frames have been integrated.
     */
    fun extractMesh(): FloatArray {
        if (frameCount < 2) return FloatArray(0)
        val origin = floatArrayOf(originX, originY, originZ)
        return MarchingCubes.extract(tsdf, N, cellSize, origin)
    }

    /**
     * Sample surface points from the TSDF zero-crossing for use by
     * [com.arhand.render.PointCloudRenderer] (visual display only).
     *
     * Returns at most [maxPoints] points sampled at [stride] voxel intervals.
     */
    fun extractSurfacePoints(maxPoints: Int = 4000, stride: Int = 2): List<Vec3> {
        if (frameCount < 1) return emptyList()
        val result = mutableListOf<Vec3>()
        for (zi in 0 until N step stride) {
            for (yi in 0 until N step stride) {
                for (xi in 0 until N step stride) {
                    val sdfVal = tsdf[voxelIdx(xi, yi, zi)]
                    // Near-surface: |sdf| < one voxel, has accumulated weight
                    if (abs(sdfVal) < cellSize && weight[voxelIdx(xi, yi, zi)] > 0.5f) {
                        val wx = originX + (xi + 0.5f) * cellSize
                        val wy = originY + (yi + 0.5f) * cellSize
                        val wz = originZ + (zi + 0.5f) * cellSize
                        result.add(Vec3(wx, wy, wz))
                        if (result.size >= maxPoints) return result
                    }
                }
            }
        }
        return result
    }

    /**
     * Reset all voxels to the initial truncated distance and zero weight.
     * Call at the start of each new scan.
     */
    fun reset() {
        tsdf.fill(truncDist)
        weight.fill(0f)
        originSet  = false
        frameCount = 0
    }

    // ── Integration ───────────────────────────────────────────────────────────

    private fun integratePoint(wx: Float, wy: Float, wz: Float, conf: Float) {
        if (!originSet) return

        // Voxel containing this surface point
        val xi = ((wx - originX) / cellSize).toInt()
        val yi = ((wy - originY) / cellSize).toInt()
        val zi = ((wz - originZ) / cellSize).toInt()

        // Update a small neighbourhood around the surface point
        val r = 1   // integration radius in voxels
        for (dz in -r..r) for (dy in -r..r) for (dx in -r..r) {
            val vx = xi + dx; val vy = yi + dy; val vz = zi + dz
            if (vx < 0 || vx >= N || vy < 0 || vy >= N || vz < 0 || vz >= N) continue

            val voxCx = originX + (vx + 0.5f) * cellSize
            val voxCy = originY + (vy + 0.5f) * cellSize
            val voxCz = originZ + (vz + 0.5f) * cellSize

            // Signed distance: positive outside (voxel farther from camera than surface)
            // negative inside (voxel closer to camera than surface).
            // Approximated as signed distance along Z axis (camera looks at -Z).
            val signedDist = wz - voxCz   // positive = voxel behind surface

            if (abs(signedDist) > truncDist) continue   // outside truncation band

            val idx = voxelIdx(vx, vy, vz)
            val oldW = weight[idx]
            val newW = minOf(oldW + conf, MAX_WEIGHT)
            tsdf[idx]   = (tsdf[idx] * oldW + signedDist * conf) / newW
            weight[idx] = newW
        }
    }

    private fun voxelIdx(x: Int, y: Int, z: Int): Int = x + N * y + N * N * z
}
