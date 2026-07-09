package com.arhand.mocap

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HAND-1 — Biomechanical joint constraint filter.
 *
 * Problem: [BoneRetargeter] produces joint rotations from shortest-arc quaternions
 * derived from MediaPipe landmarks. MediaPipe regularly detects anatomically impossible
 * poses — DIP hyperextension, thumb crossing the palm, MCP over-abduction. These are
 * not noise; they are plausible-but-wrong detections that survive OEF and EMA smoothing
 * because those filters suppress temporal jitter, not anatomical implausibility.
 *
 * Solution: after [QuaternionEmaFilter], project each joint rotation into the feasible
 * angular range defined by physical ROM limits. The projection is a quaternion slerp
 * from the current rotation toward the nearest feasible rotation, applied only when
 * the joint angle exceeds its limit.
 *
 * ## ROM limits (degrees)
 *
 * | Joint group | Flexion range | Abduction range |
 * |-------------|--------------|-----------------|
 * | PIP (all fingers) | 0° – 110°    | none            |
 * | DIP (all fingers) | 0° – ~73°    | none (= 2/3 PIP)|
 * | MCP flexion       | 0° – 90°     | ±20°            |
 * | Thumb CMC         | 0° – 80°     | ±40°            |
 * | Thumb MCP         | 0° – 60°     | ±15°            |
 * | Thumb IP          | 0° – 80°     | none            |
 *
 * The DIP coupling ratio (2/3 of PIP) is enforced as a separate pass after
 * individual joint clamping — DIP is clamped to min(DIP_MAX, PIP × 0.67).
 *
 * ## Output
 *
 * Returns a new [RetargetResult] with clamped [RetargetResult.jointRotations].
 * [RetargetResult.wristTransform] and [RetargetResult.jointPositions] are passed
 * through unchanged.
 *
 * [totalCorrectionRad] — sum of angular corrections applied this frame (radians).
 * Published to [com.arhand.util.PerfMonitor] as `constraintPressure`.
 *
 * ## Thread safety
 * Stateless — safe to call from any thread.
 */
object BiomechanicalConstraintFilter {

    // ── ROM limits in radians ──────────────────────────────────────────────────
    private val DEG = Math.PI.toFloat() / 180f

    // PIP: 0°–110°
    private const val PIP_MIN =  0f
    private const val PIP_MAX = 110f * (Math.PI.toFloat() / 180f)

    // DIP: 0°–73° (≈ 110° × 2/3), further clamped to PIP × 2/3 per-frame
    private const val DIP_MAX_ABS = 73f * (Math.PI.toFloat() / 180f)
    private const val DIP_PIP_RATIO = 0.667f

    // MCP flexion: 0°–90°; abduction: ±20°
    private const val MCP_FLEX_MIN =  0f
    private const val MCP_FLEX_MAX = 90f * (Math.PI.toFloat() / 180f)
    private const val MCP_ABD_MAX  = 20f * (Math.PI.toFloat() / 180f)

    // Thumb CMC: 0°–80° flex, ±40° abd
    private const val THUMB_CMC_FLEX_MAX = 80f * (Math.PI.toFloat() / 180f)
    private const val THUMB_CMC_ABD_MAX  = 40f * (Math.PI.toFloat() / 180f)

    // Thumb MCP: 0°–60° flex, ±15° abd
    private const val THUMB_MCP_FLEX_MAX = 60f * (Math.PI.toFloat() / 180f)
    private const val THUMB_MCP_ABD_MAX  = 15f * (Math.PI.toFloat() / 180f)

    // Thumb IP: 0°–80° flex
    private const val THUMB_IP_MAX = 80f * (Math.PI.toFloat() / 180f)

    // ── Joint → constraint group mapping ──────────────────────────────────────
    private enum class ConstraintGroup {
        THUMB_CMC, THUMB_MCP, THUMB_IP,
        FINGER_MCP, FINGER_PIP, FINGER_DIP,
        WRIST   // unconstrained by this filter
    }

