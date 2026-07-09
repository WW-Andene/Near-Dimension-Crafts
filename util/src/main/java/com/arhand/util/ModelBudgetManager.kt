package com.arhand.util

/**
 * PERF-1 — Multi-model inference budget manager.
 *
 * Problem: [com.arhand.util.FrameThrottler] adapts hand inference rate based on
 * hand dispatch time alone. Body and face models are submitted unconditionally on
 * every throttled frame alongside the hand. At 30fps with 3 model submissions the
 * combined async-dispatch time can exceed the frame budget, causing visible jank
 * even when [FrameThrottler] reports acceptable hand-only latency.
 *
 * Solution: track per-model dispatch EMA and shed lower-priority models when the
 * combined budget is tight. Priority: hand > face > body (body motion is slowest;
 * it loses the least perceptual quality from reduced inference rate).
 *
 * ## Shedding logic
 *
 * Total budget = [FRAME_BUDGET_MS] = 25ms (leaves ~8ms for GL + compositing at 30fps).
 *
 * Budget is split proportionally: at startup each model gets 1/N of the budget.
 * When total EMA exceeds budget:
 *   1. Reduce body to every 2nd throttled frame (`bodyEvery = 2`).
 *   2. If still over budget, reduce face to every 2nd throttled frame (`faceEvery = 2`).
 *   3. If still over budget, apply standard [FrameThrottler] backoff to hand.
 *
 * Recovery: when total EMA falls below budget × [RECOVERY_RATIO] (0.7), restore
 * models in reverse order (face first, then body), one step per recovery window.
 *
 * ## Usage
 *
 * ```kotlin
 * val budget = ModelBudgetManager()
 *
 * // Each camera frame, call tick() to get the submission decision:
 * val decision = budget.tick(handDispatchMs, bodyActive, faceActive)
 *
 * // Submit models according to the decision:
 * handTrackerManager.detect(bitmap, ts)                         // always
 * if (decision.submitBody) bodyPipeline.detect(bitmap, ts)
 * if (decision.submitFace) facePipeline.detect(bitmap, ts)
 *
 * // Report actual dispatch times after submission:
 * budget.reportDispatch(handMs, bodyMs, faceMs)
 * ```
 *
 * Thread safety: not thread-safe. Call from a single camera callback thread.
 */
class ModelBudgetManager {

    companion object {
        /** Total per-frame dispatch budget in milliseconds. */
        const val FRAME_BUDGET_MS    = 25f
        /** Begin recovery when total drops below this fraction of budget. */
        const val RECOVERY_RATIO     = 0.70f
        /** EMA weight for dispatch time measurements. */
        const val EMA_ALPHA          = 0.15f
        /** Minimum frames between body submissions when throttled. */
        const val BODY_THROTTLE_RATE = 2
        /** Minimum frames between face submissions when throttled. */
        const val FACE_THROTTLE_RATE = 2
    }

    // ── Per-model dispatch EMA ─────────────────────────────────────────────────
    private var handEma  = 0f
    private var bodyEma  = 0f
    private var faceEma  = 0f

    // ── Throttle counters ──────────────────────────────────────────────────────
    private var bodyCounter  = 0   // counts down to 0, then submit and reset
    private var faceCounter  = 0

    // ── Current throttle rates (1 = every frame, 2 = every other, …) ──────────
    var bodyEvery: Int = 1
        private set
    var faceEvery: Int = 1
        private set

    // ── Recovery window counter ───────────────────────────────────────────────
    private var recoveryWindow = 0

