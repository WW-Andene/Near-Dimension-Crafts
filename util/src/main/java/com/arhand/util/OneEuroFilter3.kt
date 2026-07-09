package com.arhand.util

import kotlin.math.PI
import kotlin.math.abs

/**
 * Per-axis One Euro Filter for a 3D point.
 *
 * Semantics match lidar.html's `OEF` class (mc, b, dc constructor; run(x,t)).
 * Separate cutoff params for XY vs Z allow tighter lateral smoothing and
 * slightly looser depth smoothing (Z is noisier in all three depth sources).
 *
 * Usage:
 *   val f = OneEuroFilter3(mcXY=1.0f, betaXY=0.006f, mcZ=0.7f, betaZ=0.003f)
 *   val (sx,sy,sz) = f.run(x, y, z, nowMs)
 */
class OneEuroFilter3(
    private val mcXY: Float  = 1.0f,
    private val betaXY: Float = 0.006f,
    private val mcZ: Float   = 0.7f,
    private val betaZ: Float  = 0.003f,
    private val dc: Float    = 1.0f
) {
    private var xVal = Float.NaN; private var xDv = 0f
    private var yVal = Float.NaN; private var yDv = 0f
    private var zVal = Float.NaN; private var zDv = 0f
    private var lastMs = 0L

    data class Result(val x: Float, val y: Float, val z: Float)

    fun run(x: Float, y: Float, z: Float, nowMs: Long): Result {
        if (xVal.isNaN()) {
            xVal = x; yVal = y; zVal = z; lastMs = nowMs
            return Result(x, y, z)
        }
        val dt = maxOf((nowMs - lastMs) * 0.001f, 1e-4f)
        lastMs = nowMs

        // Derivative must be updated (from the OLD value) and then used for THIS
        // frame's adaptive cutoff — `dv.also { dv = updateDx(...) }` reads as if it
        // does that, but .also returns its receiver evaluated before the lambda
        // runs, so it fed filterAxis the previous frame's stale derivative instead.
        xDv = updateDx(xDv, x, xVal, dt)
        yDv = updateDx(yDv, y, yVal, dt)
        zDv = updateDx(zDv, z, zVal, dt)

        xVal = filterAxis(xVal, xDv, x, dt, mcXY, betaXY)
        yVal = filterAxis(yVal, yDv, y, dt, mcXY, betaXY)
        zVal = filterAxis(zVal, zDv, z, dt, mcZ,  betaZ)
        return Result(xVal, yVal, zVal)
    }

    fun reset() { xVal = Float.NaN; xDv = 0f; yDv = 0f; zDv = 0f }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun updateDx(prevDv: Float, raw: Float, prev: Float, dt: Float): Float {
        val aD = alpha(dc, dt)
        val dr = (raw - prev) / dt
        return prevDv + aD * (dr - prevDv)
    }

    private fun filterAxis(prev: Float, dv: Float, raw: Float, dt: Float, mc: Float, beta: Float): Float {
        val cutoff = mc + beta * abs(dv)
        val a = alpha(cutoff, dt)
        return prev + a * (raw - prev)
    }
}
