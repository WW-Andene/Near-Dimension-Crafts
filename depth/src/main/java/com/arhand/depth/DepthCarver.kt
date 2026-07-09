package com.arhand.depth

import android.os.Build
import com.arhand.tracking.HandLandmarks
import com.arhand.util.Vec3
import com.arhand.tracking.landmarkToWorld
import kotlin.math.*

/**
 * Visual Hull SDF Carver — port of SCANNER._dcCarve() from the HTML prototype.
 *
 * Builds a signed distance field from capsule volumes along each bone segment,
 * then runs Marching Cubes to extract the mesh.
 *
 * This is a proxy reconstruction — it approximates the hand as a union of
 * capsules. Real metric depth requires ARCore (Phase 2 in the master).
 *
 * B4 — Grid resolution upgrade with LOD fallback.
 * High-tier devices (Snapdragon 8 Gen 1+ / large RAM) use GRID_N = 48 (110k voxels)
 * for significantly more finger detail. Low-tier devices fall back to GRID_N = 32
 * (32k voxels) at the original quality level.
 * Performance budget: ~600ms for 48³ vs ~200ms for 32³ — both acceptable for a
 * one-time post-scan operation.
 */
object DepthCarver {

    const val GRID_N_HIGH = 48      // 48³ = 110,592 voxels (high-tier devices)
    const val GRID_N_LOW  = 32      // 32³ = 32,768 voxels (low-tier / fallback)
    const val GRID_SIZE   = 0.5f    // world-space extent of grid

    /**
     * B4 — Device performance tier check.
     * Returns true if the device is considered high-tier based on available RAM
     * and CPU core count. We avoid vendor-specific SDK checks to stay portable.
     *
     * Heuristic (validated on Xiaomi 13T / Snapdragon 8 Gen 2):
     *   - 6+ CPU cores AND
     *   - Runtime.maxMemory() >= 512 MB (typical for flagship devices)
     *
     * This is conservative — false negatives are fine (fall back to 32³), false
     * positives (running 48³ on a slow device) add ~400ms which is still acceptable.
     */
    fun isHighTierDevice(): Boolean {
        val cores = Runtime.getRuntime().availableProcessors()
        val maxMem = Runtime.getRuntime().maxMemory()
        return cores >= 6 && maxMem >= 512L * 1024 * 1024
    }

    /**
     * Effective grid N — 48 on high-tier devices, 32 elsewhere.
     * Can be overridden at runtime (e.g. from Settings) by setting [gridNOverride].
     */
    var gridNOverride: Int? = null

    val effectiveGridN: Int
        get() = gridNOverride ?: if (isHighTierDevice()) GRID_N_HIGH else GRID_N_LOW

    private val BONE_SEGMENTS = listOf(
        // Thumb
        1 to 2, 2 to 3, 3 to 4,
        // Index
        5 to 6, 6 to 7, 7 to 8,
        // Middle
        9 to 10, 10 to 11, 11 to 12,
        // Ring
        13 to 14, 14 to 15, 15 to 16,
        // Pinky
        17 to 18, 18 to 19, 19 to 20,
        // Palm
        0 to 5, 0 to 9, 0 to 13, 0 to 17,
        5 to 9, 9 to 13, 13 to 17
    )

    /**
     * B2 — Adaptive bone radius per segment type.
     * Finger phalanges taper: distal < middle < proximal < palm.
     * Ratios match HandMeshBuilder.FINGER_PROFILES.
     */
    private enum class SegmentType { PROXIMAL, MIDDLE, DISTAL, PALM }

    private val SEGMENT_TYPES = listOf(
        // Thumb (no middle phalanx anatomically, treat as proximal/distal)
        SegmentType.PROXIMAL, SegmentType.MIDDLE, SegmentType.DISTAL,
        // Index
        SegmentType.PROXIMAL, SegmentType.MIDDLE, SegmentType.DISTAL,
        // Middle
        SegmentType.PROXIMAL, SegmentType.MIDDLE, SegmentType.DISTAL,
        // Ring
        SegmentType.PROXIMAL, SegmentType.MIDDLE, SegmentType.DISTAL,
        // Pinky
        SegmentType.PROXIMAL, SegmentType.MIDDLE, SegmentType.DISTAL,
        // Palm cross-connections
        SegmentType.PALM, SegmentType.PALM, SegmentType.PALM, SegmentType.PALM,
        SegmentType.PALM, SegmentType.PALM, SegmentType.PALM
    )

    private fun segmentRadius(boneR: Float, type: SegmentType): Float = boneR * when (type) {
        SegmentType.PROXIMAL -> 1.0f
        SegmentType.MIDDLE   -> 0.85f
        SegmentType.DISTAL   -> 0.65f
        SegmentType.PALM     -> 1.2f
    }

