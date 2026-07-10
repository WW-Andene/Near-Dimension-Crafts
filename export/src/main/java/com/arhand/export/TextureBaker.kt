package com.arhand.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * R1 — Bake a colour texture atlas from scan pose bitmaps.
 *
 * Each scan pose produces one camera [Bitmap]. [TextureBaker] reprojects each bitmap
 * onto the mesh surface using the UV coordinates from [GLBExporter]'s cylindrical
 * projection (C2), blends all poses into a single 512×512 RGBA atlas, and returns
 * it as a PNG [ByteArray] ready for embedding in the GLB as `baseColorTexture`.
 *
 * ## Algorithm
 *
 * For each triangle in the welded mesh:
 *   1. Find the triangle's UV coordinates (cylindrical projection).
 *   2. Rasterise the triangle in UV space into the atlas.
 *   3. For each atlas texel covered, sample the corresponding pixel from each
 *      pose bitmap using the vertex position projected to camera space.
 *   4. Blend contributions: weight by cos(viewAngle) — face-on views contribute more.
 *
 * UV space (0,0) = top-left, (1,1) = bottom-right, matching glTF convention.
 *
 * ## Coordinate system
 *
 * Mesh vertices are in world space (metres). Camera space uses a simple orthographic
 * projection centred on the mesh bounding box — exact intrinsics are unavailable
 * at bake time, so the projection approximates the scan camera frame.
 *
 * Thread safety: stateless — safe to call from any thread.
 */
object TextureBaker {

    private const val ATLAS_SIZE = 512

    /**
     * R1 — Convenience entry point: bake directly from a raw triangle position array.
     *
     * Computes cylindrical UVs via the same [MeshUvProjection] formula [GLBExporter] uses
     * for the exported mesh, then delegates to [bake]. Both must agree exactly — this
     * function previously computed its own independent (uncentred, inverted-V) formula,
     * which only matched [GLBExporter]'s UVs for a mesh centred at the origin (see
     * ENGINE_ARCHITECTURE.md §4.1).
     *
     * @param triangles   Flat float array (x,y,z × 3 per triangle), same as [GLBExporter] input.
     * @param poseBitmaps One [Bitmap] per completed scan pose.
     * @return PNG-encoded 512×512 RGBA atlas, or null if baking fails.
     */
    fun bakeFromTriangles(triangles: FloatArray, poseBitmaps: List<Bitmap>): ByteArray? {
        if (triangles.size < 9 || poseBitmaps.isEmpty()) return null

        val vertexCount = triangles.size / 3

        // Compute bounding box for UV normalisation — same AABB centring GLBExporter uses.
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < triangles.size) {
            val x = triangles[i]; val y = triangles[i + 1]; val z = triangles[i + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 3
        }
        val cx = (minX + maxX) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val yRange = (maxY - minY).coerceAtLeast(1e-4f)

        // Cylindrical UV projection — identical formula to GLBExporter's, via MeshUvProjection.
        val uvCoords = FloatArray(vertexCount * 2)
        i = 0; var vi = 0
        while (i + 2 < triangles.size) {
            val x = triangles[i]; val y = triangles[i + 1]; val z = triangles[i + 2]
            val uv = MeshUvProjection.project(x, y, z, cx, cz, minY, yRange)
            uvCoords[vi * 2]     = uv[0]
            uvCoords[vi * 2 + 1] = uv[1]
            i += 3; vi++
        }

        // Build index list (sequential — no welding needed for baking)
        val indices = IntArray(vertexCount) { it }

        return bake(triangles, indices, uvCoords, poseBitmaps)
    }

