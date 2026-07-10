package com.arhand.util

/**
 * Adaptive frame throttler — mirrors _inferEvery logic in the HTML prototype.
 *
 * On mobile, inference budget is capped at MIN=[minEvery] MAX=[maxEvery] frames between runs.
 * In idle (hand still), the interval can extend to [maxEvery]*2 to cut GPU load.
 * The throttler adjusts based on last inference duration to keep UI smooth.
 *
 * IMP-6: when [motionMag] is below the hand-pipeline's motion gate threshold
 * (hand is still), inference rate is halved — the frozen-landmark path already
 * serves the correct output, so extra GPU inference is waste. When motion
 * resumes [reportInferenceMs] returns to the GPU-budget-based rate immediately,
 * with no ramp-up delay.
 *
 * Note: [inferEvery] changes take effect from the start of the *next* cycle.
 * The current countdown runs to completion before the new rate applies — this
 * introduces at most one cycle of lag (≤ maxEvery*2 frames) on rate transitions.
 */
class FrameThrottler(
    private val minEvery: Int = 1,
    private val maxEvery: Int = 4,
    private val targetMs: Float = 20f
) {
    // ENGINE_ARCHITECTURE.md §17.1 — was hardcoded to 2 (half camera rate) as the
    // unconditional starting point, before reportInferenceMs() ever measured real GPU
    // cost. That meant every session started pre-shed to half-rate hand inference
    // regardless of whether the device had budget to spare, adding a latency floor this
    // class's own adaptive logic was never asked to justify. Start at minEvery instead —
    // reportInferenceMs() sheds down from full rate under real measured load, matching
    // this class's own doc ("adjusts based on last inference duration") literally.
    private var inferEvery: Int = minEvery
    private var countdown: Int  = minEvery

    /**
     * Call every render frame. Returns true if inference should run this frame.
     */
    fun shouldInfer(): Boolean {
        if (--countdown <= 0) {
            countdown = inferEvery
            return true
        }
        return false
    }

    /**
     * Call after inference completes with the measured duration.
     * Adjusts throttle rate to stay within GPU budget.
     *
     * IMP-6: also accepts [motionMag] from HandPipeline. When the hand is still
     * (motionMag < [motionThreshold]), double the infer interval to cut inference
     * rate. Reverts to GPU-budget rate immediately on any motion — no ramp delay.
     *
     * @param ms              Measured inference dispatch duration in milliseconds.
     * @param motionMag       Sum of squared per-landmark displacements from HandPipeline.
     *                        Pass [Float.MAX_VALUE] when no data is available (default: open).
     * @param motionThreshold Below this the hand is considered still. Should match
     *                        HandPipeline.MOTION_GATE_THRESHOLD (0.001f).
     */
    fun reportInferenceMs(
        ms: Float,
        motionMag: Float = Float.MAX_VALUE,
        motionThreshold: Float = 0.001f
    ) {
        val gpuBased = when {
            ms < targetMs * 0.5f -> maxOf(minEvery, inferEvery - 1)
            ms > targetMs * 1.5f -> minOf(maxEvery, inferEvery + 1)
            else -> inferEvery
        }

        inferEvery = if (motionMag < motionThreshold) {
            minOf(maxEvery * 2, gpuBased * 2)
        } else {
            gpuBased
        }
    }

    fun currentRate(): Int = inferEvery
    fun reset() { countdown = minEvery; inferEvery = minEvery }
}