    /**
     * Carve SDF from multiple accumulated landmark frames.
     *
     * B1 — Quality-weighted SDF accumulation:
     * Filters frames to the top 75% by quality score (discarding low-quality outliers),
     * then takes the minimum SDF across that top quartile. This prevents a single bad
     * frame (landmark outlier, poor pose) from carving away valid geometry.
     *
     * B4 — Uses [effectiveGridN] (48 on high-tier, 32 on low-tier).
     *
     * @param frames List of (world-space landmarks, quality score) pairs captured during scan
     * @return Marching cubes mesh as flat position array
     */
    /**
     * Build SDF from [frames] and extract a mesh.
     *
     * IMP-R4: accepts optional [shapeParams] from [ManoShapeFitter]. When provided,
     * per-segment capsule radii are multiplied by [ManoShapeParams.segmentRadiusScale],
     * reflecting the user's actual finger thickness rather than population averages.
     * Falls back to the standard `boneR * 0.14f` base radius when null.
     */
    fun carveAndExtract(
        frames: List<Pair<List<Vec3>, Float>>,
        shapeParams: ManoShapeFitter.ShapeParams? = null
    ): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)

        // B1: filter to top 75% quality, minimum 1 frame
        val qualityThreshold = 0.55f
        val qualified = frames.filter { it.second >= qualityThreshold }
            .sortedByDescending { it.second }
        val topFrames: List<List<Vec3>> = if (qualified.isEmpty()) {
            // Fallback: use all frames sorted by quality descending
            frames.sortedByDescending { it.second }
                .take(maxOf(1, frames.size * 3 / 4))
                .map { it.first }
        } else {
            qualified.take(maxOf(1, qualified.size * 3 / 4)).map { it.first }
        }

        // B4: choose grid resolution based on device tier
        val N = effectiveGridN
        val cellSize = GRID_SIZE * 2f / N

        var cx = 0f; var cy = 0f; var cz = 0f; var count = 0
        for (lms in topFrames) {
            for (v in lms) { cx += v.x; cy += v.y; cz += v.z; count++ }
        }
        if (count > 0) { cx /= count; cy /= count; cz /= count }

        val origin = floatArrayOf(cx - GRID_SIZE, cy - GRID_SIZE, cz - GRID_SIZE)

        val sdf = FloatArray(N * N * N) { GRID_SIZE * 2f }

        fun idx(x: Int, y: Int, z: Int) = x + N * y + N * N * z

        // Capsule SDF: signed distance to segment (A,B) with radius r
        fun capsuleSdf(px: Float, py: Float, pz: Float,
                       ax: Float, ay: Float, az: Float,
                       bx: Float, by: Float, bz: Float,
                       r: Float): Float {
            val abx = bx - ax; val aby = by - ay; val abz = bz - az
            val apx = px - ax; val apy = py - ay; val apz = pz - az
            val t = ((apx * abx + apy * aby + apz * abz) /
                     (abx * abx + aby * aby + abz * abz + 1e-8f)).coerceIn(0f, 1f)
            val qx = ax + t * abx; val qy = ay + t * aby; val qz = az + t * abz
            val dx = px - qx; val dy = py - qy; val dz = pz - qz
            return sqrt(dx * dx + dy * dy + dz * dz) - r
        }

        // Estimate bone radius from palm size across top-quality frames
        val avgPalmSize = topFrames.map { lms ->
            if (lms.size < 10) 0.05f
            else (lms[0] - lms[9]).length()
        }.average().toFloat().coerceAtLeast(0.02f)
        val boneR = avgPalmSize * 0.14f

        // For each voxel, compute minimum capsule SDF across top-quality frames and segments
        for (zi in 0 until N) {
            for (yi in 0 until N) {
                for (xi in 0 until N) {
                    val px = origin[0] + (xi + 0.5f) * cellSize
                    val py = origin[1] + (yi + 0.5f) * cellSize
                    val pz = origin[2] + (zi + 0.5f) * cellSize

                    var minD = GRID_SIZE * 2f
                    for (lms in topFrames) {
                        if (lms.size < 21) continue
                        for (segIdx in BONE_SEGMENTS.indices) {
                            val (ai, bi) = BONE_SEGMENTS[segIdx]
                            val a = lms[ai]; val b = lms[bi]
                            // B2: per-segment tapered radius; IMP-R4: scaled by ManoShapeFitter if available
                            val baseR = segmentRadius(boneR, SEGMENT_TYPES[segIdx])
                            val r = if (shapeParams != null && segIdx < shapeParams.segmentRadiusScale.size)
                                baseR * shapeParams.segmentRadiusScale[segIdx]
                            else baseR
                            val d = capsuleSdf(px, py, pz, a.x, a.y, a.z, b.x, b.y, b.z, r)
                            if (d < minD) minD = d
                        }
                    }
                    sdf[idx(xi, yi, zi)] = minD
                }
            }
        }

        return MarchingCubes.extract(sdf, N, cellSize, origin)
    }

    /**
     * Convert HandLandmarks to world-space Vec3 list.
     */
    fun landmarksToWorld(lms: HandLandmarks, aspect: Float, mirrorX: Boolean = false): List<Vec3> =
        lms.map { lm ->
            val (x, y, z) = landmarkToWorld(lm, aspect, mirrorX = mirrorX)
            Vec3(x, y, z)
        }
}
