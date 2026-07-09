package com.arhand.depth

import com.arhand.util.Vec3
import com.arhand.depth.ArCoreDepthSource
import kotlin.math.sqrt

/**
 * B6 — ARCore Depth API integration (Phase 2), steps 4-5.
 *
 * Replaces [DepthCarver]'s capsule-SDF proxy with a real metric-depth SDF built from
 * the fused world-space point cloud accumulated via [ArDepthSession].
 *
 * Approach:
 *   1. Voxel-fuse all accumulated depth points into a deduplicated cloud (avoids
 *      O(points × voxels) blowup with multi-second scans).
 *   2. For each voxel center in the carve grid, the SDF value is the signed distance
 *      to the nearest fused point, with sign determined by whether the voxel lies on
 *      the camera side (outside) or far side (inside) of the local point — approximated
 *      via a thin-shell offset (points are surface samples, so we carve a shell of
 *      thickness ~2*cellSize around them, marking voxels within the shell as "inside").
 *   3. [MarchingCubes.extract] runs unchanged on the resulting grid (B6 step 5),
 *      reusing the same [DepthCarver.GRID_SIZE] / [DepthCarver.effectiveGridN] /
 *      origin-centering conventions as the capsule-SDF path.
 *
 * If the fused cloud is too sparse (e.g. depth API unsupported mid-scan, or user moved
 * too fast), [carveAndExtract] returns an empty array so the caller can fall back to
 * [DepthCarver.carveAndExtract].
 */
object DepthApiCarver {

    /** Minimum number of fused points required to attempt a depth-based carve. */
    const val MIN_FUSED_POINTS = 500

    /** Shell half-thickness, in multiples of cellSize, around each surface point. */
    private const val SHELL_CELLS = 1.5f

    // HAND-5 — Normal map nudge constants.
    /** SDF band around the zero-crossing within which nudge is applied (in cell widths). */
    private const val NUDGE_BAND_CELLS    = 2.0f
    /** Nudge magnitude relative to cellSize. 0.15 = 15% of one voxel per unit nz. */
    private const val NORMAL_NUDGE_SCALE  = 0.15f
    /** Approximate photometric camera intrinsics (matches PhotometricDepthSource). */
    private const val PHOTO_FX = 310f
    private const val PHOTO_FY = 310f

    /**
     * B6 step 4 — fuse raw per-frame depth point clouds into a single deduplicated
     * world-space cloud via voxel-grid hashing (tolerance = [cellSize]).
     */
    fun fusePointClouds(frames: List<List<Vec3>>, cellSize: Float): List<Vec3> {
        val voxelMap = HashMap<Long, Vec3>()
        for (frame in frames) {
            for (p in frame) {
                val key = ArCoreDepthSource.voxelKey(p.x, p.y, p.z, cellSize)
                // Keep first sample per voxel — averaging across many frames is possible
                // but first-write is sufficient for SDF shell purposes and avoids
                // per-voxel running-average bookkeeping.
                voxelMap.putIfAbsent(key, p)
            }
        }
        return voxelMap.values.toList()
    }

    /**
     * B6 steps 4-5 — build a thin-shell SDF from the fused point cloud and run
     * [MarchingCubes.extract] over [DepthCarver.effectiveGridN]³ voxels.
     *
     * Grid origin/size match [DepthCarver] conventions (B3 centroid centering,
     * B4 LOD grid resolution) so downstream consumers (smoothing, GLB export,
     * point-cloud export) need no changes.
     *
     * @param depthFrames Per-frame world-space depth point clouds from [ArDepthSession.update]
     * @return Marching cubes mesh as flat position array, or empty if the fused cloud
     *         is too sparse (caller should fall back to [DepthCarver.carveAndExtract]).
     */
    /**
     * Carve a voxel SDF from accumulated depth frames and extract a triangle mesh.
     *
     * HAND-5 — [normalMap] is an optional [PhotometricNormalMap] from the photometric
     * stereo capture. When provided, surface voxels near the zero-crossing are nudged
     * along the normal direction by a fraction of their depth value, sharpening fine
     * surface detail that the voxel SDF alone cannot capture (pore-scale features,
     * wrinkle geometry). The nudge is small (NORMAL_NUDGE_SCALE × normalMap depth)
     * and conservative — it only affects voxels already within NUDGE_BAND of the surface.
     *
     * @param depthFrames  Accumulated ARCore depth point cloud frames from the scan.
     * @param normalMap    Optional photometric normal map. Null = same behaviour as before.
     */
    fun carveAndExtract(
        depthFrames: List<List<Vec3>>,
        normalMap:   com.arhand.scanner.PhotometricNormalMap? = null
    ): FloatArray {
        if (depthFrames.isEmpty()) return FloatArray(0)

        val N = DepthCarver.effectiveGridN
        val cellSize = DepthCarver.GRID_SIZE * 2f / N

        val fused = fusePointClouds(depthFrames, cellSize)
        if (fused.size < MIN_FUSED_POINTS) return FloatArray(0)

        // B3-equivalent — center grid on the fused cloud centroid.
        var cx = 0f; var cy = 0f; var cz = 0f
        for (p in fused) { cx += p.x; cy += p.y; cz += p.z }
        cx /= fused.size; cy /= fused.size; cz /= fused.size

        val origin = floatArrayOf(cx - DepthCarver.GRID_SIZE, cy - DepthCarver.GRID_SIZE, cz - DepthCarver.GRID_SIZE)

        // Spatial hash of fused points for nearest-neighbor lookup, bucketed by the
        // same voxel grid as the carve grid for O(1) average lookups.
        val buckets = HashMap<Long, MutableList<Vec3>>()
        for (p in fused) {
            val key = ArCoreDepthSource.voxelKey(p.x, p.y, p.z, cellSize)
            buckets.getOrPut(key) { mutableListOf() }.add(p)
        }

        fun nearestDist(px: Float, py: Float, pz: Float): Float {
            val qx = ((px - 0f) / cellSize).let { Math.round(it) }
            val qy = ((py - 0f) / cellSize).let { Math.round(it) }
            val qz = ((pz - 0f) / cellSize).let { Math.round(it) }
            var best = Float.MAX_VALUE
            // Search the 3x3x3 neighborhood of voxel buckets around this point.
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val key = ArCoreDepthSource.voxelKey(
                    (qx + dx) * cellSize, (qy + dy) * cellSize, (qz + dz) * cellSize,
                    cellSize
                )
                val bucket = buckets[key] ?: continue
                for (p in bucket) {
                    val ddx = px - p.x; val ddy = py - p.y; val ddz = pz - p.z
                    val d = sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
                    if (d < best) best = d
                }
            }
            return best
        }

