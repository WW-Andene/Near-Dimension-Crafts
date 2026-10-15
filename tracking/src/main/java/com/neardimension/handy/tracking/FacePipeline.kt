package com.arhand.tracking

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * Wraps MediaPipe FaceLandmarker (Tasks SDK) and exposes:
 *
 *   1. **478 smoothed face landmarks** via [processed] — same [Landmark] type as hand/body.
 *   2. **[FaceExpressions]** derived from landmark geometry — blink, brow raise, jaw open,
 *      and mouth corner positions. These map directly to ARKit / VRM blendshape targets,
 *      making it straightforward to drive facial animation on any compatible rig.
 *
 * The model file `face_landmarker.task` must be included in `assets/`.
 * The Tasks SDK blendshape model produces 478 landmarks including the iris
 * points (indices 468–477), which are used here for gaze estimation.
 *
 * Usage:
 * ```kotlin
 * val facePipeline = FacePipeline()
 * facePipeline.init(context)
 *
 * // Each camera frame:
 * facePipeline.detect(bitmap, timestampMs)
 *
 * // Collect results:
 * facePipeline.processed.collect { landmarks -> ... }
 * facePipeline.expressions.collect { expr -> ... }
 *
 * // On teardown:
 * facePipeline.close()
 * ```
 */
class FacePipeline {

    companion object {
        const val MODEL_ASSET = "face_landmarker.task"

        // OneEuroFilter parameters — face landmarks need low latency for
        // expression responsiveness; slightly higher beta than body
        const val OEF_MIN_CUTOFF_XY = 0.6f
        const val OEF_BETA_XY       = 1.2f
        const val OEF_MIN_CUTOFF_Z  = 0.8f
        const val OEF_BETA_Z        = 1.5f

        const val GRACE_FRAMES = 6

        // Blink threshold: eye-openness ratio below this = closed
        const val BLINK_THRESHOLD = 0.2f
        // Jaw-open threshold: mouth-open ratio above this = open
        const val JAW_OPEN_THRESHOLD = 0.08f
        // Brow-raise threshold: brow elevation delta above this = raised
        const val BROW_RAISE_THRESHOLD = 0.02f
    }

    private var landmarker: FaceLandmarker? = null

    private val oef = OneEuroFilter(OEF_MIN_CUTOFF_XY, OEF_BETA_XY, OEF_MIN_CUTOFF_Z, OEF_BETA_Z)
    private var graceCounts = 0
    private var lastAccepted: FaceLandmarks? = null

    private val _processed   = MutableStateFlow<FaceLandmarks?>(null)
    private val _expressions = MutableStateFlow(FaceExpressions())

    val processed:   StateFlow<FaceLandmarks?>   = _processed
    val expressions: StateFlow<FaceExpressions>  = _expressions

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Initialize the FaceLandmarker. Call once before [detect].
     * GPU delegate used; falls back to CPU if unavailable.
     */
    fun init(context: Context) {

        // FaceLandmarker.createFromOptions() is where delegate init actually occurs;
        // BaseOptions.build() alone does not probe delegate availability.
        val delegate = listOf(Delegate.GPU, Delegate.CPU).firstNotNullOfOrNull { d ->
            try {
                val opts = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(d).build()
                FaceLandmarker.createFromOptions(
                    context,
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(opts)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumFaces(1)
                        .setMinFaceDetectionConfidence(0.5f)
                        .setMinFacePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setOutputFaceBlendshapes(false)
                        .setOutputFacialTransformationMatrixes(false)
                        .setResultListener { result, _ -> onResult(result) }
                        .build()
                ).also { landmarker = it }
                d   // return the working delegate
            } catch (_: Exception) { null }
        }
        if (delegate == null) {
            android.util.Log.e("FacePipeline", "All delegates failed — face tracking unavailable")
        }
    }

    /** Submit a frame for async detection. Safe to call from camera callback thread. */
    fun detect(bitmap: Bitmap, timestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker?.detectAsync(mpImage, timestampMs)
    }

    /** Release resources and reset state. */
    fun close() {
        landmarker?.close()
        landmarker = null
        oef.reset()
        lastAccepted = null
        graceCounts = 0
        _processed.value = null
        _expressions.value = FaceExpressions()
    }

    // ─── Result handling ──────────────────────────────────────────────────────

    private fun onResult(result: FaceLandmarkerResult) {
        val rawList = result.faceLandmarks()

        if (rawList.isEmpty()) {
            graceCounts++
            if (graceCounts >= GRACE_FRAMES) {
                oef.reset()
                lastAccepted = null
                _processed.value = null
                _expressions.value = FaceExpressions()
            } else {
                _processed.value = lastAccepted
            }
            return
        }

        graceCounts = 0
        val now = System.currentTimeMillis()

        val raw: FaceLandmarks = rawList[0].map { lm ->
            Landmark(x = lm.x(), y = lm.y(), z = lm.z())
        }

        val smoothed = oef.smooth(raw, now)
        lastAccepted = smoothed
        _processed.value = smoothed
        _expressions.value = deriveExpressions(smoothed)
    }

