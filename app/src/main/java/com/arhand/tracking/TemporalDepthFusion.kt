package com.arhand.tracking

import kotlin.math.exp

/**
 * Temporal Depth Fusion (TDF) — port of applyTemporalDepthFusion() from the HTML prototype.
 *
 * Smooths MediaPipe's noisy Z proxy using a speed-adaptive EMA.
 * Fast motion → high alpha (responsive).
 * Slow/static → low alpha (smooth).
 *
 * A4 — Adaptive per-joint SPEED_THRESH based on running 90th-percentile Z-velocity.
 * Finger joints and wrist joints have very different natural speed ranges.
 * Instead of a single global SPEED_THRESH = 0.30f, each joint slot accumulates a
 * rolling window of |dz| samples and uses the 90th percentile as its saturation point.
 * This prevents slow-moving distal joints from being over-damped and fast-moving wrist
 * joints from being under-damped.
 *
 * This does NOT produce real metric depth — MediaPipe Z is still a proxy.
 * TDF only removes the frame-to-frame jitter that makes the proxy unusable for rendering.
 */
class TemporalDepthFusion {

    companion object {
        const val ALPHA_MIN         = 0.12f  // ~8-frame decay at rest
        const val ALPHA_MAX         = 0.55f  // responsive for fast motion
        const val SPEED_THRESH_GLOBAL = 0.30f // fallback until per-joint history is warm

        // A4: rolling window size for per-joint 90th-percentile velocity estimation
        private const val VELOCITY_HISTORY_SIZE = 30
    }

    data class LandmarkDepthState(
        var z: Float,
        var dz: Float = 0f,       // EMA z velocity
        var tMs: Long,
        var primed: Boolean = false,
        // A4: circular buffer of recent |dz| values for percentile estimation
        val dzHistory: FloatArray = FloatArray(VELOCITY_HISTORY_SIZE),
        var dzHistoryIdx: Int = 0,
        var dzHistoryFull: Boolean = false
    )

    private var states: Array<LandmarkDepthState?>? = null

    /**
     * IMP-R1 — Per-joint speed ratios from the last [apply] call.
     * Index matches landmark index. Values in [0,1]: 0=fully still, 1=at full speed.
     * Read by [HandPipeline] to dynamically tune OEF params per-joint.
     * Populated only after the first non-cold-start frame per joint.
     */
    val lastSpeedRatios: FloatArray = FloatArray(21) { 0f }

    fun reset() {
        states = null
    }

    /**
     * A4 — Compute the 90th-percentile of the velocity history for [state].
     * Returns [SPEED_THRESH_GLOBAL] if the buffer hasn't accumulated enough samples yet.
     * Minimum returned value is 0.05f to prevent division-by-zero and degenerate smoothing.
     */
    private fun percentile90SpeedThresh(state: LandmarkDepthState): Float {
        val count = if (state.dzHistoryFull) VELOCITY_HISTORY_SIZE else state.dzHistoryIdx
        if (count < 5) return SPEED_THRESH_GLOBAL  // not enough history yet
        val sorted = state.dzHistory.copyOf(count)
        sorted.sort()
        val p90idx = ((count - 1) * 0.90f).toInt().coerceIn(0, count - 1)
        return sorted[p90idx].coerceAtLeast(0.05f)
    }

    /**
     * Apply TDF in-place on a mutable landmark list.
     * Returns a new list with fused Z values.
     */
    fun apply(lms: List<Landmark>, nowMs: Long): List<Landmark> {
        if (states == null || states!!.size != lms.size) {
            states = Array(lms.size) { null }
        }
        val st = states!!
        return lms.mapIndexed { i, lm ->
            val rawZ = lm.z
            if (st[i] == null) {
                st[i] = LandmarkDepthState(z = rawZ, tMs = nowMs, primed = false)
                lm
            } else {
                val s = st[i]!!
                if (!s.primed) {
                    s.z = rawZ
                    s.tMs = nowMs
                    s.primed = true
                    lm
                } else {
                    val dt = maxOf((nowMs - s.tMs) * 0.001f, 1e-4f)
                    val instDz = (rawZ - s.z) / dt
                    // EMA velocity — alpha scaled by dt so the smoothing time-constant
                    // stays consistent across variable frame intervals (dropped frames,
                    // throttled inference). At 33ms (30fps) alpha≈0.3; at 66ms (missed
                    // frame) alpha≈0.5 (more responsive, not more sluggish).
                    // Time-constant τ = -dt / ln(1 - α_base), solved for α_base ≈ 0.3 at dt=33ms
                    val velAlpha = (1f - exp(-dt / 0.077f)).coerceIn(0.05f, 0.95f)
                    s.dz = s.dz + velAlpha * (instDz - s.dz)
                    val absDz = Math.abs(s.dz)

                    // A4: record |dz| into rolling history
                    s.dzHistory[s.dzHistoryIdx] = absDz
                    s.dzHistoryIdx = (s.dzHistoryIdx + 1) % VELOCITY_HISTORY_SIZE
                    if (s.dzHistoryIdx == 0) s.dzHistoryFull = true

                    // A4: use per-joint 90th-percentile speed threshold
                    val speedThresh = percentile90SpeedThresh(s)
                    val speedRatio = minOf(1f, absDz / speedThresh)
                    lastSpeedRatios[i] = speedRatio
                    val alpha = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * speedRatio
                    s.z = s.z + alpha * (rawZ - s.z)
                    s.tMs = nowMs
                    lm.copy(z = s.z)
                }
            }
        }
    }
}
