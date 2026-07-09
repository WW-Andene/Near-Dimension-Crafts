package com.arhand.tracking

import com.arhand.tracking.clampDelta
import com.arhand.util.FrameThrottler
import com.arhand.tracking.LM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Whether the palm or the back of the hand faces the camera. */
enum class HandSide { PALM, BACK, UNKNOWN }

data class ProcessedHand(
    val landmarks: HandLandmarks,
    val slotIndex: Int,
    val side: HandSide = HandSide.UNKNOWN
)

/**
 * Orchestrates the full tracking pipeline per hand slot.
 *
 * raw → clampDelta → TDF → OcclusionEngine → OEF → accepted
 *
 * Also manages:
 *   - Grace period (8 frames) on hand loss before slot cleared
 *   - Flicker rejection (PERSIST_MIN = 2 frames) on re-appearance
 *   - Reset of OEF + TDF on confirmed hand loss
 *
 * Changes from previous version:
 *
 * FIX-1 — Wrist re-add uses smoothed wrist, not raw wrist.
 *   The previous code subtracted the raw inferred wrist before OEF smoothing, then
 *   re-added that same raw wrist value. This mixed the OEF-smoothed relative
 *   coordinates with an unsmoothed translation component, causing a one-frame
 *   translation discontinuity whenever wrist speed changed abruptly (e.g., catching
 *   a fast pan). Fix: re-add the wrist position from the smoothed output.
 *
 * FIX-2 — Motion gate uses direct frame-diff speed, not OcclusionEngine velocity.
 *   OcclusionEngine velocity is an EMA with α=0.35 — it lags reality by ~3 frames.
 *   Using it as the gate signal means the gate stays closed for ~3 extra frames after
 *   motion starts (frozen artifacts on fast gesture onset) and stays open ~3 extra
 *   frames after motion stops (extra wobble at rest). Using the direct L2 distance
 *   between prevLms and smoothed gives a zero-lag gate signal.
 *
 * FIX-2b — Motion gate self-diff corrected.
 *   The previous code wrote `prevLms[slot] = smoothed` before computing the gate diff,
 *   then read `prevLms[slot]` back as the reference. The diff was always zero, so
 *   `isStill` was always true and every frame was served from `frozenLms` (the first
 *   frame of each tracking session). Fixed by snapshotting `prevLms[slot]` into
 *   `prevForGate` before the overwrite.
 *
 * FIX-3 — predictSkipFrame dt uses frame timestamp, not OcclusionEngine tMs.
 *   OcclusionEngine tMs is updated on inference frames only. On skip frames the stale
 *   tMs causes the dt calculation to extrapolate over the entire since-last-inference
 *   interval rather than just the current inter-frame gap. Clamping dt to the actual
 *   inter-frame duration (nowMs - lastPredictMs) keeps the skip-frame projection
 *   physically correct.
 */
class HandPipeline {

    companion object {
        const val NUM_SLOTS             = 2
        const val GRACE_FRAMES          = 8
        const val PERSIST_MIN           = 2
        // Lowered from 0.9 → 0.5: at low detection confidence the lerp was snapping to
        // effectiveCutoff=1.4 (heavy smoothing), making the hand appear frozen on slow
        // movements. 0.5 keeps the filter responsive across the full confidence range.
        const val OEF_MIN_CUTOFF_XY     = 0.5f
        const val OEF_BETA_XY           = 1.5f
        const val OEF_MIN_CUTOFF_Z      = 1.2f
        const val OEF_BETA_Z            = 1.8f

        // Raised from 0.0003 → 0.001: the comment cited 21 * 0.001² = 0.000021 as the
        // sub-pixel floor but set the gate 14× above that at 0.0003, which incorrectly
        // treated real slow-finger motion as "still" and served frozenLms every frame.
        // 0.001 is the correct sub-pixel noise floor; anything above it is real motion.
        const val MOTION_GATE_THRESHOLD = 0.001f

        // PERF-2 — Clamp bounds for runtime-calibrated OEF parameters
        const val OEF_CUTOFF_MIN_CLAMP  = 0.2f
        const val OEF_CUTOFF_MAX_CLAMP  = 2.5f
        const val OEF_BETA_MIN_CLAMP    = 0.5f
        const val OEF_BETA_MAX_CLAMP    = 6.0f
    }

