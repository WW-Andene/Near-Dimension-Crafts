package com.arhand.tracking

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * G4 — On-device gesture vocabulary classifier.
 *
 * Runs on the already-smoothed landmark stream produced by [HandPipeline].
 * Overhead is < 2ms per frame (pure Kotlin, no ML runtime required).
 *
 * ## Feature vector (14-dimensional, unchanged from previous version)
 *
 *   [0..4]   finger extension ratios [0–1]
 *   [5..8]   MCP–TIP curl (1 - extension) for index..pinky
 *   [9]      thumb–index tip gap (normalized by palm size)
 *   [10..13] inter-finger ANGLE at palm level (MCP vectors from wrist)
 *
 * ## Changes from previous version
 *
 * FIX-4 — Features [10..13]: tip-to-tip spread distances replaced by MCP vector angles.
 *   The previous spread features were distances between fingertips (normalized by palmSize).
 *   Problem: fingertip distances change dramatically with finger curl even at constant
 *   abduction — a curled FIST and a flat FIST look different in features [10..13] despite
 *   being the same gesture. They also conflate abduction with extension.
 *   Fix: compute the angle between adjacent finger base vectors (wrist→MCP for each finger).
 *   These are pure abduction/adduction signals, unaffected by finger curl or hand distance.
 *   The result is a genuinely scale- and curl-invariant spread feature.
 *   Templates for [10..13] updated accordingly.
 *
 * FIX-5 — Hysteresis debounce: re-confirm same gesture in 2 frames, new gesture in 4.
 *   The previous debounce required HOLD_FRAMES=4 regardless of whether the incoming
 *   gesture was the same as the last confirmed one or a new one. This meant:
 *   - After a brief hand occlusion (1–2 frames returning null), re-confirming the same
 *     gesture took 4 frames (~133ms at 30fps) — visible hesitation in OSC streaming and
 *     gesture shortcuts.
 *   Fix: track last confirmed gesture separately. When the candidate matches the last
 *   confirmed gesture, require only RECONFIRM_FRAMES=2. For a new gesture, still require
 *   HOLD_FRAMES=4. This halves re-entry latency without reducing false-positive rejection.
 */
class GestureClassifier {

