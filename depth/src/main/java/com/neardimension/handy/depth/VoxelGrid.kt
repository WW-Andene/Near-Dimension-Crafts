package com.arhand.depth

/**
 * VoxelGrid — sparse voxel room map.
 *
 * Accumulates 3D metric point-cloud data into a sparse voxel hash map keyed by
 * quantised (x, y, z) cell coordinates. Each voxel stores the highest confidence
 * value seen from any source. Periodically flushes the lowest-confidence half to
 * bound memory usage.
 *
 * The grid is designed to accompany [SlamLite]: SLAM supplies camera pose deltas,
 * and VoxelGrid accumulates world-space points across camera positions.
 *
 * ## Point format
 *
 * Input arrays use the same stride-4 layout as [com.arhand.util.PointCloudStore]:
 *   [x, y, z, confidence]  (metric, camera space or world space after transform)
 *
 * ## Export
 *
 * [exportAsArray] returns a flat FloatArray of (x, y, z, confidence) tuples for
 * rendering via [com.arhand.render.DepthCloudRenderer] or similar.
 */
class VoxelGrid(
    val cellSize:   Float = 0.005f,   // voxel cell edge length (metres) — 5 mm default
    val maxVoxels:  Int   = 200_000
) {

    // voxel key → best confidence at that cell
    private val voxelMap  = LinkedHashMap<Long, Float>(maxVoxels, 0.75f, false)
    // voxel key → [x, y, z] centre position (metric)
    private val voxelPos  = LinkedHashMap<Long, FloatArray>(maxVoxels, 0.75f, false)

    private var flushCounter = 0

    /** Current number of occupied voxels. */
    val size: Int get() = voxelMap.size

    /**
     * Accumulate a stride-4 point batch (x, y, z, confidence) into the grid.
     * Len must be a multiple of 4.
     */
    fun update(points: FloatArray, len: Int) {
        val n = len / 4
        for (i in 0 until n) {
            val x = points[i * 4];     val y = points[i * 4 + 1]
            val z = points[i * 4 + 2]; val c = points[i * 4 + 3]
            if (c <= 0f) continue

            val key = voxelKey(x, y, z)
            val existing = voxelMap[key]
            if (existing == null || c > existing) {
                voxelMap[key] = c
                voxelPos[key] = floatArrayOf(
                    kotlin.math.round(x / cellSize) * cellSize,
                    kotlin.math.round(y / cellSize) * cellSize,
                    kotlin.math.round(z / cellSize) * cellSize
                )
            }
        }

        if (++flushCounter > 60) {
            flushCounter = 0
            flushLow()
        }
    }

    /**
     * Export all occupied voxels as a flat stride-4 FloatArray (x, y, z, confidence).
     * Safe to call from any thread; takes a snapshot of current map state.
     */
    fun exportAsArray(): FloatArray {
        val snapshot = ArrayList<Map.Entry<Long, Float>>(voxelMap.size)
        snapshot.addAll(voxelMap.entries)

        val out = FloatArray(snapshot.size * 4)
        var i   = 0
        for (entry in snapshot) {
            val pos = voxelPos[entry.key] ?: continue
            out[i++] = pos[0]; out[i++] = pos[1]
            out[i++] = pos[2]; out[i++] = entry.value
        }
        return if (i == out.size) out else out.copyOf(i)
    }

    /** Remove all voxels within [radius] metres of (cx, cy, cz). */
    fun clearRegion(cx: Float, cy: Float, cz: Float, radius: Float) {
        val r2 = radius * radius
        val iter = voxelMap.iterator()
        while (iter.hasNext()) {
            val key = iter.next().key
            val pos = voxelPos[key] ?: continue
            val dx = pos[0] - cx; val dy = pos[1] - cy; val dz = pos[2] - cz
            if (dx * dx + dy * dy + dz * dz < r2) {
                iter.remove(); voxelPos.remove(key)
            }
        }
    }

    /** Clear all accumulated data. */
    fun reset() {
        voxelMap.clear(); voxelPos.clear(); flushCounter = 0
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun voxelKey(x: Float, y: Float, z: Float): Long {
        val ix = (x / cellSize).toInt() and 0x1FFFFF  // 21 bits per axis
        val iy = (y / cellSize).toInt() and 0x1FFFFF
        val iz = (z / cellSize).toInt() and 0x1FFFFF
        return ix.toLong() or (iy.toLong() shl 21) or (iz.toLong() shl 42)
    }

    private fun flushLow() {
        if (voxelMap.size <= maxVoxels) return
        val threshold = voxelMap.values.sorted().getOrElse(maxVoxels / 2) { 0.5f }
        val iter = voxelMap.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.value < threshold) {
                voxelPos.remove(e.key)
                iter.remove()
            }
        }
    }
}