        val shellThickness = cellSize * SHELL_CELLS
        val sdf = FloatArray(N * N * N) { DepthCarver.GRID_SIZE * 2f }

        fun idx(x: Int, y: Int, z: Int) = x + N * y + N * N * z

        for (zi in 0 until N) {
            for (yi in 0 until N) {
                for (xi in 0 until N) {
                    val px = origin[0] + (xi + 0.5f) * cellSize
                    val py = origin[1] + (yi + 0.5f) * cellSize
                    val pz = origin[2] + (zi + 0.5f) * cellSize

                    val d = nearestDist(px, py, pz)
                    // Thin-shell SDF: negative (inside) within shellThickness of a
                    // surface sample, positive (outside) beyond it.
                    sdf[idx(xi, yi, zi)] = d - shellThickness
                }
            }
        }

        // HAND-5 — Normal map surface nudge.
        //
        // The photometric normal map encodes surface detail at pixel resolution (320×240).
        // The voxel SDF grid is at a coarser resolution — fine wrinkle and crease
        // geometry is lost in the voxelisation step.
        //
        // Strategy: for each voxel within NUDGE_BAND of the zero-crossing (|sdf| < band),
        // project the voxel into the normal map's camera space, look up the normal and
        // depth offset, and nudge the SDF value in the normal direction.
        //
        // Nudge magnitude = normalMap.depth[projected pixel] × NORMAL_NUDGE_SCALE.
        // This shifts the implicit surface by a small amount corresponding to the
        // photometric depth estimate, preserving large-scale shape from ARCore while
        // adding photometric surface detail.
        if (normalMap != null && !normalMap.isEmpty) {
            val nudgeBand  = cellSize * NUDGE_BAND_CELLS
            val nmW = normalMap.width.toFloat()
            val nmH = normalMap.height.toFloat()

            for (zi in 0 until N) for (yi in 0 until N) for (xi in 0 until N) {
                val sdfVal = sdf[idx(xi, yi, zi)]
                if (kotlin.math.abs(sdfVal) > nudgeBand) continue  // skip non-surface voxels

                // World-space voxel centre
                val wx = origin[0] + (xi + 0.5f) * cellSize
                val wy = origin[1] + (yi + 0.5f) * cellSize
                val wz = origin[2] + (zi + 0.5f) * cellSize

                // Project into normal map image space (pinhole camera, principal point = centre)
                // Normal map was captured with camera at origin looking at -Z.
                // Using approximate intrinsics matching PhotometricDepthSource (FX ≈ 310, CX = 160)
                if (wz >= 0f) continue   // behind camera
                val invZ  = -1f / wz
                val nmX   = (wx * PHOTO_FX * invZ + nmW * 0.5f).toInt()
                val nmY   = (wy * PHOTO_FY * invZ + nmH * 0.5f).toInt()
                if (nmX < 0 || nmX >= normalMap.width || nmY < 0 || nmY >= normalMap.height) continue

                // Look up normal and depth at this projected pixel
                val normal = normalMap.normalAt(nmX, nmY) ?: continue
                val (nx, ny, nz) = normal

                // Depth offset from normal map: use nz (camera-facing component)
                // as a proxy for "how much does the surface face the camera here"
                // → surfaces facing away contribute less nudge
                val nudge = nz * NORMAL_NUDGE_SCALE * cellSize
                sdf[idx(xi, yi, zi)] = sdfVal - nudge
            }
        }

        return MarchingCubes.extract(sdf, N, cellSize, origin)
    }
}