    companion object {
        // Raised from 0.55 → 0.65: per-person variation in thumb abduction and
        // natural finger extension means 0.55 was too tight for many users. 0.65
        // still rejects ambiguous in-between states while accepting clear gestures.
        const val MATCH_THRESHOLD    = 0.65f
        // Lowered from 4 → 3: at FrameThrottler's default skip rate (~every 3rd frame)
        // 4 confirmed frames = up to 400ms latency. 3 frames = ~300ms, still
        // enough to reject spurious matches.
        const val HOLD_FRAMES        = 3
        const val RECONFIRM_FRAMES   = 2      // frames to re-confirm the SAME gesture
        const val FEATURE_DIM        = 14

        private val FINGER_CHAINS = arrayOf(
            intArrayOf(LM.THUMB_CMC,  LM.THUMB_MCP,  LM.THUMB_IP,   LM.THUMB_TIP),
            intArrayOf(LM.INDEX_MCP,  LM.INDEX_PIP,  LM.INDEX_DIP,  LM.INDEX_TIP),
            intArrayOf(LM.MIDDLE_MCP, LM.MIDDLE_PIP, LM.MIDDLE_DIP, LM.MIDDLE_TIP),
            intArrayOf(LM.RING_MCP,   LM.RING_PIP,   LM.RING_DIP,   LM.RING_TIP),
            intArrayOf(LM.PINKY_MCP,  LM.PINKY_PIP,  LM.PINKY_DIP,  LM.PINKY_TIP)
        )

        private val FINGER_BASE = intArrayOf(
            LM.THUMB_CMC,
            LM.INDEX_MCP,
            LM.MIDDLE_MCP,
            LM.RING_MCP,
            LM.PINKY_MCP
        )

        // MCP landmarks used for inter-finger angle features [10..13]
        // Each pair: (finger A MCP, finger B MCP) — angle of their vectors from wrist
        private val MCP_ANGLE_PAIRS = arrayOf(
            intArrayOf(LM.THUMB_CMC,  LM.INDEX_MCP),
            intArrayOf(LM.INDEX_MCP,  LM.MIDDLE_MCP),
            intArrayOf(LM.MIDDLE_MCP, LM.RING_MCP),
            intArrayOf(LM.RING_MCP,   LM.PINKY_MCP)
        )

        // ── Gesture templates ──────────────────────────────────────────────────
        // Feature layout:
        //   [0..4]   extension ratios:  thumb, index, middle, ring, pinky
        //   [5..8]   curl (1-extension): index, middle, ring, pinky
        //   [9]      thumb-index tip gap / palmSize
        //   [10..13] inter-finger MCP angle / π  (0=parallel, 1=90°)

        private val TEMPLATES: Map<Gesture, FloatArray> = mapOf(

            Gesture.OPEN_PALM to floatArrayOf(
                0.95f, 0.95f, 0.95f, 0.95f, 0.95f,
                0.05f, 0.05f, 0.05f, 0.05f,
                0.35f,
                0.18f, 0.12f, 0.12f, 0.10f   // MCP angles: wide spread
            ),

            Gesture.FIST to floatArrayOf(
                0.10f, 0.10f, 0.10f, 0.10f, 0.10f,
                0.90f, 0.90f, 0.90f, 0.90f,
                0.05f,
                0.06f, 0.04f, 0.04f, 0.04f   // MCPs close together (curled)
            ),

            Gesture.PEACE to floatArrayOf(
                0.15f, 0.95f, 0.90f, 0.10f, 0.10f,
                0.05f, 0.05f, 0.90f, 0.90f,
                0.30f,
                0.12f, 0.18f, 0.06f, 0.05f
            ),

            Gesture.THUMBS_UP to floatArrayOf(
                0.90f, 0.10f, 0.10f, 0.10f, 0.10f,
                0.90f, 0.90f, 0.90f, 0.90f,
                0.20f,
                0.16f, 0.04f, 0.04f, 0.04f
            ),

            Gesture.POINT_UP to floatArrayOf(
                0.15f, 0.95f, 0.10f, 0.10f, 0.10f,
                0.05f, 0.90f, 0.90f, 0.90f,
                0.30f,
                0.12f, 0.08f, 0.04f, 0.04f
            ),

            Gesture.OK to floatArrayOf(
                0.65f, 0.60f, 0.90f, 0.90f, 0.85f,
                0.30f, 0.30f, 0.05f, 0.10f,
                0.04f,
                0.08f, 0.12f, 0.10f, 0.10f
            ),

            Gesture.ROCK to floatArrayOf(
                0.15f, 0.90f, 0.10f, 0.10f, 0.90f,
                0.05f, 0.90f, 0.90f, 0.05f,
                0.35f,
                0.12f, 0.04f, 0.04f, 0.14f
            ),

            Gesture.CALL to floatArrayOf(
                0.90f, 0.10f, 0.10f, 0.10f, 0.90f,
                0.90f, 0.90f, 0.90f, 0.05f,
                0.35f,
                0.16f, 0.04f, 0.04f, 0.14f
            )
        )
    }

    // ─── Debounce state ───────────────────────────────────────────────────────

    private var holdCandidate:  Gesture? = null
    private var holdCount:      Int      = 0
    private var confirmedLast:  Gesture? = null   // FIX-5: last confirmed gesture

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * @param side  The detected [HandSide] from [HandPipeline.classifySide].
     *              When [HandSide.BACK] is confirmed, palm-specific gestures (OK, PEACE,
     *              POINT_UP, OPEN_PALM) are excluded from matching — they are geometrically
     *              ambiguous when viewed from the back and cause false positives against
     *              FIST and THUMBS_UP.
     */
    fun classify(lms: HandLandmarks, side: HandSide = HandSide.UNKNOWN): Gesture? {
        if (lms.size < 21) return debounce(null)
        val features = extractFeatures(lms)
        val best     = nearestTemplate(features, side)
        return debounce(best)
    }

    fun reset() {
        holdCandidate = null
        holdCount     = 0
        confirmedLast = null
    }

    // ─── Feature extraction ───────────────────────────────────────────────────