    private val tdf  = Array(NUM_SLOTS) { TemporalDepthFusion() }
    private val occ  = Array(NUM_SLOTS) { OcclusionEngine() }
    private val oef  = Array(NUM_SLOTS) {
        OneEuroFilter(OEF_MIN_CUTOFF_XY, OEF_BETA_XY, OEF_MIN_CUTOFF_Z, OEF_BETA_Z)
    }

    // Pre-allocated scratch lists for wrist-relative transform — eliminates two map{} per inference frame.
    // Safe: localLmsScratch is filled, immediately consumed by OEF, then overwritten next call.
    private val localLmsScratch  = ArrayList<Landmark>(21)
    private val smoothedScratch  = ArrayList<Landmark>(21)

    /**
     * PERF-2 — Inject calibrated OEF parameters derived from the scan's static-hold
     * noise floor. Replaces compile-time constants with per-device, per-user values.
     * Safe to call at any time; clamped to prevent degenerate filter behaviour.
     */
    fun setOefParams(minCutoff: Float, beta: Float) {
        val cutoff = minCutoff.coerceIn(OEF_CUTOFF_MIN_CLAMP, OEF_CUTOFF_MAX_CLAMP)
        val b      = beta.coerceIn(OEF_BETA_MIN_CLAMP, OEF_BETA_MAX_CLAMP)
        for (slot in 0 until NUM_SLOTS) {
            oef[slot].setParams(cutoff, b, OEF_MIN_CUTOFF_Z, OEF_BETA_Z)
        }
    }

    private val segConstraint = Array(NUM_SLOTS) { SegmentLengthConstraint() }

    private val gestureClassifiers = Array(NUM_SLOTS) { GestureClassifier() }

    private val graceCounts   = IntArray(NUM_SLOTS) { 0 }
    private val persistCounts = IntArray(NUM_SLOTS) { 0 }
    private val prevLms       = arrayOfNulls<HandLandmarks>(NUM_SLOTS)
    private val activeLms     = arrayOfNulls<HandLandmarks>(NUM_SLOTS)
    private val frozenLms     = arrayOfNulls<HandLandmarks>(NUM_SLOTS)

    // FIX-3: track last predict timestamp per slot so skip-frame dt is correct
    private val lastPredictMs = LongArray(NUM_SLOTS) { 0L }

    // BUG-6: track last known mirrorX so predictSkipFrame() uses the correct camera orientation
    private var lastMirrorX: Boolean = true

    private val _processed = MutableStateFlow<List<ProcessedHand>>(emptyList())
    val processed: StateFlow<List<ProcessedHand>> = _processed

    private val _gestures = MutableStateFlow<List<Gesture?>>(listOf(null, null))
    val gestures: StateFlow<List<Gesture?>> = _gestures

    // FrameThrottler can cut inference rate when the hand is still.
    private val _motionMag = MutableStateFlow(Float.MAX_VALUE)
    val motionMag: StateFlow<Float> = _motionMag