    // ─── Expression derivation ────────────────────────────────────────────────

    /**
     * Derive [FaceExpressions] from smoothed landmark geometry.
     *
     * All values are in [0,1] representing expression intensity:
     *   - 0 = fully neutral / closed
     *   - 1 = fully expressed / open
     *
     * These map directly to ARKit blendshape names and VRM expression presets,
     * so they can be forwarded to [OscStreamer] or used to drive a facial rig.
     */
    private fun deriveExpressions(lms: FaceLandmarks): FaceExpressions {
        if (lms.size < FL.COUNT) return FaceExpressions()

        // Eye openness — vertical distance between upper and lower lid,
        // normalised by the horizontal eye width for scale invariance
        val leftEyeOpen  = eyeOpenness(lms, FL.LEFT_EYE_TOP,  FL.LEFT_EYE_BOTTOM,
                                             FL.LEFT_EYE_OUTER, 33)
        val rightEyeOpen = eyeOpenness(lms, FL.RIGHT_EYE_TOP, FL.RIGHT_EYE_BOTTOM,
                                             FL.RIGHT_EYE_OUTER, 263)

        val leftBlink  = (1f - (leftEyeOpen  / BLINK_THRESHOLD).coerceIn(0f, 1f))
        val rightBlink = (1f - (rightEyeOpen / BLINK_THRESHOLD).coerceIn(0f, 1f))

        // Jaw openness — vertical distance between upper and lower lip
        // normalised by face height (nose-to-chin distance)
        val jawOpen = jawOpenness(lms)

        // Brow raise — average upward shift of brow landmarks relative to
        // their neutral position (approximated by distance to eye outer corner)
        val leftBrowRaise  = browRaise(lms, FL.LEFT_BROW_INNER,  FL.LEFT_BROW_OUTER,  FL.LEFT_EYE_OUTER)
        val rightBrowRaise = browRaise(lms, FL.RIGHT_BROW_INNER, FL.RIGHT_BROW_OUTER, FL.RIGHT_EYE_OUTER)

        // Mouth corners — lateral stretch indicates smile
        val mouthStretch = mouthCornerStretch(lms)

        // FACE-2 — Iris gaze: compute iris centre position relative to eye orbit.
        // Returns (horizontal, vertical) in [-1, 1] per eye.
        val (leftGazeH, leftGazeV)   = irisGaze(lms,
            irisIdx  = FL.LEFT_IRIS_CENTER,
            outerIdx = FL.LEFT_EYE_OUTER,
            innerIdx = FL.LEFT_EYE_INNER,
            topIdx   = FL.LEFT_EYE_TOP,
            bottomIdx= FL.LEFT_EYE_BOTTOM
        )
        val (rightGazeH, rightGazeV) = irisGaze(lms,
            irisIdx  = FL.RIGHT_IRIS_CENTER,
            outerIdx = FL.RIGHT_EYE_OUTER,
            innerIdx = FL.RIGHT_EYE_INNER,
            topIdx   = FL.RIGHT_EYE_TOP,
            bottomIdx= FL.RIGHT_EYE_BOTTOM
        )

        return FaceExpressions(
            leftBlink       = leftBlink,
            rightBlink      = rightBlink,
            jawOpen         = jawOpen,
            leftBrowRaise   = leftBrowRaise,
            rightBrowRaise  = rightBrowRaise,
            mouthSmile      = mouthStretch,
            leftGazeH       = leftGazeH,
            leftGazeV       = leftGazeV,
            rightGazeH      = rightGazeH,
            rightGazeV      = rightGazeV
        )
    }

    private fun eyeOpenness(
        lms: FaceLandmarks,
        topIdx: Int, bottomIdx: Int,
        outerIdx: Int, innerIdx: Int
    ): Float {
        val vertical   = abs(lms[topIdx].y - lms[bottomIdx].y)
        val horizontal = abs(lms[outerIdx].x - lms[innerIdx].x).coerceAtLeast(1e-4f)
        return vertical / horizontal
    }

    private fun jawOpenness(lms: FaceLandmarks): Float {
        val mouthOpen  = abs(lms[FL.UPPER_LIP_TOP].y - lms[FL.LOWER_LIP_BOTTOM].y)
        val faceHeight = abs(lms[FL.NOSE_TIP].y      - lms[FL.CHIN].y).coerceAtLeast(1e-4f)
        return (mouthOpen / faceHeight / JAW_OPEN_THRESHOLD).coerceIn(0f, 1f)
    }

    private fun browRaise(
        lms: FaceLandmarks,
        innerIdx: Int, outerIdx: Int, eyeOuterIdx: Int
    ): Float {
        // Raised brows move toward the top of the frame (lower y in normalised coords)
        val browY = (lms[innerIdx].y + lms[outerIdx].y) * 0.5f
        val eyeY  = lms[eyeOuterIdx].y
        val delta = eyeY - browY   // positive = brow above eye = raised
        return ((delta - BROW_RAISE_THRESHOLD) / BROW_RAISE_THRESHOLD).coerceIn(0f, 1f)
    }

