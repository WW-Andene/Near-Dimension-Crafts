package com.arhand.mocap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Gap 3 — OSC connection health monitor.
 *
 * Tracks per-session streaming statistics and exposes them via [StateFlow<OscHealthState>]
 * for display in the HUD and settings screen. Because OSC/UDP has no acknowledgement,
 * "dropped" packets are estimated from send failures (socket exceptions), not from
 * receiver feedback — true packet loss on a well-configured LAN is near-zero.
 *
 * Metrics exposed:
 *  - [OscHealthState.sentFrames]   — cumulative frames sent this session
 *  - [OscHealthState.droppedFrames]— cumulative send failures (socket errors)
 *  - [OscHealthState.frameRateFps] — rolling 1-second send rate
 *  - [OscHealthState.lastSentMs]   — epoch-ms of last successful send
 *  - [OscHealthState.isHealthy]    — true when frames were sent in the last 500ms
 *
 * Thread safety: all updates happen from the OscStreamer sender coroutine
 * (single writer). StateFlow reads are safe from any thread.
 */
class OscHealthMonitor {

    private val _state = MutableStateFlow(OscHealthState())
    val state: StateFlow<OscHealthState> = _state

    // Rolling rate window
    private var windowStart    = System.currentTimeMillis()
    private var windowFrames   = 0
    private var totalSent      = 0L
    private var totalDropped   = 0L

    /** Call after each successfully transmitted OSC bundle. */
    fun onFrameSent() {
        totalSent++
        windowFrames++
        val now = System.currentTimeMillis()
        val elapsed = now - windowStart
        val fps = if (elapsed >= 500L) {
            val rate = windowFrames / (elapsed / 1000f)
            windowFrames = 0
            windowStart  = now
            rate
        } else {
            _state.value.frameRateFps  // keep previous value mid-window
        }
        _state.value = OscHealthState(
            sentFrames    = totalSent,
            droppedFrames = totalDropped,
            frameRateFps  = fps,
            lastSentMs    = now,
            isHealthy     = true
        )
    }

    /** Call when a send attempt throws a socket exception. */
    fun onFrameDropped() {
        totalDropped++
        _state.value = _state.value.copy(droppedFrames = totalDropped)
    }

    /** Reset all counters — call when streaming stops or host changes. */
    fun reset() {
        totalSent    = 0L
        totalDropped = 0L
        windowFrames = 0
        windowStart  = System.currentTimeMillis()
        _state.value = OscHealthState()
    }

    /** Snapshot current state for display — reflects stale indicator if no frames sent recently. */
    fun tick() {
        val now = System.currentTimeMillis()
        if (_state.value.isHealthy && now - _state.value.lastSentMs > 500L) {
            _state.value = _state.value.copy(isHealthy = false, frameRateFps = 0f)
        }
    }
}

/**
 * Immutable snapshot of OSC streaming health.
 *
 * @param sentFrames    Frames successfully dispatched this session.
 * @param droppedFrames Socket-level send failures this session.
 * @param frameRateFps  Smoothed send rate over the last 0.5s window.
 * @param lastSentMs    Epoch-ms of the most recent successful send. 0 = never sent.
 * @param isHealthy     True when a frame was sent in the last 500ms.
 */
data class OscHealthState(
    val sentFrames:    Long    = 0L,
    val droppedFrames: Long    = 0L,
    val frameRateFps:  Float   = 0f,
    val lastSentMs:    Long    = 0L,
    val isHealthy:     Boolean = false
) {
    /** Drop rate as a fraction [0,1]. 0 = no drops. */
    val dropRate: Float get() = if (sentFrames + droppedFrames > 0)
        droppedFrames.toFloat() / (sentFrames + droppedFrames) else 0f
}
