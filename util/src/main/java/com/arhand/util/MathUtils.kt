package com.arhand.util

import kotlin.math.sqrt

// View scale — matches HTML prototype VIEW_SCALE = 0.65
const val VIEW_SCALE = 0.65f

/**
 * Linear interpolation between two floats.
 */
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Simple 3-component vector — pure math, no external dependencies.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3)  = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float)= Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3)   = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length()       = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-8f) Vec3(0f, 1f, 0f) else this * (1f / l)
    }
    fun lerp(o: Vec3, t: Float) = Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    companion object {
        val ZERO  = Vec3(0f, 0f, 0f)
        val UP    = Vec3(0f, 1f, 0f)
        val RIGHT = Vec3(1f, 0f, 0f)
    }
}

// NOTE: lmDist, clampDelta, landmarkToWorld have moved to
// com.arhand.tracking.LandmarkUtils to fix the inverted util→tracking dependency.
// Import them from com.arhand.tracking instead.
