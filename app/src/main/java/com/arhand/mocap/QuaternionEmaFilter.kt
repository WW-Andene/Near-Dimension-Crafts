package com.arhand.mocap

import kotlin.math.acos

/**
 * IMP-1 — Speed-adaptive quaternion EMA filter applied to [RetargetResult] output.
 *
 * Problem: [BoneRetargeter.shortestArcQuaternion] is nonlinear. Near 180° singularities,
 * small landmark displacements produce large quaternion jumps. OEF smooths landmark
 * positions, not the derived quaternions — high-frequency noise survives and shows as
 * microtremor on the rig and in OSC/BVH output.
 *
 * Solution: after retargeting, blend each joint quaternion toward its previous value
 * with an alpha that opens fully during motion and closes at rest:
 *   - angularSpeed ≥ [speedThresh] → α = 1.0 (passthrough, zero lag)
 *   - angularSpeed = 0             → α = [alphaMin] (~5-frame average)
 *
 * Side output: [lastAngularVelocity] per joint (rad/frame), consumed by IMP-5 OSC
 * velocity channel without extra computation.
 *
 * Thread safety: not thread-safe. Call from a single tracking thread.
 */
class QuaternionEmaFilter(
    /** α at rest — lower = more smoothing, more lag. */
    val alphaMin: Float   = 0.20f,
    /** α during fast motion — 1.0 = passthrough, no added lag. */
    val alphaMax: Float   = 1.00f,
    /** Angular speed (rad/frame) that fully opens the filter. */
    val speedThresh: Float = 0.8f
) {
    private val prev = HashMap<Int, Quaternion>()

    /**
     * Angular velocity (rad/frame) for each joint index, from the most recent [apply] call.
     * Consumed by IMP-5 to emit `/hand/<joint>/velocity` OSC messages without
     * re-differentiating the quaternion stream.
     */
    val lastAngularVelocity = HashMap<Int, Float>()

    /**
     * Apply speed-adaptive EMA smoothing to [result].
     *
     * @return A new [RetargetResult] with smoothed [RetargetResult.jointRotations].
     *         [RetargetResult.wristTransform] and [RetargetResult.jointPositions] are
     *         passed through unchanged — only the rotations are filtered.
     */
    fun apply(result: RetargetResult): RetargetResult {
        val smoothed = HashMap<Int, Quaternion>(result.jointRotations.size)
        lastAngularVelocity.clear()

        for ((joint, q) in result.jointRotations) {
            val p = prev[joint]
            if (p == null) {
                // Cold start — no history yet; pass through unmodified.
                smoothed[joint]            = q
                lastAngularVelocity[joint] = 0f
                prev[joint]               = q   // seed history so next frame has a valid prev
            } else {
                val dot = (p.x * q.x + p.y * q.y + p.z * q.z + p.w * q.w)
                    .let { if (it < 0f) -it else it }
                    .coerceIn(0f, 1f)
                val angularSpeed = 2f * acos(dot)

                val t     = (angularSpeed / speedThresh).coerceIn(0f, 1f)
                val alpha = alphaMin + (alphaMax - alphaMin) * t

                val blended = p.slerp(q, alpha)
                smoothed[joint]              = blended
                lastAngularVelocity[joint]   = angularSpeed
                prev[joint]                  = blended   // use local val to avoid double-bang dereference
            }
        }

        return result.copy(jointRotations = smoothed)
    }

    /** Clear history — call on hand-loss or tracking reset. */
    fun reset() {
        prev.clear()
        lastAngularVelocity.clear()
    }
}
