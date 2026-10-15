package com.arhand.tracking

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * One Euro Filter — port of the Géry Casiez implementation from the HTML prototype.
 *
 * Applies adaptive frequency filtering per landmark per axis (x, y, z).
 * Low beta = smooth but laggy; high beta = responsive but noisy.
 * Adjust minCutoff to control static noise, beta to control lag.
 *
 * Allocation strategy: smooth/smoothPerJoint return a pre-allocated ArrayList that is
 * cleared and refilled in-place each call. Callers must NOT hold references to the
 * returned list across the next call on the same OEF instance — copy if persistence is needed.
 * HandPipeline uses the result immediately (single-expression chain), so sharing is safe.
 */
class OneEuroFilter(
    private var minCutoffXY: Float,
    private var betaXY: Float,
    private var minCutoffZ: Float,
    private var betaZ: Float,
    private val dCutoff: Float = 1.0f
) {
    data class ScalarState(
        var value: Float,
        var dvalue: Float,
        var lastTimeMs: Long
    )

    data class LandmarkState(
        val x: ScalarState,
        val y: ScalarState,
        val z: ScalarState
    )

    private var states: Array<LandmarkState?>? = null
    // Pre-allocated result buffer — reused across calls on this instance
    private val resultBuf = ArrayList<Landmark>(21)

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1.0f / (2.0f * PI.toFloat() * cutoff)
        return 1.0f / (1.0f + tau / dt)
    }

    private fun filterScalar(
        state: ScalarState,
        raw: Float,
        nowMs: Long,
        minCutoff: Float,
        beta: Float
    ): Float {
        val dt = maxOf((nowMs - state.lastTimeMs) * 0.001f, 1e-4f)
        val aD = alpha(dCutoff, dt)
        val dRaw = (raw - state.value) / dt
        state.dvalue = state.dvalue + aD * (dRaw - state.dvalue)
        val cutoff = minCutoff + beta * abs(state.dvalue)
        val a = alpha(cutoff, dt)
        state.value = state.value + a * (raw - state.value)
        state.lastTimeMs = nowMs
        return state.value
    }

    fun ensureInit(numLandmarks: Int, _nowMs: Long) {
        if (states == null || states!!.size != numLandmarks) {
            states = Array(numLandmarks) { null }
        }
    }

    fun reset() {
        states = null
        resultBuf.clear()
    }

    fun setParams(newMinCutoffXY: Float, newBetaXY: Float, newMinCutoffZ: Float, newBetaZ: Float) {
        minCutoffXY = newMinCutoffXY
        betaXY      = newBetaXY
        minCutoffZ  = newMinCutoffZ
        betaZ       = newBetaZ
        reset()
    }

    fun smooth(lms: List<Landmark>, nowMs: Long): List<Landmark> =
        smooth(lms, nowMs, minCutoffXY, betaXY)

    fun smooth(lms: List<Landmark>, nowMs: Long, effectiveCutoffXY: Float, effectiveBetaXY: Float): List<Landmark> {
        ensureInit(lms.size, nowMs)
        val st = states!!
        resultBuf.clear()
        for (i in lms.indices) {
            val lm = lms[i]
            if (st[i] == null) {
                st[i] = LandmarkState(
                    ScalarState(lm.x, 0f, nowMs),
                    ScalarState(lm.y, 0f, nowMs),
                    ScalarState(lm.z, 0f, nowMs)
                )
                resultBuf.add(lm)
            } else {
                val s = st[i]!!
                val sx = filterScalar(s.x, lm.x, nowMs, effectiveCutoffXY, effectiveBetaXY)
                val sy = filterScalar(s.y, lm.y, nowMs, effectiveCutoffXY, effectiveBetaXY)
                val sz = filterScalar(s.z, lm.z, nowMs, minCutoffZ,        betaZ)
                resultBuf.add(lm.copy(x = sx, y = sy, z = sz))
            }
        }
        return resultBuf
    }

    /**
     * IMP-R1 — Per-landmark dynamic params: each landmark gets its own cutoff/beta
     * derived from [speedRatios] (from TDF.lastSpeedRatios).
     */
    fun smoothPerJoint(
        lms: List<Landmark>,
        nowMs: Long,
        speedRatios: FloatArray?,
        baseCutoff: Float,
        fastCutoff: Float,
        baseBeta: Float,
        fastBeta: Float
    ): List<Landmark> {
        ensureInit(lms.size, nowMs)
        val st = states!!
        resultBuf.clear()
        for (i in lms.indices) {
            val lm = lms[i]
            val ratio   = speedRatios?.getOrNull(i) ?: 0f
            val eCutoff = baseCutoff + (fastCutoff - baseCutoff) * ratio
            val eBeta   = baseBeta   + (fastBeta   - baseBeta)   * ratio
            if (st[i] == null) {
                st[i] = LandmarkState(
                    ScalarState(lm.x, 0f, nowMs),
                    ScalarState(lm.y, 0f, nowMs),
                    ScalarState(lm.z, 0f, nowMs)
                )
                resultBuf.add(lm)
            } else {
                val s = st[i]!!
                val sx = filterScalar(s.x, lm.x, nowMs, eCutoff, eBeta)
                val sy = filterScalar(s.y, lm.y, nowMs, eCutoff, eBeta)
                val sz = filterScalar(s.z, lm.z, nowMs, minCutoffZ, betaZ)
                resultBuf.add(lm.copy(x = sx, y = sy, z = sz))
            }
        }
        return resultBuf
    }
}