    /**
     * Bake a colour atlas from [poseBitmaps] onto [weldedPositions] using [uvCoords].
     *
     * @param weldedPositions  Welded vertex positions from [GLBExporter] (x,y,z × V).
     * @param indices          Triangle index list (i0,i1,i2 × T).
     * @param uvCoords         Cylindrical UV coords from [GLBExporter] (u,v × V).
     * @param poseBitmaps      One [Bitmap] per completed scan pose (up to 8).
     * @return PNG-encoded 512×512 RGBA atlas, or null if baking fails.
     */
    fun bake(
        weldedPositions: FloatArray,
        indices:         IntArray,
        uvCoords:        FloatArray,
        poseBitmaps:     List<Bitmap>
    ): ByteArray? {
        if (poseBitmaps.isEmpty() || weldedPositions.size < 3 || indices.isEmpty()) return null

        try {
            // Create atlas and accumulation buffers
            val atlas       = Bitmap.createBitmap(ATLAS_SIZE, ATLAS_SIZE, Bitmap.Config.ARGB_8888)
            val colorAccum  = Array(ATLAS_SIZE * ATLAS_SIZE) { FloatArray(3) }   // R,G,B
            val weightAccum = FloatArray(ATLAS_SIZE * ATLAS_SIZE)

            // Compute mesh bounding box for camera-space projection
            val (minX, maxX, minY, maxY, minZ, maxZ) = meshBounds(weldedPositions)
            val cx = (minX + maxX) * 0.5f
            val cy = (minY + maxY) * 0.5f
            val range = max(maxX - minX, max(maxY - minY, maxZ - minZ)).coerceAtLeast(1e-4f)

            // For each pose bitmap, project and rasterise
            for ((poseIdx, bitmap) in poseBitmaps.withIndex()) {
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()

                // Simple orthographic projection: map world XY → bitmap UV
                // Front-facing view (looking along -Z axis) for all poses
                // This is a first-order approximation — good enough for texture seeding
                val scale = min(bw, bh) / range * 0.8f   // 80% fill factor
                val offX  = bw * 0.5f - cx * scale
                val offY  = bh * 0.5f - cy * scale

                // Rasterise each triangle into the atlas
                var i = 0
                while (i + 2 < indices.size) {
                    val i0 = indices[i]; val i1 = indices[i + 1]; val i2 = indices[i + 2]

                    // UV coordinates (scaled to atlas pixels)
                    val u0 = uvCoords[i0 * 2] * ATLAS_SIZE
                    val v0 = uvCoords[i0 * 2 + 1] * ATLAS_SIZE
                    val u1 = uvCoords[i1 * 2] * ATLAS_SIZE
                    val v1 = uvCoords[i1 * 2 + 1] * ATLAS_SIZE
                    val u2 = uvCoords[i2 * 2] * ATLAS_SIZE
                    val v2 = uvCoords[i2 * 2 + 1] * ATLAS_SIZE

                    // World positions
                    val wx0 = weldedPositions[i0 * 3];     val wy0 = weldedPositions[i0 * 3 + 1]
                    val wx1 = weldedPositions[i1 * 3];     val wy1 = weldedPositions[i1 * 3 + 1]
                    val wx2 = weldedPositions[i2 * 3];     val wy2 = weldedPositions[i2 * 3 + 1]
                    val wz0 = weldedPositions[i0 * 3 + 2]
                    val wz1 = weldedPositions[i1 * 3 + 2]
                    val wz2 = weldedPositions[i2 * 3 + 2]

                    // Triangle normal (for view-angle weighting)
                    val ex = wx1 - wx0; val ey = wy1 - wy0; val ez = wz1 - wz0
                    val fx = wx2 - wx0; val fy = wy2 - wy0; val fz = wz2 - wz0
                    val nx = ey * fz - ez * fy
                    val ny = ez * fx - ex * fz
                    val nz = ex * fy - ey * fx
                    val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-8f)
                    // cosAngle with camera look direction (0,0,-1)
                    val cosView = (-nz / nLen).coerceIn(0f, 1f)
                    if (cosView < 0.1f) { i += 3; continue }   // back-facing triangle

                    // Rasterise triangle in UV space
                    val minU = max(0, min(u0, min(u1, u2)).toInt())
                    val maxU = min(ATLAS_SIZE - 1, max(u0, max(u1, u2)).toInt())
                    val minV = max(0, min(v0, min(v1, v2)).toInt())
                    val maxV = min(ATLAS_SIZE - 1, max(v0, max(v1, v2)).toInt())

                    for (av in minV..maxV) {
                        for (au in minU..maxU) {
                            val up = au + 0.5f; val vp = av + 0.5f
                            val (l0, l1, l2) = barycentric(up, vp, u0, v0, u1, v1, u2, v2)
                                ?: continue

                            // Interpolate world position and project to bitmap
                            val wx = l0 * wx0 + l1 * wx1 + l2 * wx2
                            val wy = l0 * wy0 + l1 * wy1 + l2 * wy2
                            val bx = (wx * scale + offX).toInt().coerceIn(0, bitmap.width - 1)
                            val by = (ATLAS_SIZE - 1 - av)   // flip V for bitmap y-down

                            // Sample bitmap pixel
                            val pixel = try { bitmap.getPixel(bx, by.coerceIn(0, bitmap.height - 1)) }
                                        catch (_: Exception) { continue }

                            val atlasIdx = av * ATLAS_SIZE + au
                            colorAccum[atlasIdx][0] += Color.red(pixel)   * cosView
                            colorAccum[atlasIdx][1] += Color.green(pixel) * cosView
                            colorAccum[atlasIdx][2] += Color.blue(pixel)  * cosView
                            weightAccum[atlasIdx]   += cosView
                        }
                    }
                    i += 3
                }
            }

            // Composite: normalise by weight, fill unsampled texels with neutral grey
            for (idx in 0 until ATLAS_SIZE * ATLAS_SIZE) {
                val w = weightAccum[idx]
                val pixel = if (w > 0f) {
                    val r = (colorAccum[idx][0] / w).toInt().coerceIn(0, 255)
                    val g = (colorAccum[idx][1] / w).toInt().coerceIn(0, 255)
                    val b = (colorAccum[idx][2] / w).toInt().coerceIn(0, 255)
                    Color.rgb(r, g, b)
                } else Color.rgb(200, 185, 170)   // neutral skin-tone fill for gaps

                atlas.setPixel(idx % ATLAS_SIZE, idx / ATLAS_SIZE, pixel)
            }

            // Dilate atlas 2px to avoid seam bleeding
            val dilated = dilate(atlas, passes = 2)

            // Encode as PNG
            val out = java.io.ByteArrayOutputStream()
            dilated.compress(Bitmap.CompressFormat.PNG, 95, out)
            atlas.recycle(); dilated.recycle()
            return out.toByteArray()

        } catch (_: Exception) { return null }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class Bounds(
        val minX: Float, val maxX: Float,
        val minY: Float, val maxY: Float,
        val minZ: Float, val maxZ: Float
    )

