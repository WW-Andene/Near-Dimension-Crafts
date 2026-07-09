package com.arhand.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * ARCH-4 — Expanded performance state.
 *
 * Adds per-pipeline confidence metrics and model-budget pressure on top of the
 * existing FPS / inference / skip-ratio measurements.
 *
 * All new fields default to 0f so existing consumers compile without changes.
 */
data class PerfState(
    // ── Existing metrics ────────────────────────────────────────────────────
    val fps:            Float = 0f,
    val inferenceMs:    Float = 0f,
    val skipRatio:      Float = 0f,

    // ── ARCH-4: Tracking quality ─────────────────────────────────────────────
    /** Median MediaPipe hand detection confidence in the current window (0–1). */
    val handConfidence:  Float = 0f,
    /** Median body pose landmark visibility in the current window (0–1). */
    val bodyConfidence:  Float = 0f,
    /** Median face detection confidence in the current window (0–1). */
    val faceConfidence:  Float = 0f,

    // ── ARCH-4: Model budget pressure ─────────────────────────────────────────
    /**
     * Number of MediaPipe models currently submitting inference (1–3).
     * 1 = hand only, 2 = hand + body or face, 3 = hand + body + face.
     */
    val activeModelCount: Int   = 1,
    /**
     * Combined async-dispatch time for all active models in the most recent
     * inference frame (milliseconds). Includes CLAHE + all model submits.
     * Does NOT include GPU execution time (which runs asynchronously).
     */
    val totalInferenceMs: Float = 0f,

    // ── ARCH-4: Budget manager state ──────────────────────────────────────────
    /**
     * PERF-1 — True when the model budget manager has reduced body inference rate
     * below 1:1 with hand inference due to frame-budget pressure.
     */
    val bodyThrottled:    Boolean = false,
    /**
     * PERF-1 — True when the model budget manager has reduced face inference rate
     * below 1:1 with hand inference due to frame-budget pressure.
     */
    val faceThrottled:    Boolean = false
)

/**
 * Tracks FPS, inference timing, and ARCH-4 expanded metrics.
 * Exposes [StateFlow<PerfState>] consumed by the HUD overlay.
 *
 * Thread safety: [onFrame] and [onInference] are called from the camera callback
 * thread. [updateQuality] and [updateBudget] are called from the tracking coroutine.
 * Plain `_state.value = _state.value.copy(...)` is a read-modify-write that isn't
 * atomic across threads — concurrent callers can race and silently drop each
 * other's field changes. All writes go through [MutableStateFlow.update], which
 * retries the whole copy under compare-and-swap until it applies cleanly.
 */
class PerfMonitor {
    private val _state = MutableStateFlow(PerfState())
    val state: StateFlow<PerfState> = _state

    private var frameCount     = 0
    private var windowStart    = System.nanoTime()
    private var inferenceMsEma = 0f
    private var inferredFrames = 0
    private var totalFrames    = 0

    // ARCH-4: confidence EMA accumulators (updated from tracking coroutine)
    private var handConfEma  = 0f
    private var bodyConfEma  = 0f
    private var faceConfEma  = 0f

    fun onFrame() {
        frameCount++
        totalFrames++
        val now = System.nanoTime()
        val elapsed = (now - windowStart) / 1_000_000_000f
        if (elapsed >= 0.5f) {
            val fps = frameCount / elapsed
            _state.update {
                it.copy(
                    fps       = fps,
                    skipRatio = if (totalFrames > 0) 1f - (inferredFrames.toFloat() / totalFrames) else 0f,
                    // Publish confidence EMAs on the same 0.5s window
                    handConfidence = handConfEma,
                    bodyConfidence = bodyConfEma,
                    faceConfidence = faceConfEma
                )
            }
            frameCount     = 0
            inferredFrames = 0
            totalFrames    = 0
            windowStart    = now
        }
    }

    fun onInference(ms: Float) {
        inferredFrames++
        inferenceMsEma = inferenceMsEma * 0.8f + ms * 0.2f
        _state.update { it.copy(inferenceMs = inferenceMsEma) }
    }

    /**
     * ARCH-4 — Update per-pipeline confidence EMAs.
     *
     * Called from the tracking coroutine each inference frame with the latest
     * confidence values from each active pipeline. Pipelines that are off
     * should pass 0f — their EMA decays toward zero naturally.
     *
     * @param hand  MediaPipe hand detection confidence (0–1).
     * @param body  Mean body landmark visibility from [BodyRetargetResult.confidence] (0–1).
     * @param face  Face detection presence confidence (0–1; use 1.0 when face is detected).
     */
    fun updateQuality(hand: Float, body: Float, face: Float) {
        handConfEma = handConfEma * 0.85f + hand * 0.15f
        bodyConfEma = bodyConfEma * 0.85f + body * 0.15f
        faceConfEma = faceConfEma * 0.85f + face * 0.15f
    }

    /**
     * ARCH-4 / PERF-1 — Update model budget state reported by [ModelBudgetManager].
     *
     * @param activeModels   Number of models submitting inference this frame (1–3).
     * @param totalDispatchMs Combined dispatch time for all models this frame.
     * @param bodyThrottled  Whether body inference is running at reduced rate.
     * @param faceThrottled  Whether face inference is running at reduced rate.
     */
    fun updateBudget(
        activeModels:    Int,
        totalDispatchMs: Float,
        bodyThrottled:   Boolean,
        faceThrottled:   Boolean
    ) {
        _state.update {
            it.copy(
                activeModelCount = activeModels,
                totalInferenceMs = totalDispatchMs,
                bodyThrottled    = bodyThrottled,
                faceThrottled    = faceThrottled
            )
        }
    }

    fun reset() {
        frameCount     = 0
        totalFrames    = 0
        inferredFrames = 0
        inferenceMsEma = 0f
        handConfEma    = 0f
        bodyConfEma    = 0f
        faceConfEma    = 0f
        windowStart    = System.nanoTime()
        _state.value   = PerfState()
    }
}