    private fun extractFeatures(lms: HandLandmarks): FloatArray {
        val palmSize = lmDist(lms[LM.WRIST], lms[LM.MIDDLE_MCP]).coerceAtLeast(1e-5f)
        val f = FloatArray(FEATURE_DIM)

        // [0..4] Finger extension ratios
        for (fi in 0..4) {
            val base    = lms[FINGER_BASE[fi]]
            val chain   = FINGER_CHAINS[fi]
            val tip     = lms[chain[3]]
            val straightDist = lmDist(base, tip)
            val chainLen = lmDist(base, lms[chain[0]]) +
                lmDist(lms[chain[0]], lms[chain[1]]) +
                lmDist(lms[chain[1]], lms[chain[2]]) +
                lmDist(lms[chain[2]], tip)
            f[fi] = if (chainLen > 1e-5f) (straightDist / chainLen).coerceIn(0f, 1f) else 0f
        }

        // [5..8] Curl = 1 - extension for index..pinky
        for (fi in 1..4) f[4 + fi] = 1f - f[fi]

        // [9] Thumb–index tip gap
        f[9] = (lmDist(lms[LM.THUMB_TIP], lms[LM.INDEX_TIP]) / palmSize).coerceIn(0f, 1f)

        // FIX-4: [10..13] Inter-finger MCP angles (normalized by π)
        // Each feature = angle between (wrist→MCP_A) and (wrist→MCP_B) / π
        // Pure abduction signal: unaffected by finger curl or hand scale.
        val wrist = lms[LM.WRIST]
        for ((i, pair) in MCP_ANGLE_PAIRS.withIndex()) {
            val vA = lms[pair[0]]
            val vB = lms[pair[1]]
            // Direction vectors from wrist to each MCP
            val axRaw = vA.x - wrist.x; val ayRaw = vA.y - wrist.y; val azRaw = vA.z - wrist.z
            val bxRaw = vB.x - wrist.x; val byRaw = vB.y - wrist.y; val bzRaw = vB.z - wrist.z
            val lenA = sqrt(axRaw * axRaw + ayRaw * ayRaw + azRaw * azRaw).coerceAtLeast(1e-6f)
            val lenB = sqrt(bxRaw * bxRaw + byRaw * byRaw + bzRaw * bzRaw).coerceAtLeast(1e-6f)
            val dot  = (axRaw * bxRaw + ayRaw * byRaw + azRaw * bzRaw) / (lenA * lenB)
            val angle = acos(dot.coerceIn(-1f, 1f))   // radians [0, π]
            f[10 + i] = (angle / Math.PI.toFloat()).coerceIn(0f, 1f)
        }

        return f
    }

    // ─── k-NN (k=1) ──────────────────────────────────────────────────────────

    // Gestures that require the palm to face the camera to be geometrically meaningful.
    // When the back of the hand is confirmed, these are excluded to avoid false positives.
    private val PALM_ONLY_GESTURES = setOf(Gesture.OK, Gesture.PEACE, Gesture.POINT_UP, Gesture.OPEN_PALM)

    private fun nearestTemplate(features: FloatArray, side: HandSide = HandSide.UNKNOWN): Gesture? {
        var bestGesture: Gesture? = null
        var bestDist = Float.MAX_VALUE
        for ((gesture, template) in TEMPLATES) {
            // Skip palm-facing-only gestures when we're looking at the back of the hand
            if (side == HandSide.BACK && gesture in PALM_ONLY_GESTURES) continue
            val dist = l2(features, template)
            if (dist < bestDist) { bestDist = dist; bestGesture = gesture }
        }
        return if (bestDist <= MATCH_THRESHOLD) bestGesture else null
    }

    private fun l2(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) { val d = a[i] - b[i]; sum += d * d }
        return sqrt(sum)
    }

    // ─── Debounce with hysteresis (FIX-5) ────────────────────────────────────

    private fun debounce(candidate: Gesture?): Gesture? {
        if (candidate == holdCandidate) {
            holdCount++
        } else {
            holdCandidate = candidate
            holdCount     = 1
        }
        // Re-confirming the last confirmed gesture needs fewer frames than a new one
        val required = if (holdCandidate != null && holdCandidate == confirmedLast)
            RECONFIRM_FRAMES else HOLD_FRAMES

        return if (holdCount >= required) {
            confirmedLast = holdCandidate
            holdCandidate
        } else null
    }

    private fun lmDist(a: Landmark, b: Landmark): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
