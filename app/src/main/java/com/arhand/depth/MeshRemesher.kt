package com.arhand.depth

import kotlin.math.sqrt

/**
 * HAND-7 — Mesh topology stabiliser for GLB blend shape computation.
 *
 * Problem: [MarchingCubes.extract] produces a different vertex count for each
 * SDF grid it processes. GLB blend shape morph targets require **consistent topology**
 * — all pose meshes must have the same vertex count and ordering, so that per-vertex
 * deltas `Δ[v] = pose[v] - base[v]` are meaningful.
 *
 * Solution: given a base mesh and a set of pose meshes (each with potentially
 * different topology), re-sample each pose mesh by projecting every base vertex
 * onto the nearest surface of the pose mesh. The result is a set of
 * topology-consistent delta arrays of the same length as the base vertex list.
 *
 * ## Algorithm
 *
 * For each vertex `b` in the base mesh:
 *   1. Find the nearest triangle in the pose mesh.
 *   2. Compute the closest point on that triangle to `b`.
 *   3. `delta[b] = closestPoint - b`
 *
 * Triangle search uses a spatial bucket hash for O(1) average lookup,
 * making the total complexity O(V_base × bucket_density) rather than
 * O(V_base × T_pose).
 *
 * Thread safety: all methods are stateless and safe to call concurrently.
 */
object MeshRemesher {

    /** Side length of one spatial bucket (world units). Smaller = faster lookup, more memory. */
    private const val BUCKET_SIZE = 0.005f   // 5mm — fine enough for hand geometry

    /**
     * Project [poseMesh] onto the topology of [baseMesh], producing a delta array.
     *
     * @param baseMesh  Flat float array (x,y,z × V triangles × 3). The reference topology.
     * @param poseMesh  Flat float array with any number of vertices. Source surface.
     * @return          FloatArray of the same length as [baseMesh]: for each base vertex,
     *                  the displacement vector to the nearest surface of [poseMesh].
     *                  All zeros if [poseMesh] is empty.
     */
    fun computeDelta(baseMesh: FloatArray, poseMesh: FloatArray): FloatArray {
        if (poseMesh.size < 9) return FloatArray(baseMesh.size)   // empty pose mesh

        // Build spatial bucket of pose triangles
        val buckets = buildBuckets(poseMesh)

        val delta = FloatArray(baseMesh.size)
        var vi = 0
        while (vi + 2 < baseMesh.size) {
            val bx = baseMesh[vi];  val by = baseMesh[vi + 1]; val bz = baseMesh[vi + 2]
            val (cx, cy, cz) = nearestSurfacePoint(bx, by, bz, poseMesh, buckets)
            delta[vi]     = cx - bx
            delta[vi + 1] = cy - by
            delta[vi + 2] = cz - bz
            vi += 3
        }
        return delta
    }

    /**
     * Compute deltas for multiple pose meshes against a common base mesh.
     *
     * @param baseMesh   Reference topology.
     * @param poseMeshes List of pose meshes. May be empty (FloatArray(0)) for poses that
     *                   failed to produce a mesh; those entries produce zero delta arrays.
     * @return           List of delta arrays, one per pose mesh, each the same length as baseMesh.
     */
    fun computeDeltas(baseMesh: FloatArray, poseMeshes: List<FloatArray>): List<FloatArray> =
        poseMeshes.map { if (it.isNotEmpty()) computeDelta(baseMesh, it) else FloatArray(baseMesh.size) }

    // ── Spatial bucket hash ───────────────────────────────────────────────────

    private data class BucketKey(val ix: Int, val iy: Int, val iz: Int)

    private fun bucketKey(x: Float, y: Float, z: Float): BucketKey =
        BucketKey(
            (x / BUCKET_SIZE).toInt(),
            (y / BUCKET_SIZE).toInt(),
            (z / BUCKET_SIZE).toInt()
        )

    /**
     * Build a spatial bucket map from triangle centroids → triangle index.
     * Each bucket contains the indices (into [mesh]) of triangles whose centroid
     * falls in that cell.
     */
    private fun buildBuckets(mesh: FloatArray): Map<BucketKey, MutableList<Int>> {
        val buckets = HashMap<BucketKey, MutableList<Int>>()
        var i = 0
        var triIdx = 0
        while (i + 8 < mesh.size) {
            val cx = (mesh[i] + mesh[i + 3] + mesh[i + 6]) / 3f
            val cy = (mesh[i + 1] + mesh[i + 4] + mesh[i + 7]) / 3f
            val cz = (mesh[i + 2] + mesh[i + 5] + mesh[i + 8]) / 3f
            val key = bucketKey(cx, cy, cz)
            buckets.getOrPut(key) { mutableListOf() }.add(triIdx)
            i += 9; triIdx++
        }
        return buckets
    }