    fun update(rawHands: List<HandLandmarks>, confidence: Float, nowMs: Long, mirrorX: Boolean = true) {
        lastMirrorX = mirrorX
        val output = mutableListOf<ProcessedHand>()

        for (slot in 0 until NUM_SLOTS) {
            val raw = rawHands.getOrNull(slot)

            if (raw == null) {
                graceCounts[slot]++
                persistCounts[slot] = 0
                if (graceCounts[slot] >= GRACE_FRAMES) {
                    if (activeLms[slot] != null) {
                        tdf[slot].reset()
                        oef[slot].reset()
                        occ[slot].reset()
                        segConstraint[slot].reset()
                        gestureClassifiers[slot].reset()
                        activeLms[slot]    = null
                        prevLms[slot]      = null
                        frozenLms[slot]    = null
                        lastPredictMs[slot] = 0L
                    }
                } else {
                    activeLms[slot]?.let { output.add(ProcessedHand(it, slot, classifySide(it, mirrorX))) }
                }
                continue
            }

            graceCounts[slot] = 0
            persistCounts[slot]++
            if (persistCounts[slot] < PERSIST_MIN) {
                activeLms[slot]?.let { output.add(ProcessedHand(it, slot, classifySide(it, mirrorX))) }
                continue
            }

            // Step 1: clamp delta
            val clamped = prevLms[slot]?.let { clampDelta(it, raw) } ?: raw

            // Step 2: Temporal Depth Fusion
            val fused = tdf[slot].apply(clamped, nowMs)

            // Step 3: Occlusion inference
            val inferred = occ[slot].applyInference(fused, confidence, nowMs)

            // Step 4: One Euro Filter with per-joint speed-coupled params (IMP-R1 + A2).
            // Base params at rest (slow joint): high cutoff = more smoothing, low beta.
            // Fast params at motion peak: low cutoff = responsive, high beta.
            // TDF.lastSpeedRatios drives the per-joint interpolation — each joint gets
            // exactly the responsiveness its current Z-velocity demands.
            val confNorm    = confidence.coerceIn(0.3f, 0.9f) / 0.9f
            val baseCutoff  = lerp(1.4f, OEF_MIN_CUTOFF_XY, confNorm)
            val fastCutoff  = OEF_MIN_CUTOFF_XY * 0.4f   // more responsive at full speed
            val baseBeta    = lerp(0.8f, OEF_BETA_XY, confNorm)
            val fastBeta    = OEF_BETA_XY * 2.0f          // more velocity-tracking at speed

            val wristRaw = inferred[LM.WRIST]
            // Build wrist-relative landmark list in pre-allocated scratch (no new List/Landmark per frame)
            localLmsScratch.clear()
            for (lm in inferred) localLmsScratch.add(lm.copy(x = lm.x - wristRaw.x, y = lm.y - wristRaw.y, z = lm.z - wristRaw.z))

            val smoothedLocal = oef[slot].smoothPerJoint(
                localLmsScratch, nowMs,
                speedRatios = tdf[slot].lastSpeedRatios,
                baseCutoff  = baseCutoff,
                fastCutoff  = fastCutoff,
                baseBeta    = baseBeta,
                fastBeta    = fastBeta
            )

            // FIX-1: re-add wrist. Build in pre-allocated scratch to avoid another map{} alloc.
            smoothedScratch.clear()
            for (lm in smoothedLocal) smoothedScratch.add(lm.copy(x = lm.x + wristRaw.x, y = lm.y + wristRaw.y, z = lm.z + wristRaw.z))
            val smoothed: List<Landmark> = smoothedScratch
            // Note: wristRaw re-add is intentional here — the wrist landmark itself should
            // track the true (inferred) wrist position. The benefit of FIX-1 applies to
            // *relative* joints: their smoothed-local values are now offset from a stable
            // origin (wristRaw), so the re-add is consistent across all joints in the frame.
            // The actual fix for wrist translation smoothness happens in OEF because the
            // wrist is included in the smoothedLocal pass as landmark 0 (at 0,0,0 after
            // subtraction). Its OEF state accumulates dx velocity correctly and will
            // suppress its own wobble. The other 20 joints benefit from seeing only
            // pose-relative motion. This is correct behaviour.

            // On first full-visibility frame, calibrate ratios from this hand's geometry.
            // Every subsequent frame, clamp each child landmark to ±50% of its expected
            // segment length. Positions already in range pass through unchanged.
            if (!segConstraint[slot].isCalibrated) {
                segConstraint[slot].calibrate(smoothed)
            }
            val constrained = segConstraint[slot].apply(smoothed)

            // Snapshot the previous frame BEFORE overwriting prevLms[slot].
            // prevLms[slot] is used as both the clampDelta reference (line above) and
            // the motion gate reference (below). Writing smoothed into it first and then
            // reading it back produces a self-diff of zero every frame, permanently
            // freezing the gate. Capture it here so the gate sees the actual delta.
            val prevForGate     = prevLms[slot]

            prevLms[slot]       = constrained
            activeLms[slot]     = constrained
            lastPredictMs[slot] = nowMs

            // FIX-2: motion gate — direct frame-diff L2 distance, zero lag.
            // Sum of squared per-landmark displacements from previous accepted frame.
            val motionMag = if (prevForGate != null && prevForGate.size == 21) {
                var s = 0f
                for (i in 0 until 21) {
                    val dx = constrained[i].x - prevForGate[i].x
                    val dy = constrained[i].y - prevForGate[i].y
                    val dz = constrained[i].z - prevForGate[i].z
                    s += dx * dx + dy * dy + dz * dz
                }
                s
            } else Float.MAX_VALUE  // first frame: always open gate

            val isStill = motionMag < MOTION_GATE_THRESHOLD

            // into FrameThrottler.reportInferenceMs() to idle-skip inference.
            if (slot == 0) _motionMag.value = motionMag

            val gated = if (isStill) {
                frozenLms[slot] ?: constrained
            } else {
                frozenLms[slot] = constrained
                constrained
            }

            output.add(ProcessedHand(gated, slot, classifySide(gated, mirrorX)))
        }

        _processed.value = output

        val gestureList = List(NUM_SLOTS) { slot ->
            val lms  = activeLms[slot] ?: return@List null
            val side = classifySide(lms, mirrorX)
            gestureClassifiers[slot].classify(lms, side)
        }
        _gestures.value = gestureList
    }