    private val JOINT_GROUP = mapOf(
        BoneRetargeter.JOINT_WRIST      to ConstraintGroup.WRIST,
        BoneRetargeter.JOINT_THUMB_CMC  to ConstraintGroup.THUMB_CMC,
        BoneRetargeter.JOINT_THUMB_MCP  to ConstraintGroup.THUMB_MCP,
        BoneRetargeter.JOINT_THUMB_IP   to ConstraintGroup.THUMB_IP,
        BoneRetargeter.JOINT_INDEX_MCP  to ConstraintGroup.FINGER_MCP,
        BoneRetargeter.JOINT_INDEX_PIP  to ConstraintGroup.FINGER_PIP,
        BoneRetargeter.JOINT_INDEX_DIP  to ConstraintGroup.FINGER_DIP,
        BoneRetargeter.JOINT_MIDDLE_MCP to ConstraintGroup.FINGER_MCP,
        BoneRetargeter.JOINT_MIDDLE_PIP to ConstraintGroup.FINGER_PIP,
        BoneRetargeter.JOINT_MIDDLE_DIP to ConstraintGroup.FINGER_DIP,
        BoneRetargeter.JOINT_RING_MCP   to ConstraintGroup.FINGER_MCP,
        BoneRetargeter.JOINT_RING_PIP   to ConstraintGroup.FINGER_PIP,
        BoneRetargeter.JOINT_RING_DIP   to ConstraintGroup.FINGER_DIP,
        BoneRetargeter.JOINT_PINKY_MCP  to ConstraintGroup.FINGER_MCP,
        BoneRetargeter.JOINT_PINKY_PIP  to ConstraintGroup.FINGER_PIP,
        BoneRetargeter.JOINT_PINKY_DIP  to ConstraintGroup.FINGER_DIP
    )

    // PIP→DIP pairs for tendon coupling enforcement
    private val PIP_DIP_PAIRS = listOf(
        BoneRetargeter.JOINT_INDEX_PIP  to BoneRetargeter.JOINT_INDEX_DIP,
        BoneRetargeter.JOINT_MIDDLE_PIP to BoneRetargeter.JOINT_MIDDLE_DIP,
        BoneRetargeter.JOINT_RING_PIP   to BoneRetargeter.JOINT_RING_DIP,
        BoneRetargeter.JOINT_PINKY_PIP  to BoneRetargeter.JOINT_PINKY_DIP
    )

    /** Last frame total correction magnitude in radians. Read by AppViewModel for telemetry. */
    @Volatile var totalCorrectionRad: Float = 0f
        private set

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Apply biomechanical constraints to [result] and return a new [RetargetResult]
     * with corrected joint rotations.
     *
     * Only modifies [RetargetResult.jointRotations]. Wrist transform and joint
     * positions are passed through unchanged.
     */
    fun apply(result: RetargetResult): RetargetResult {
        val constrained = HashMap<Int, Quaternion>(result.jointRotations.size)
        var correction  = 0f

        for ((jointIdx, q) in result.jointRotations) {
            val group = JOINT_GROUP[jointIdx] ?: ConstraintGroup.WRIST
            val (cq, delta) = clamp(q, group)
            constrained[jointIdx] = cq
            correction           += delta
        }

        // DIP–PIP tendon coupling: DIP ≤ PIP × DIP_PIP_RATIO
        for ((pipIdx, dipIdx) in PIP_DIP_PAIRS) {
            val pipAngle = quaternionAngle(constrained[pipIdx] ?: Quaternion.IDENTITY)
            val dipAngle = quaternionAngle(constrained[dipIdx] ?: Quaternion.IDENTITY)
            val maxDip   = minOf(DIP_MAX_ABS, pipAngle * DIP_PIP_RATIO)
            if (dipAngle > maxDip) {
                val (cq, delta) = clampToAngle(constrained[dipIdx]!!, maxDip)
                constrained[dipIdx] = cq
                correction += delta
            }
        }

        totalCorrectionRad = correction
        return result.copy(jointRotations = constrained)
    }

    // ── Constraint dispatch ───────────────────────────────────────────────────

