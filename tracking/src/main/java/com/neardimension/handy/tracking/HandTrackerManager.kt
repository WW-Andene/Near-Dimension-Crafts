package com.arhand.tracking

import android.content.Context
import android.graphics.Bitmap

/**
 * F2 — Encapsulates [HandTracker] lifecycle: init, detect, parse, close.
 *
 * Extracted from [AppViewModel] to reduce ViewModel size and make the tracker
 * independently testable. Callers interact via [onResult] callback and the
 * [detect] / [close] surface.
 */
class HandTrackerManager(
    private val context: Context,
    private val onResult: (List<HandLandmarks>, Float, Long) -> Unit
) {

    private var tracker: HandTracker? = null

    /** The delegate actually selected after fallback probe. Exposed for HUD / logging. */
    val activeDelegate get() = tracker?.activeDelegate

    /**
     * Initialise the underlying [HandTracker].
     * Safe to call multiple times — subsequent calls are no-ops if already initialised.
     */
    fun init() {
        if (tracker != null) return
        tracker = HandTracker(context) { result, timestamp ->

            // (config change, camera restart), tracker may be null by the time we use it.
            val t = tracker ?: return@HandTracker
            val rawHands = t.parseResult(result)
            val conf = if (result.handedness().isNotEmpty())
                result.handedness()[0].firstOrNull()?.score() ?: 0.5f
            else 0f
            onResult(rawHands, conf, timestamp)
        }
        tracker!!.init()
    }

    /**
     * Submit [bitmap] for async landmark detection.
     * No-op if [init] has not been called yet.
     */
    fun detect(bitmap: Bitmap, timestampMs: Long) {
        tracker?.detect(bitmap, timestampMs)
    }

    /**
     * Release MediaPipe resources. Idempotent.
     */
    fun close() {
        tracker?.close()
        tracker = null
    }
}