    private operator fun Bounds.component1() = minX
    private operator fun Bounds.component2() = maxX
    private operator fun Bounds.component3() = minY
    private operator fun Bounds.component4() = maxY
    private operator fun Bounds.component5() = minZ
    private operator fun Bounds.component6() = maxZ

    private fun meshBounds(pos: FloatArray): Bounds {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < pos.size) {
            val x = pos[i]; val y = pos[i + 1]; val z = pos[i + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 3
        }
        return Bounds(minX, maxX, minY, maxY, minZ, maxZ)
    }

    /**
     * Barycentric coordinates of point (px, py) relative to triangle (u0,v0)-(u1,v1)-(u2,v2).
     * Returns null if the point is outside the triangle.
     */
    private fun barycentric(
        px: Float, py: Float,
        u0: Float, v0: Float,
        u1: Float, v1: Float,
        u2: Float, v2: Float
    ): Triple<Float, Float, Float>? {
        val denom = (v1 - v2) * (u0 - u2) + (u2 - u1) * (v0 - v2)
        if (kotlin.math.abs(denom) < 1e-8f) return null
        val l0 = ((v1 - v2) * (px - u2) + (u2 - u1) * (py - v2)) / denom
        val l1 = ((v2 - v0) * (px - u2) + (u0 - u2) * (py - v2)) / denom
        val l2 = 1f - l0 - l1
        if (l0 < 0f || l1 < 0f || l2 < 0f) return null
        return Triple(l0, l1, l2)
    }

    /**
     * Simple dilation pass: for each unsampled (grey) pixel, replace with the
     * average of its sampled neighbours. Prevents UV seam bleed artefacts.
     */
    private fun dilate(src: Bitmap, passes: Int): Bitmap {
        var current = src.copy(Bitmap.Config.ARGB_8888, true)
        val neutral = Color.rgb(200, 185, 170)
        repeat(passes) {
            val next = current.copy(Bitmap.Config.ARGB_8888, true)
            for (y in 1 until ATLAS_SIZE - 1) {
                for (x in 1 until ATLAS_SIZE - 1) {
                    if (current.getPixel(x, y) != neutral) continue
                    val neighbours = listOf(
                        current.getPixel(x - 1, y),
                        current.getPixel(x + 1, y),
                        current.getPixel(x, y - 1),
                        current.getPixel(x, y + 1)
                    ).filter { it != neutral }
                    if (neighbours.isEmpty()) continue
                    val r = neighbours.map { Color.red(it) }.average().toInt()
                    val g = neighbours.map { Color.green(it) }.average().toInt()
                    val b = neighbours.map { Color.blue(it) }.average().toInt()
                    next.setPixel(x, y, Color.rgb(r, g, b))
                }
            }
            current.recycle(); current = next
        }
        return current
    }
}
