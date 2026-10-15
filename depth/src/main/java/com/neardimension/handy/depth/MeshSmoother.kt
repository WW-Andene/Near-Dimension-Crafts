package com.arhand.depth

import kotlin.math.sqrt

/**
 * B5 — Post-processing: Laplacian mesh smoothing.
 *
 * Marching cubes output is blocky at cell boundaries due to the discrete grid.
 * One pass of umbrella Laplacian smoothing (λ = 0.5, 3 iterations) removes
 * staircase artifacts with minimal shape distortion. Operates on the triangle
 * soup output from MarchingCubes before GLB export.
 *
 * Algorithm: Umbrella Laplacian (Taubin, "A Signal Processing Approach to Fair
 * Surface Design", SIGGRAPH 1995). Each vertex is moved toward the mean of its
 * immediate neighbours weighted uniformly (umbrella operator).
 *
 * Welding:
 * The raw marching cubes output is unindexed triangle soup — each triangle has
 * 3 unique vertex entries even when they share a position with adjacent triangles.
 * Before smoothing we weld coincident vertices (within tolerance 1e-4f) to build
 * a proper adjacency graph, smooth on the indexed mesh, then unpack back to the
 * flat triangle soup format expected by the rest of the pipeline.
 *
 * Complexity: O(V × iterations) where V ≈ N_triangles × 0.5 after welding.
 * At GRID_N=48 this is ~5k–15k vertices — well within the ~50ms budget for a
 * one-time post-scan operation.
 */
object MeshSmoother {

    /**
     * Apply [iterations] passes of umbrella Laplacian smoothing with weight [lambda]
     * to the flat triangle soup [positions] (stride 9: x0,y0,z0, x1,y1,z1, x2,y2,z2).
     *
     * @param positions  Flat FloatArray from MarchingCubes.extract() — length must be % 9 == 0
     * @param iterations Number of smoothing passes. 3 is sufficient for staircase removal.
     * @param lambda     Move fraction toward neighbourhood mean. 0.5 is standard.
     * @return  Smoothed flat triangle soup in the same format as the input.
     */
    fun smooth(
        positions: FloatArray,
        iterations: Int = 3,
        lambda: Float = 0.5f
    ): FloatArray {
        if (positions.size < 9 || positions.size % 9 != 0) return positions

        val vertCount = positions.size / 3
        val triCount  = positions.size / 9

        // ── Step 1: Weld vertices by quantised position ──────────────────────
        // Quantise to 1e-4 world units (well below cell size at both 32³ and 48³)
        val QUANT = 10000f
        data class Key(val x: Int, val y: Int, val z: Int)

        val keyToIdx = HashMap<Key, Int>(vertCount)
        val weldedPos = ArrayList<Float>(vertCount * 3 / 2)  // rough estimate after welding
        val origToWelded = IntArray(vertCount)

        for (v in 0 until vertCount) {
            val px = positions[v * 3]
            val py = positions[v * 3 + 1]
            val pz = positions[v * 3 + 2]
            val key = Key(
                (px * QUANT).toInt(),
                (py * QUANT).toInt(),
                (pz * QUANT).toInt()
            )
            val existing = keyToIdx[key]
            if (existing != null) {
                origToWelded[v] = existing
            } else {
                val newIdx = weldedPos.size / 3
                keyToIdx[key] = newIdx
                origToWelded[v] = newIdx
                weldedPos.add(px); weldedPos.add(py); weldedPos.add(pz)
            }
        }

        val W = weldedPos.size / 3  // welded vertex count
        val vx = FloatArray(W) { weldedPos[it * 3] }
        val vy = FloatArray(W) { weldedPos[it * 3 + 1] }
        val vz = FloatArray(W) { weldedPos[it * 3 + 2] }

        // ── Step 2: Build adjacency lists ────────────────────────────────────
        // neighbours[i] = set of welded vertex indices adjacent to vertex i
        val neighbours = Array(W) { HashSet<Int>() }
        for (t in 0 until triCount) {
            val base = t * 3  // in origToWelded index space (3 verts per tri)
            val i0 = origToWelded[base]
            val i1 = origToWelded[base + 1]
            val i2 = origToWelded[base + 2]
            neighbours[i0].add(i1); neighbours[i0].add(i2)
            neighbours[i1].add(i0); neighbours[i1].add(i2)
            neighbours[i2].add(i0); neighbours[i2].add(i1)
        }

        // ── Step 3: Laplacian smoothing passes ───────────────────────────────
        val nx = vx.copyOf()
        val ny = vy.copyOf()
        val nz = vz.copyOf()

        repeat(iterations) {
            // Jacobi: read from vx/vy/vz (old positions), write into nx/ny/nz (new positions).
            // This ensures every vertex in a pass uses only values from the *previous* iteration,
            // not already-updated neighbours from the current pass (which would be Gauss-Seidel).
            for (i in 0 until W) {
                val nbrs = neighbours[i]
                if (nbrs.isEmpty()) {
                    nx[i] = vx[i]; ny[i] = vy[i]; nz[i] = vz[i]
                    continue
                }
                var sx = 0f; var sy = 0f; var sz = 0f
                for (j in nbrs) { sx += vx[j]; sy += vy[j]; sz += vz[j] }
                val inv = 1f / nbrs.size
                nx[i] = vx[i] + lambda * (sx * inv - vx[i])
                ny[i] = vy[i] + lambda * (sy * inv - vy[i])
                nz[i] = vz[i] + lambda * (sz * inv - vz[i])
            }
            // Swap: new positions become the old positions for the next iteration
            nx.copyInto(vx); ny.copyInto(vy); nz.copyInto(vz)
        }

        // ── Step 4: Unpack back to flat triangle soup ─────────────────────────
        val out = FloatArray(positions.size)
        for (v in 0 until vertCount) {
            val wi = origToWelded[v]
            out[v * 3]     = vx[wi]
            out[v * 3 + 1] = vy[wi]
            out[v * 3 + 2] = vz[wi]
        }
        return out
    }
}
