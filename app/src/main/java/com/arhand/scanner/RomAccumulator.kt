package com.arhand.scanner

import com.arhand.tracking.HandLandmarks
import com.arhand.tracking.LM
import com.arhand.util.Vec3
import com.arhand.scanner.QualityEngine
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * D5 — Joint Range-of-Motion accumulator.
 *
 * Tracks the minimum and maximum flexion angle (in degrees) for each
 * finger joint across all frames fed during a scan.
 *
 * Joints measured: MCP, PIP, DIP for all four fingers; MCP and IP for the thumb.
 * Angle is defined as the interior angle between the two bone vectors meeting at
 * each joint — 0° = fully extended / straight, 90° = perpendicular, 180° = curled.
 *
 * Only landmarks with visibility ≥ [MIN_VISIBILITY] contribute to the running min/max.
 */
class RomAccumulator {

    companion object {
        private const val MIN_VISIBILITY = 0.35f

        // (proximal, joint, distal) triplets for every measurable joint
        // Reading: angle is measured at `joint` using vectors joint→proximal and joint→distal.
        val JOINT_TRIPLETS: List<Triple<Int, Int, Int>> = listOf(
            // Thumb
            Triple(LM.THUMB_CMC,   LM.THUMB_MCP,  LM.THUMB_IP),
            Triple(LM.THUMB_MCP,   LM.THUMB_IP,   LM.THUMB_TIP),
            // Index
            Triple(LM.INDEX_MCP,   LM.INDEX_PIP,  LM.INDEX_DIP),
            Triple(LM.INDEX_PIP,   LM.INDEX_DIP,  LM.INDEX_TIP),
            // Middle
            Triple(LM.MIDDLE_MCP,  LM.MIDDLE_PIP, LM.MIDDLE_DIP),
            Triple(LM.MIDDLE_PIP,  LM.MIDDLE_DIP, LM.MIDDLE_TIP),
            // Ring
            Triple(LM.RING_MCP,    LM.RING_PIP,   LM.RING_DIP),
            Triple(LM.RING_PIP,    LM.RING_DIP,   LM.RING_TIP),
            // Pinky
            Triple(LM.PINKY_MCP,   LM.PINKY_PIP,  LM.PINKY_DIP),
            Triple(LM.PINKY_PIP,   LM.PINKY_DIP,  LM.PINKY_TIP)
        )

        val JOINT_LABELS = listOf(
            "Thumb MCP", "Thumb IP",
            "Index PIP", "Index DIP",
            "Middle PIP", "Middle DIP",
            "Ring PIP", "Ring DIP",
            "Pinky PIP", "Pinky DIP"
        )

        /** Number of distinct joints tracked. */
        val JOINT_COUNT = JOINT_TRIPLETS.size
    }

    /** Per-joint minimum angle (degrees), initialized to +∞ */
    private val minAngles = FloatArray(JOINT_COUNT) { Float.MAX_VALUE }
    /** Per-joint maximum angle (degrees), initialized to −∞ */
    private val maxAngles = FloatArray(JOINT_COUNT) { -Float.MAX_VALUE }
    /** Number of valid frames that updated each joint. */
    private val frameCount = IntArray(JOINT_COUNT) { 0 }

    /**
     * Feed one frame of landmarks into the accumulator.
     *
     * IMP-R2: velocity gating — [prevLms] is used to compute a temporal consistency
     * score via [QualityEngine.temporalConsistencyScore]. Frames where the hand is
     * moving fast (mid-gesture transitions) are excluded because they produce
     * non-physiological intermediate angles that skew the min/max ROM readings.
     *
     * Threshold: consistency < 0.65 → frame rejected.
     * This corresponds to mean inter-frame displacement > ~8% of palm size,
     * which is consistent with active motion between pose targets.
     *
     * @param lms      Current frame landmarks.
     * @param prevLms  Previous frame landmarks for velocity estimation. Pass null to
     *                 accept the frame unconditionally (e.g. first frame of session).
     */
    fun update(lms: HandLandmarks, prevLms: HandLandmarks? = null) {
        if (lms.size < 21) return

        // Reject fast-moving frames — mid-gesture angles contaminate ROM measurement.
        if (prevLms != null) {
            val consistency = QualityEngine.temporalConsistencyScore(lms, prevLms)
            if (consistency < 0.65f) return
        }

        JOINT_TRIPLETS.forEachIndexed { i, (proxIdx, jIdx, distIdx) ->
            val prox = lms[proxIdx]
            val joint = lms[jIdx]
            val dist = lms[distIdx]

            // Skip if any of the three landmarks are unreliable
            if (prox.visibility < MIN_VISIBILITY ||
                joint.visibility < MIN_VISIBILITY ||
                dist.visibility < MIN_VISIBILITY) return@forEachIndexed

            val angleDeg = angleDegrees(
                Vec3(prox.x, prox.y, prox.z),
                Vec3(joint.x, joint.y, joint.z),
                Vec3(dist.x, dist.y, dist.z)
            )

            if (angleDeg.isNaN() || angleDeg.isInfinite()) return@forEachIndexed

            if (angleDeg < minAngles[i]) minAngles[i] = angleDeg
            if (angleDeg > maxAngles[i]) maxAngles[i] = angleDeg
            frameCount[i]++
        }
    }

    /**
     * Build the final [JointRomData] result.
     * Joints with fewer than [minFrames] observations are marked as unmeasured.
     */
    fun build(minFrames: Int = 5): JointRomData {
        val joints = (0 until JOINT_COUNT).map { i ->
            val measured = frameCount[i] >= minFrames &&
                minAngles[i] != Float.MAX_VALUE &&
                maxAngles[i] != -Float.MAX_VALUE
            JointRom(
                label    = JOINT_LABELS[i],
                minDeg   = if (measured) minAngles[i] else Float.NaN,
                maxDeg   = if (measured) maxAngles[i] else Float.NaN,
                measured = measured
            )
        }
        return JointRomData(joints)
    }

    /** Reset accumulator state for reuse. */
    fun reset() {
        for (i in 0 until JOINT_COUNT) {
            minAngles[i] = Float.MAX_VALUE
            maxAngles[i] = -Float.MAX_VALUE
            frameCount[i] = 0
        }
    }

    // ── math ─────────────────────────────────────────────────────────────────

    /**
     * Interior angle at [joint] (degrees), formed by bone vectors joint→prox and joint→dist.
     * Returns the angle in [0°, 180°].
     */
    private fun angleDegrees(prox: Vec3, joint: Vec3, dist: Vec3): Float {
        val v1 = (prox - joint).normalized()
        val v2 = (dist - joint).normalized()
        // Clamp dot to [-1,1] to guard against floating-point noise
        val dot = v1.dot(v2).coerceIn(-1f, 1f)
        return (acos(dot) * (180.0 / PI)).toFloat()
    }
}