    private fun clamp(q: Quaternion, group: ConstraintGroup): Pair<Quaternion, Float> =
        when (group) {
            ConstraintGroup.WRIST      -> Pair(q, 0f)   // wrist unconstrained
            ConstraintGroup.FINGER_PIP -> clampFlexion(q, PIP_MIN, PIP_MAX)
            ConstraintGroup.FINGER_DIP -> clampFlexion(q, 0f,      DIP_MAX_ABS)
            ConstraintGroup.FINGER_MCP -> clampFlexionAbduction(q, MCP_FLEX_MIN, MCP_FLEX_MAX, MCP_ABD_MAX)
            ConstraintGroup.THUMB_CMC  -> clampFlexionAbduction(q, 0f, THUMB_CMC_FLEX_MAX, THUMB_CMC_ABD_MAX)
            ConstraintGroup.THUMB_MCP  -> clampFlexionAbduction(q, 0f, THUMB_MCP_FLEX_MAX, THUMB_MCP_ABD_MAX)
            ConstraintGroup.THUMB_IP   -> clampFlexion(q, 0f, THUMB_IP_MAX)
        }

    // ── Constraint implementations ────────────────────────────────────────────

    /**
     * Clamp a single-axis rotation (flexion only) to [minRad, maxRad].
     *
     * The rotation angle is the full quaternion arc. Since joint rotations from
     * [BoneRetargeter] are shortest-arc quaternions (always ≤ 180°), the arc
     * direction is implicitly known — negative values represent hyperextension.
     */
    private fun clampFlexion(q: Quaternion, minRad: Float, maxRad: Float): Pair<Quaternion, Float> {
        val angle = quaternionAngle(q)
        return when {
            angle < minRad -> clampToAngle(q, minRad)
            angle > maxRad -> clampToAngle(q, maxRad)
            else           -> Pair(q, 0f)
        }
    }

    /**
     * Clamp flexion and abduction independently.
     *
     * Decomposes the quaternion into a primary (flexion) axis component and a
     * secondary (abduction) component, clamps each, and recomposes.
     *
     * This is an approximation — full decomposition into anatomical axes would
     * require knowing the joint's anatomical frame, which varies by asset.
     * The approximation is accurate enough to eliminate gross violations.
     */
    private fun clampFlexionAbduction(
        q:       Quaternion,
        flexMin: Float, flexMax: Float,
        abdMax:  Float
    ): Pair<Quaternion, Float> {
        val angle = quaternionAngle(q)
        val (qx, qy, qz, qw) = q

        // Primary axis = Y (flexion for most hand joints in standard bind pose)
        // Secondary axis = X (abduction/adduction)
        val flexAngle = 2f * kotlin.math.atan2(qy, qw)   // signed flexion around Y
        val abdAngle  = 2f * kotlin.math.atan2(qx, qw)   // signed abduction around X

        val clampedFlex = flexAngle.coerceIn(flexMin, flexMax)
        val clampedAbd  = abdAngle.coerceIn(-abdMax, abdMax)

        val correction = abs(flexAngle - clampedFlex) + abs(abdAngle - clampedAbd)
        if (correction < 1e-5f) return Pair(q, 0f)

        // Recompose from clamped angles (independent Y and X rotations)
        val qFlex = Quaternion(0f, sin(clampedFlex * 0.5f), 0f, cos(clampedFlex * 0.5f))
        val qAbd  = Quaternion(sin(clampedAbd  * 0.5f), 0f, 0f, cos(clampedAbd  * 0.5f))
        val clamped = (qFlex * qAbd).normalized()

        return Pair(clamped, correction)
    }

    /** Scale a quaternion's rotation angle to exactly [targetAngle], preserving axis. */
    private fun clampToAngle(q: Quaternion, targetAngle: Float): Pair<Quaternion, Float> {
        val currentAngle = quaternionAngle(q)
        val delta = abs(currentAngle - targetAngle)
        if (delta < 1e-5f) return Pair(q, 0f)

        // Extract axis and recompose with target angle
        val sinHalf = sqrt(1f - q.w * q.w).coerceAtLeast(1e-8f)
        val ax = q.x / sinHalf
        val ay = q.y / sinHalf
        val az = q.z / sinHalf
        val half = targetAngle * 0.5f
        val clamped = Quaternion(ax * sin(half), ay * sin(half), az * sin(half), cos(half)).normalized()
        return Pair(clamped, delta)
    }

    /** Extract the rotation angle (0–π) from a unit quaternion. */
    private fun quaternionAngle(q: Quaternion): Float =
        2f * acos(q.w.coerceIn(-1f, 1f))
}