    private fun mouthCornerStretch(lms: FaceLandmarks): Float {
        val stretch  = abs(lms[FL.MOUTH_LEFT_CORNER].x - lms[FL.MOUTH_RIGHT_CORNER].x)
        val faceWidth = abs(lms[FL.LEFT_EYE_OUTER].x  - lms[FL.RIGHT_EYE_OUTER].x).coerceAtLeast(1e-4f)
        // Smile stretches mouth wider relative to face width; 0.6 is rough neutral ratio
        return ((stretch / faceWidth - 0.6f) / 0.25f).coerceIn(0f, 1f)
    }

    /**
     * FACE-2 — Compute iris gaze direction for one eye.
     *
     * The iris centre position is normalised relative to the eye orbit bounding box:
     *   horizontal = (iris.x - innerCorner.x) / eyeWidth  → mapped to [-1, +1]
     *   vertical   = (iris.y - topLid.y)      / eyeHeight → mapped to [-1, +1]
     *
     * Returns (horizontalGaze, verticalGaze) each in [-1, 1]:
     *   H: -1 = full left, 0 = centre, +1 = full right
     *   V: -1 = full up,   0 = centre, +1 = full down
     *
     * When the eye is fully closed (vertical eye height < threshold) returns (0, 0)
     * to avoid undefined gaze values during blink.
     */
    private fun irisGaze(
        lms:       FaceLandmarks,
        irisIdx:   Int,
        outerIdx:  Int,
        innerIdx:  Int,
        topIdx:    Int,
        bottomIdx: Int
    ): Pair<Float, Float> {
        if (lms.size <= maxOf(irisIdx, outerIdx, innerIdx, topIdx, bottomIdx)) {
            return Pair(0f, 0f)
        }

        val iris   = lms[irisIdx]
        val outer  = lms[outerIdx]
        val inner  = lms[innerIdx]
        val top    = lms[topIdx]
        val bottom = lms[bottomIdx]

        val eyeWidth  = abs(outer.x - inner.x).coerceAtLeast(1e-4f)
        val eyeHeight = abs(bottom.y - top.y)

        // Skip gaze when eye is mostly closed — iris position is unreliable
        if (eyeHeight < eyeWidth * 0.08f) return Pair(0f, 0f)

        // Normalise iris position within eye orbit: 0 = inner corner, 1 = outer corner
        val hNorm = (iris.x - inner.x) / eyeWidth   // [0, 1] range, 0.5 = centre
        val vNorm = (iris.y - top.y)   / eyeHeight  // [0, 1] range, 0.5 = centre

        // Map [0, 1] → [-1, +1] with centre at 0.5
        val h = ((hNorm - 0.5f) * 2f).coerceIn(-1f, 1f)
        val v = ((vNorm - 0.5f) * 2f).coerceIn(-1f, 1f)

        return Pair(h, v)
    }
}

// ─── FaceExpressions ─────────────────────────────────────────────────────────

/**
 * Per-frame facial expression intensities, all in [0,1].
 *
 * Maps directly to:
 *   - **ARKit**: `eyeBlinkLeft`, `eyeBlinkRight`, `jawOpen`, `browInnerUp`,
 *     `browOuterUpLeft`, `browOuterUpRight`, `mouthSmileLeft/Right`
 *   - **VRM**: `Blink_L`, `Blink_R`, `O` (jaw), `Angry`/`Fun` (brow/smile presets)
 *   - **OSC**: forwarded as `/face/<name>` float messages when streaming
 */
data class FaceExpressions(
    /** Left eye blink intensity. 0 = open, 1 = fully closed. */
    val leftBlink:      Float = 0f,
    /** Right eye blink intensity. 0 = open, 1 = fully closed. */
    val rightBlink:     Float = 0f,
    /** Jaw open intensity. 0 = closed, 1 = fully open. */
    val jawOpen:        Float = 0f,
    /** Left brow raise intensity. 0 = neutral, 1 = fully raised. */
    val leftBrowRaise:  Float = 0f,
    /** Right brow raise intensity. 0 = neutral, 1 = fully raised. */
    val rightBrowRaise: Float = 0f,
    /** Mouth smile intensity. 0 = neutral, 1 = broad smile. */
    val mouthSmile:     Float = 0f,

    // ── FACE-2: Iris gaze ────────────────────────────────────────────────────
    /** Left eye horizontal gaze. -1 = looking left, 0 = centre, +1 = looking right. */
    val leftGazeH:      Float = 0f,
    /** Left eye vertical gaze. -1 = looking up, 0 = centre, +1 = looking down. */
    val leftGazeV:      Float = 0f,
    /** Right eye horizontal gaze. -1 = looking left, 0 = centre, +1 = looking right. */
    val rightGazeH:     Float = 0f,
    /** Right eye vertical gaze. -1 = looking up, 0 = centre, +1 = looking down. */
    val rightGazeV:     Float = 0f
)