    /**
     * Find the nearest surface point on [mesh] to query point (px, py, pz).
     * Searches the 3×3×3 neighbourhood of the bucket containing the query point,
     * then falls back to brute-force over all triangles if no bucket hit is found.
     */
    private fun nearestSurfacePoint(
        px: Float, py: Float, pz: Float,
        mesh: FloatArray,
        buckets: Map<BucketKey, MutableList<Int>>
    ): Triple<Float, Float, Float> {
        val qKey = bucketKey(px, py, pz)
        var bestDist = Float.MAX_VALUE
        var bestX = px; var bestY = py; var bestZ = pz

        // Search 3×3×3 neighbourhood
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val nKey = BucketKey(qKey.ix + dx, qKey.iy + dy, qKey.iz + dz)
            val tris = buckets[nKey] ?: continue
            for (triIdx in tris) {
                val base = triIdx * 9
                if (base + 8 >= mesh.size) continue
                val (cx, cy, cz, d) = closestPointOnTriangle(
                    px, py, pz,
                    mesh[base], mesh[base + 1], mesh[base + 2],
                    mesh[base + 3], mesh[base + 4], mesh[base + 5],
                    mesh[base + 6], mesh[base + 7], mesh[base + 8]
                )
                if (d < bestDist) { bestDist = d; bestX = cx; bestY = cy; bestZ = cz }
            }
        }

        // Fallback: if no nearby bucket found, brute-force the full mesh
        if (bestDist == Float.MAX_VALUE) {
            var i = 0
            while (i + 8 < mesh.size) {
                val (cx, cy, cz, d) = closestPointOnTriangle(
                    px, py, pz,
                    mesh[i], mesh[i + 1], mesh[i + 2],
                    mesh[i + 3], mesh[i + 4], mesh[i + 5],
                    mesh[i + 6], mesh[i + 7], mesh[i + 8]
                )
                if (d < bestDist) { bestDist = d; bestX = cx; bestY = cy; bestZ = cz }
                i += 9
            }
        }

        return Triple(bestX, bestY, bestZ)
    }

    /**
     * Closest point on a triangle to a query point, using barycentric coordinates.
     * Returns (closestX, closestY, closestZ, squaredDistance).
     *
     * Reference: Ericson, "Real-Time Collision Detection", Chapter 5.
     */
    private fun closestPointOnTriangle(
        px: Float, py: Float, pz: Float,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float
    ): Array<Float> {
        val abx = bx - ax; val aby = by - ay; val abz = bz - az
        val acx = cx - ax; val acy = cy - ay; val acz = cz - az
        val apx = px - ax; val apy = py - ay; val apz = pz - az

        val d1 = abx * apx + aby * apy + abz * apz
        val d2 = acx * apx + acy * apy + acz * apz
        if (d1 <= 0f && d2 <= 0f) {
            val d = dist2(px, py, pz, ax, ay, az)
            return arrayOf(ax, ay, az, d)
        }

        val bpx = px - bx; val bpy = py - by; val bpz = pz - bz
        val d3 = abx * bpx + aby * bpy + abz * bpz
        val d4 = acx * bpx + acy * bpy + acz * bpz
        if (d3 >= 0f && d4 <= d3) {
            val d = dist2(px, py, pz, bx, by, bz)
            return arrayOf(bx, by, bz, d)
        }

        val vc = d1 * d4 - d3 * d2
        if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
            val v = d1 / (d1 - d3)
            val rx = ax + v * abx; val ry = ay + v * aby; val rz = az + v * abz
            return arrayOf(rx, ry, rz, dist2(px, py, pz, rx, ry, rz))
        }

        val cpx = px - cx; val cpy = py - cy; val cpz = pz - cz
        val d5 = abx * cpx + aby * cpy + abz * cpz
        val d6 = acx * cpx + acy * cpy + acz * cpz
        if (d6 >= 0f && d5 <= d6) {
            val d = dist2(px, py, pz, cx, cy, cz)
            return arrayOf(cx, cy, cz, d)
        }

        val vb = d5 * d2 - d1 * d6
        if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
            val w = d2 / (d2 - d6)
            val rx = ax + w * acx; val ry = ay + w * acy; val rz = az + w * acz
            return arrayOf(rx, ry, rz, dist2(px, py, pz, rx, ry, rz))
        }

        val va = d3 * d6 - d5 * d4
        if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) {
            val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
            val rx = bx + w * (cx - bx); val ry = by + w * (cy - by); val rz = bz + w * (cz - bz)
            return arrayOf(rx, ry, rz, dist2(px, py, pz, rx, ry, rz))
        }

        val denom = 1f / (va + vb + vc)
        val v = vb * denom; val w = vc * denom
        val rx = ax + v * abx + w * acx
        val ry = ay + v * aby + w * acy
        val rz = az + v * abz + w * acz
        return arrayOf(rx, ry, rz, dist2(px, py, pz, rx, ry, rz))
    }

    private fun dist2(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2; val dz = z1 - z2
        return dx * dx + dy * dy + dz * dz
    }
}