    /**
     * Returns the [SubmitDecision] for the current frame.
     *
     * Must be called once per throttled inference frame (i.e. when
     * [FrameThrottler.shouldInfer] returns true).
     *
     * @param bodyActive  Whether body tracking is currently enabled.
     * @param faceActive  Whether face tracking is currently enabled.
     */
    fun tick(bodyActive: Boolean, faceActive: Boolean): SubmitDecision {
        val total = handEma + (if (bodyActive) bodyEma else 0f) + (if (faceActive) faceEma else 0f)
        val activeCount = 1 + (if (bodyActive) 1 else 0) + (if (faceActive) 1 else 0)

        // ── Budget pressure: shed models ──────────────────────────────────────
        if (total > FRAME_BUDGET_MS) {
            // Shed body first (lowest priority)
            if (bodyEvery < BODY_THROTTLE_RATE && bodyActive) {
                bodyEvery = BODY_THROTTLE_RATE
            }
            // If still over budget, shed face. bodySavings must be gated by bodyActive
            // the same way `total` above is — bodyEma isn't part of `total` at all
            // when body is inactive, so subtracting it here would skew this check
            // against a value that isn't actually contributing to the budget.
            val bodySavings = if (bodyActive) bodyEma * (bodyEvery - 1) / bodyEvery else 0f
            if (total - bodySavings > FRAME_BUDGET_MS) {
                if (faceEvery < FACE_THROTTLE_RATE && faceActive) {
                    faceEvery = FACE_THROTTLE_RATE
                }
            }
            recoveryWindow = 0
        } else if (total < FRAME_BUDGET_MS * RECOVERY_RATIO) {
            // ── Budget recovery: restore models one step per 30 frames ───────
            recoveryWindow++
            if (recoveryWindow >= 30) {
                recoveryWindow = 0
                // Restore face first, then body
                when {
                    faceEvery > 1 -> faceEvery--
                    bodyEvery > 1 -> bodyEvery--
                }
            }
        }

        // ── Compute submit decisions ───────────────────────────────────────────
        val submitBody = bodyActive && run {
            bodyCounter--
            if (bodyCounter <= 0) { bodyCounter = bodyEvery; true } else false
        }
        val submitFace = faceActive && run {
            faceCounter--
            if (faceCounter <= 0) { faceCounter = faceEvery; true } else false
        }

        return SubmitDecision(
            submitBody       = submitBody,
            submitFace       = submitFace,
            activeModelCount = activeCount,
            bodyThrottled    = bodyEvery > 1,
            faceThrottled    = faceEvery > 1
        )
    }

    /**
     * Report measured dispatch times after model submission.
     *
     * Pass 0f for models that were not submitted this frame — their EMA decays
     * toward zero naturally, which is correct (they contribute no dispatch cost
     * on frames they skip).
     */
    fun reportDispatch(handMs: Float, bodyMs: Float, faceMs: Float) {
        handEma = handEma * (1f - EMA_ALPHA) + handMs * EMA_ALPHA
        bodyEma = bodyEma * (1f - EMA_ALPHA) + bodyMs * EMA_ALPHA
        faceEma = faceEma * (1f - EMA_ALPHA) + faceMs * EMA_ALPHA
    }

    /** Total combined dispatch EMA across all active models. */
    val totalEma: Float get() = handEma + bodyEma + faceEma

    /** Reset all state — call when tracking is fully restarted. */
    fun reset() {
        handEma      = 0f; bodyEma = 0f; faceEma = 0f
        bodyCounter  = 0;  faceCounter = 0
        bodyEvery    = 1;  faceEvery = 1
        recoveryWindow = 0
    }
}

/**
 * Per-frame model submission decision from [ModelBudgetManager.tick].
 */
data class SubmitDecision(
    /** Whether to call [com.arhand.tracking.BodyPipeline.detect] this frame. */
    val submitBody:       Boolean,
    /** Whether to call [com.arhand.tracking.FacePipeline.detect] this frame. */
    val submitFace:       Boolean,
    /** Number of models being submitted this frame (for telemetry). */
    val activeModelCount: Int,
    /** True when body inference is running at reduced rate. */
    val bodyThrottled:    Boolean,
    /** True when face inference is running at reduced rate. */
    val faceThrottled:    Boolean
)