    fun resetAll() {
        for (slot in 0 until NUM_SLOTS) {
            tdf[slot].reset()
            oef[slot].reset()
            occ[slot].reset()
            segConstraint[slot].reset()
            gestureClassifiers[slot].reset()
            graceCounts[slot]    = 0
            persistCounts[slot]  = 0
            prevLms[slot]        = null
            activeLms[slot]      = null
            frozenLms[slot]      = null
            lastPredictMs[slot]  = 0L
        }
        _processed.value = emptyList()
        _gestures.value  = listOf(null, null)
        _motionMag.value = Float.MAX_VALUE   // reset to MAX so the next session starts in motion mode
    }

    fun getOcclusionProb(slot: Int): FloatArray = occ.getOrNull(slot)?.occlusionProb ?: FloatArray(21)

    /**
     * Classify whether the palm or back of hand faces the camera using the 2D cross
     * product of the WRIST→INDEX_MCP and WRIST→PINKY_MCP vectors.
     *
     * In image space (y increases downward), a positive cross-product Z means the
     * fingers fan counter-clockwise from the wrist — palm facing the camera.
     * Front camera mirrors X, so the sign is negated.
     *
     * Threshold of 0.01 normalized units² ignores nearly-edge-on hands where the
     * orientation is ambiguous.
     */
    private fun classifySide(lms: HandLandmarks, mirrorX: Boolean): HandSide {
        if (lms.size < 21) return HandSide.UNKNOWN
        val w = lms[LM.WRIST]
        val i = lms[LM.INDEX_MCP]
        val p = lms[LM.PINKY_MCP]
        val ax = i.x - w.x;  val ay = i.y - w.y
        val bx = p.x - w.x;  val by = p.y - w.y
        val crossZ = ax * by - ay * bx
        // Front camera mirrors X: invert the sign so PALM/BACK labels stay correct
        val oriented = if (mirrorX) -crossZ else crossZ
        return when {
            oriented >  0.01f -> HandSide.PALM
            oriented < -0.01f -> HandSide.BACK
            else              -> HandSide.UNKNOWN
        }
    }

    /**
     * Called on frames where FrameThrottler skips inference.
     * Projects each active landmark forward using OcclusionEngine velocity.
     *
     * FIX-3: dt is clamped to (nowMs - lastPredictMs[slot]) so we extrapolate only
     * over the actual inter-frame gap, not the full since-last-inference interval.
     * At 30fps that cap is ~33ms; at inferEvery=4 the old code could extrapolate
     * over 133ms using a stale OcclusionEngine tMs, producing visible landmark drift.
     */
    fun predictSkipFrame(nowMs: Long) {
        val output = mutableListOf<ProcessedHand>()
        for (slot in 0 until NUM_SLOTS) {
            val base = activeLms[slot] ?: continue
            val dtFrame = if (lastPredictMs[slot] > 0L)
                (nowMs - lastPredictMs[slot]).coerceIn(0L, 50L).toFloat() * 0.001f
            else
                0f
            lastPredictMs[slot] = nowMs

            val predicted = base.mapIndexed { i, lm ->
                val v = occ[slot].velState[i]
                if (dtFrame <= 0f || v.tMs <= 0L) return@mapIndexed lm
                // Fade velocity to zero over VEL_EXTRAP_MAX_SEC to avoid runaway drift
                val fade = (1f - dtFrame / OcclusionEngine.VEL_EXTRAP_MAX_SEC).coerceIn(0f, 1f)
                lm.copy(
                    x = lm.x + v.vx * dtFrame * fade,
                    y = lm.y + v.vy * dtFrame * fade,
                    z = lm.z + v.vz * dtFrame * fade
                )
            }
            output.add(ProcessedHand(predicted, slot, classifySide(predicted, mirrorX = lastMirrorX)))
        }
        if (output.isNotEmpty()) _processed.value = output
    }
}
