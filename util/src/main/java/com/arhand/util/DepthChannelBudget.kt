package com.arhand.util

/**
 * PERF-2 — Core-layer (depth/SLAM) per-frame budget manager.
 *
 * Problem (ENGINE_ARCHITECTURE.md §3): [FrameThrottler]/[ModelBudgetManager] only govern
 * MediaPipe tracking submission. The Core-layer depth channels called a few lines above that
 * gate in `SpatialFrameProducer.processBitmap` — SlamLite visual odometry (Harris-corner
 * detection + pyramidal optical flow) and DA2 monocular depth (CNN forward pass) — run
 * unconditionally on every single camera frame, with no rate control at all. Since
 * `processBitmap` runs on one sequential coroutine per camera frame, an over-budget Core
 * pass delays every later stage of that same frame, including hand-tracking submission —
 * this is a plausible direct cause of visible lag between real movement and rendered result.
 *
 * Solution: same shed/recover EMA mechanism as [ModelBudgetManager], generalised to an
 * ordered list of named channels (first = highest priority, kept most eager; last = lowest
 * priority, shed first). No channel is ever fully disabled — `everyN` is capped at [maxEvery],
 * so each channel still runs periodically regardless of load, and each channel already
 * exposes its last output via its own `@Volatile var last...` field, so skipped frames read
 * as "stale but recent" for free.
 *
 * ## Usage
 *
 * ```kotlin
 * val depthBudget = DepthChannelBudget()
 * val ids = listOf("da2", "slam")   // da2 first: also feeds hand-landmark Z correction
 *
 * val decision = depthBudget.tick(ids)
 * val slamMs = if (decision["slam"] == true) timed { spatialLayer.processBitmap(bitmap) } else 0f
 * val da2Ms  = if (decision["da2"]  == true) timed { spatialLayer.fusedDepth.processAuxSources(...) } else 0f
 *
 * // Report every id every frame, 0f for skipped channels — same convention as
 * // ModelBudgetManager.reportDispatch, so a skipped channel's EMA decays correctly
 * // instead of staying frozen at its last-measured cost.
 * depthBudget.report("slam", slamMs)
 * depthBudget.report("da2", da2Ms)
 * ```
 *
 * Thread safety: not thread-safe. Call from a single camera-frame collector coroutine.
 */
class DepthChannelBudget(
    private val budgetMs: Float = 25f,
    private val recoveryRatio: Float = 0.70f,
    private val emaAlpha: Float = 0.15f,
    private val maxEvery: Int = 4
) {
    private val ema      = HashMap<String, Float>()
    private val everyN   = HashMap<String, Int>()
    private val counter  = HashMap<String, Int>()
    private var recoveryWindow = 0

    /**
     * Returns, for each id in [channelIds], whether that channel should run this frame.
     * Must be called once per camera frame, before deciding which channels to invoke.
     */
    fun tick(channelIds: List<String>): Map<String, Boolean> {
        for (id in channelIds) {
            ema.putIfAbsent(id, 0f)
            everyN.putIfAbsent(id, 1)
            counter.putIfAbsent(id, 0)
        }

        var total = 0f
        for (id in channelIds) total += ema.getValue(id)

        if (total > budgetMs) {
            // Shed the lowest-priority channel that isn't already at maxEvery.
            for (id in channelIds.asReversed()) {
                val cur = everyN.getValue(id)
                if (cur < maxEvery) {
                    everyN[id] = cur + 1
                    break
                }
            }
            recoveryWindow = 0
        } else if (total < budgetMs * recoveryRatio) {
            // Recover one step per 30 frames, highest-priority-first.
            recoveryWindow++
            if (recoveryWindow >= 30) {
                recoveryWindow = 0
                for (id in channelIds) {
                    val cur = everyN.getValue(id)
                    if (cur > 1) {
                        everyN[id] = cur - 1
                        break
                    }
                }
            }
        }

        val decisions = LinkedHashMap<String, Boolean>()
        for (id in channelIds) {
            val n = everyN.getValue(id)
            var c = counter.getValue(id) - 1
            val run = c <= 0
            if (run) c = n
            counter[id] = c
            decisions[id] = run
        }
        return decisions
    }

    /**
     * Report measured wall-clock time for a channel this frame. Pass 0f for a channel
     * skipped this frame (per [tick]'s decision) — same convention as
     * [ModelBudgetManager.reportDispatch] — so its EMA decays toward zero instead of
     * staying frozen at its last-measured cost.
     */
    fun report(channelId: String, ms: Float) {
        val prev = ema.getValue(channelId)
        ema[channelId] = prev * (1f - emaAlpha) + ms * emaAlpha
    }

    /** Current throttle rate for a channel (1 = every frame, 2 = every other, …). */
    fun everyN(channelId: String): Int = everyN[channelId] ?: 1

    /** Total combined dispatch EMA across all tracked channels. */
    val totalEma: Float get() = ema.values.sum()

    /** Reset all state — call when the depth engine is fully restarted. */
    fun reset() {
        ema.clear(); everyN.clear(); counter.clear()
        recoveryWindow = 0
    }
}
