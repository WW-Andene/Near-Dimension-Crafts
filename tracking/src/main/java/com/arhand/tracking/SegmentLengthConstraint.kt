package com.arhand.tracking

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * IMP-3 — FK chain segment-length consistency pass applied after OEF smoothing.
 *
 * Problem: OEF smooths all 21 landmarks independently. It can push the index PIP to a
 * position geometrically unreachable from its MCP parent — producing "rubber finger"
 * stretch during fast motion or occlusion recovery.
 *
 * Solution: on the first full-visibility frame of a session, record each segment's
 * rest length as a ratio relative to the wrist→index-MCP ("palm") distance. Each
 * subsequent frame, clamp each child landmark to within ±50% of its expected length
 * from its parent. Positions already in range pass through unchanged.
 *
 * - No assumed population averages; calibration comes from the user's own geometry.
 * - Palm reference is re-measured each frame so scale changes (hand moving toward/away
 *   from camera) are tracked automatically.
 * - Fallback: before calibration completes, all landmarks pass through unchanged.
 *
 * Integration point: after OEF in [HandPipeline.update], before the motion gate.
 *
 * Thread safety: not thread-safe. Call from a single tracking thread per hand slot.
 */
class SegmentLengthConstraint {

    private var palmRef   = 0f
    private val segRatios = FloatArray(21) { 0f }
    var isCalibrated = false
        private set

    /**
     * Record segment ratios from a full-visibility reference frame.
     * Call once when the hand first achieves full visibility (all landmarks with
     * high confidence) so ratios reflect the user's actual hand geometry.
     *
     * Safe to call repeatedly — each call refreshes the calibration.
     */
    fun calibrate(lms: List<Landmark>) {
        if (lms.size < 21) return
        val p0 = lms[LM.WRIST]; val p5 = lms[LM.INDEX_MCP]
        val ref = dist3(p0, p5)
        if (ref < 0.001f) return
        palmRef = ref

        for (chain in OcclusionEngine.FINGER_CHAINS) {
            for (ci in 1 until chain.size) {
                val a = lms[chain[ci - 1]]; val b = lms[chain[ci]]
                segRatios[chain[ci]] = dist3(a, b) / palmRef
            }
        }
        isCalibrated = true
    }

    /**
     * Clamp each landmark in the finger chains to within ±50% of its calibrated
     * segment length from its parent. Returns the input list unchanged when uncalibrated.
     */
    fun apply(lms: List<Landmark>): List<Landmark> {
        if (!isCalibrated || lms.size < 21) return lms

        val curPalmRef = dist3(lms[LM.WRIST], lms[LM.INDEX_MCP]).coerceAtLeast(0.001f)

        // If we read from the mutating result list, a clamped PIP shifts the DIP's reference
        // parent, cascading the correction error down the chain.
        val original = lms  // immutable reference; only result is mutated below
        val result   = lms.toMutableList()

        for (chain in OcclusionEngine.FINGER_CHAINS) {
            for (ci in 1 until chain.size) {
                val parentIdx = chain[ci - 1]
                val childIdx  = chain[ci]
                val parent = original[parentIdx]   // read from original, not result
                val child  = original[childIdx]    // read from original, not result

                val expected = segRatios[childIdx] * curPalmRef
                val dx = child.x - parent.x
                val dy = child.y - parent.y
                val dz = child.z - parent.z
                val actual = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)

                val ratio = (expected / actual).coerceIn(0.5f, 1.5f)
                if (ratio != 1.0f) {
                    result[childIdx] = child.copy(
                        x = parent.x + dx * ratio,
                        y = parent.y + dy * ratio,
                        z = parent.z + dz * ratio
                    )
                }
            }
        }
        return result
    }

    /** Reset calibration — call on tracking loss or hand-slot reset. */
    fun reset() {
        isCalibrated = false
        palmRef = 0f
        segRatios.fill(0f)
    }

    private fun dist3(a: Landmark, b: Landmark) =
        sqrt((b.x - a.x).pow(2) + (b.y - a.y).pow(2) + (b.z - a.z).pow(2))
}
