package com.arhand.tracking

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps MediaPipe PoseLandmarker (Tasks SDK) and applies the same
 * OneEuroFilter smoothing used by [HandPipeline].
 *
 * Uses the same [Landmark] type as hand tracking — normalized x/y in [0,1],
 * relative z depth. The 33-point MediaPipe pose topology is defined in [PL].
 *
 * The model file `pose_landmarker_lite.task` must be included in `assets/`.
 * Lite model: ~4MB, ~15ms inference on mid-range devices, sufficient for
 * full-body retargeting. Swap to `pose_landmarker_full.task` for higher accuracy.
 *
 * Usage:
 * ```kotlin
 * val bodyPipeline = BodyPipeline()
 * bodyPipeline.init(context)
 *
 * // Each camera frame (same Bitmap fed to HandTracker):
 * bodyPipeline.detect(bitmap, timestampMs)
 *
 * // Collect results:
 * bodyPipeline.processed.collect { landmarks -> ... }
 *
 * // On teardown:
 * bodyPipeline.close()
 * ```
 *
 * Thread safety: [detect] is called from the camera callback thread.
 * Results arrive on the same thread via the MediaPipe callback; [processed]
 * is a [StateFlow] safe to collect from any coroutine.
 */
class BodyPipeline {

    companion object {
        const val MODEL_ASSET = "pose_landmarker_lite.task"

        // OneEuroFilter parameters — tuned for body joints (lower beta than hands
        // since body motion is slower and smoother)
        const val OEF_MIN_CUTOFF_XY = 0.5f
        const val OEF_BETA_XY       = 0.8f
        const val OEF_MIN_CUTOFF_Z  = 0.7f
        const val OEF_BETA_Z        = 1.0f

        // Grace period: keep last pose for this many frames after detection drops
        const val GRACE_FRAMES = 10
    }

    private var landmarker: PoseLandmarker? = null

    private val oef = OneEuroFilter(OEF_MIN_CUTOFF_XY, OEF_BETA_XY, OEF_MIN_CUTOFF_Z, OEF_BETA_Z)
    private var graceCounts = 0
    private var lastAccepted: PoseLandmarks? = null

    private val _processed = MutableStateFlow<PoseLandmarks?>(null)
    val processed: StateFlow<PoseLandmarks?> = _processed

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Initialize the PoseLandmarker. Call once, on any thread, before [detect].
     * Uses GPU delegate; falls back to CPU if unavailable.
     */
    fun init(context: Context) {

        val delegate = listOf(Delegate.GPU, Delegate.CPU).firstNotNullOfOrNull { d ->
            try {
                val opts = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(d).build()
                PoseLandmarker.createFromOptions(
                    context,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(opts)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumPoses(1)
                        .setMinPoseDetectionConfidence(0.5f)
                        .setMinPosePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setResultListener { result, _ -> onResult(result) }
                        .build()
                ).also { landmarker = it }
                d
            } catch (_: Exception) { null }
        }
        if (delegate == null) {
            android.util.Log.e("BodyPipeline", "All delegates failed — body tracking unavailable")
        }
    }

    /** Submit a frame for async detection. Safe to call from camera callback thread. */
    fun detect(bitmap: Bitmap, timestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker?.detectAsync(mpImage, timestampMs)
    }

    /** Release the MediaPipe landmarker and reset filter state. */
    fun close() {
        landmarker?.close()
        landmarker = null
        oef.reset()
        lastAccepted = null
        graceCounts = 0
        _processed.value = null
    }

    // ─── Result handling ──────────────────────────────────────────────────────

    private fun onResult(result: PoseLandmarkerResult) {
        val rawList = result.landmarks()

        if (rawList.isEmpty()) {
            graceCounts++
            if (graceCounts >= GRACE_FRAMES) {
                oef.reset()
                lastAccepted = null
                _processed.value = null
            } else {
                // Grace period — emit last accepted unchanged
                _processed.value = lastAccepted
            }
            return
        }

        graceCounts = 0
        val now = System.currentTimeMillis()

        // Convert MediaPipe NormalizedLandmark → our Landmark type
        val raw: PoseLandmarks = rawList[0].map { lm ->
            // A6 — populate visibility from MediaPipe landmark so BodyRetargeter
            // can gate per-joint visibility (BODY-4). Defaults to 1.0 in Landmark
            // constructor, so existing code that ignores visibility is unaffected.
            Landmark(
                x          = lm.x(),
                y          = lm.y(),
                z          = lm.z(),
                visibility = lm.visibility().orElse(1f)
            )
        }

        // Smooth with OneEuroFilter
        val smoothed = oef.smooth(raw, now)
        lastAccepted = smoothed
        _processed.value = smoothed
    }
}
