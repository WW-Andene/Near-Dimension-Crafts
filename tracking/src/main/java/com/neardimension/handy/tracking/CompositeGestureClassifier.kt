package com.arhand.tracking

import com.arhand.tracking.HandSide

/**
 * IMP-12 — Composite gesture classifier: hand gesture × face modifier.
 *
 * Problem: hand gestures alone have a limited vocabulary — 8–10 recognisable shapes
 * before user confusion rises. Multiplying by face modifiers (blink, jaw, brow)
 * triples the vocabulary without adding any new model, sensor, or capture step.
 *
 * Solution: read the hand gesture and face expression values that are already computed
 * every frame, and emit a [CompositeGesture] pairing a base [Gesture] with a
 * [FaceModifier]. Existing gesture bindings (no face modifier) keep working unchanged.
 * New bindings opt in to composite triggers.
 *
 * Integration point: [AppViewModel.dispatchGestureShortcut] — call
 * [CompositeGestureClassifier.classify] with the current [FullBodyFrame] to get the
 * composite result.
 *
 * Thread safety: the `classify(FullBodyFrame, slot)` overload holds hysteresis
 * state in a shared [GestureClassifier] instance and must be called from a single,
 * consistent thread (the tracking pipeline's). The `classify(Gesture, FaceExpressions?)`
 * overload is stateless.
 */

/**
 * Face modifier applied on top of a base [Gesture].
 *
 * Priority: BLINK > JAW > BROW > NONE — first threshold that fires wins.
 * Only one modifier fires per frame to keep classification deterministic.
 */
enum class FaceModifier {
    NONE,
    /** Both eyes blinking simultaneously (deliberate double-blink, not a squint). */
    BLINK,
    /** Jaw open wider than [CompositeGestureClassifier.JAW_THRESH]. */
    JAW,
    /** Either brow raised above [CompositeGestureClassifier.BROW_THRESH]. */
    BROW
}

/**
 * A hand gesture paired with an optional face modifier.
 *
 * @param base      The recognized [Gesture] from [GestureClassifier].
 * @param modifier  The [FaceModifier] active at the same frame, or [FaceModifier.NONE].
 */
data class CompositeGesture(
    val base:     Gesture,
    val modifier: FaceModifier
)

object CompositeGestureClassifier {

    /** Both-eyes blink intensity threshold. High to avoid squints or lighting artefacts. */
    const val BLINK_THRESH = 0.75f
    /** Jaw open intensity threshold. */
    const val JAW_THRESH   = 0.60f
    /** Either-brow raise intensity threshold. */
    const val BROW_THRESH  = 0.65f

    // Reused across calls: GestureClassifier's hysteresis debounce (HOLD_FRAMES /
    // RECONFIRM_FRAMES) needs its hold-count state carried across frames. A fresh
    // instance per call would never accumulate enough frames to confirm a gesture.
    private val gestureClassifier = GestureClassifier()

    /**
     * Classify the composite gesture from the current [FullBodyFrame].
     *
     * Returns null if no hand gesture is recognised.
     * Returns a [CompositeGesture] with [FaceModifier.NONE] if face data is absent
     * or no modifier threshold is met — so existing per-gesture handlers still fire.
     *
     * @param frame  The latest unified frame from the tracking pipeline.
     * @param slot   Hand slot index to read from (default: 0, primary hand).
     */
    fun classify(frame: FullBodyFrame, slot: Int = 0): CompositeGesture? {
        val lms = if (slot == 0) frame.leftHand else frame.rightHand
        val gesture = lms?.let { landmarks ->
            gestureClassifier.classify(landmarks, HandSide.UNKNOWN)
        } ?: return null

        // FACE-3: FullBodyFrame now carries derived FaceExpressions — use the
        // classify(Gesture, FaceExpressions?) overload so face modifiers fire correctly.
        return classify(gesture, frame.expressions)
    }

    /**
     * Overload that accepts face expressions directly, bypassing FullBodyFrame.
     *
     * Preferred call site in [AppViewModel] where [FacePipeline.expressions] is
     * already available as a [StateFlow] without needing to embed it in FullBodyFrame.
     *
     * @param gesture  Result from [GestureClassifier] for the primary hand slot.
     * @param fe       Current [FaceExpressions], or null if face tracking is off.
     */
    fun classify(gesture: Gesture, fe: FaceExpressions?): CompositeGesture {
        val modifier = when {
            fe == null -> FaceModifier.NONE
            fe.leftBlink  > BLINK_THRESH && fe.rightBlink > BLINK_THRESH -> FaceModifier.BLINK
            fe.jawOpen    > JAW_THRESH   -> FaceModifier.JAW
            fe.leftBrowRaise > BROW_THRESH || fe.rightBrowRaise > BROW_THRESH -> FaceModifier.BROW
            else -> FaceModifier.NONE
        }
        return CompositeGesture(gesture, modifier)
    }
}
