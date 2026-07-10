package com.arhand.export

/**
 * Single shared cylindrical UV projection used by both [GLBExporter] and [TextureBaker].
 *
 * Previously each computed its own formula independently and they drifted apart:
 * [GLBExporter] centred u/z on the mesh's AABB centre and left v non-inverted, while
 * [TextureBaker] used raw (uncentred) x/z and inverted v — so a baked texture atlas
 * lined up with the exported mesh's UVs only when the mesh happened to be centred at
 * the origin. See ENGINE_ARCHITECTURE.md §4.1.
 *
 * u = atan2(x - cx, z - cz) / 2π + 0.5   — angular wrap around the mesh's AABB centre
 * v = (y - minY) / yRange                 — linear along the AABB's Y extent, not inverted
 */
object MeshUvProjection {
    fun project(x: Float, y: Float, z: Float, cx: Float, cz: Float, minY: Float, yRange: Float): FloatArray {
        val u = (Math.atan2((x - cx).toDouble(), (z - cz).toDouble()) / (2.0 * Math.PI) + 0.5).toFloat()
        val v = ((y - minY) / yRange).coerceIn(0f, 1f)
        return floatArrayOf(u, v)
    }
}
