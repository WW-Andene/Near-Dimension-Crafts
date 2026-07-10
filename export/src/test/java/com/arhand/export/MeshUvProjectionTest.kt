package com.arhand.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for ENGINE_ARCHITECTURE.md §4.1: GLBExporter and TextureBaker used to
 * compute UVs independently and disagreed for any mesh not centred at the world origin.
 * Both now call [MeshUvProjection] directly, so testing it here is sufficient to guarantee
 * they agree — there is only one formula left to get wrong.
 */
class MeshUvProjectionTest {

    @Test
    fun `off-origin mesh centres UV on the AABB centre, not the world origin`() {
        // AABB spans x:[10,14], z:[10,14], centred at (12, 12) — far from the world origin.
        // A point directly in front of that centre along +Z must project to u = 0.5
        // regardless of its absolute distance from the origin. The old TextureBaker formula
        // used raw (uncentred) x/z, so it would have computed atan2(14, 14) here instead of
        // atan2(0, 2) — a completely different, wrong result for this exact off-origin case.
        val uv = MeshUvProjection.project(x = 12f, y = 1f, z = 14f, cx = 12f, cz = 12f, minY = 0f, yRange = 2f)
        assertEquals(0.5f, uv[0], 1e-5f)
    }

    @Test
    fun `v is linear from minY to maxY, not inverted`() {
        val uvAtMinY = MeshUvProjection.project(x = 0f, y = 0f, z = 1f, cx = 0f, cz = 0f, minY = 0f, yRange = 4f)
        val uvAtMaxY = MeshUvProjection.project(x = 0f, y = 4f, z = 1f, cx = 0f, cz = 0f, minY = 0f, yRange = 4f)
        // The old TextureBaker formula computed v = 1 - (y - minY) / height (inverted);
        // GLBExporter's (the decided canonical formula) does not invert.
        assertEquals(0f, uvAtMinY[1], 1e-5f)
        assertEquals(1f, uvAtMaxY[1], 1e-5f)
    }

    @Test
    fun `u wraps to the 0-1 seam on the negative-Z side of the AABB centre`() {
        val uv = MeshUvProjection.project(x = 0f, y = 1f, z = -1f, cx = 0f, cz = 0f, minY = 0f, yRange = 2f)
        assertEquals(1.0f, uv[0], 1e-5f)
    }

    @Test
    fun `v is clamped to 0-1 even if y falls outside the AABB`() {
        val below = MeshUvProjection.project(x = 0f, y = -5f, z = 1f, cx = 0f, cz = 0f, minY = 0f, yRange = 4f)
        val above = MeshUvProjection.project(x = 0f, y = 50f, z = 1f, cx = 0f, cz = 0f, minY = 0f, yRange = 4f)
        assertEquals(0f, below[1], 1e-5f)
        assertEquals(1f, above[1], 1e-5f)
    }
}
