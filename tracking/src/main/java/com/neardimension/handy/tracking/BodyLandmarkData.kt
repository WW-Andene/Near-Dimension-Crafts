package com.arhand.tracking

/**
 * Landmark types and named index constants for MediaPipe Pose Landmarker (33 points)
 * and FaceMesh (478 points — Tasks SDK blendshape model).
 *
 * Both reuse the existing [Landmark] data class (x, y, z, inferred) from LandmarkData.kt.
 */

// ─── Pose ─────────────────────────────────────────────────────────────────────

/** 33-landmark body pose, one entry per MediaPipe PoseLandmarker index. */
typealias PoseLandmarks = List<Landmark>

/** Named pose landmark indices — matches MediaPipe PoseLandmarker topology. */
object PL {
    const val NOSE              = 0
    const val LEFT_EYE_INNER   = 1
    const val LEFT_EYE         = 2
    const val LEFT_EYE_OUTER   = 3
    const val RIGHT_EYE_INNER  = 4
    const val RIGHT_EYE        = 5
    const val RIGHT_EYE_OUTER  = 6
    const val LEFT_EAR         = 7
    const val RIGHT_EAR        = 8
    const val MOUTH_LEFT       = 9
    const val MOUTH_RIGHT      = 10
    const val LEFT_SHOULDER    = 11
    const val RIGHT_SHOULDER   = 12
    const val LEFT_ELBOW       = 13
    const val RIGHT_ELBOW      = 14
    const val LEFT_WRIST       = 15
    const val RIGHT_WRIST      = 16
    const val LEFT_PINKY       = 17
    const val RIGHT_PINKY      = 18
    const val LEFT_INDEX       = 19
    const val RIGHT_INDEX      = 20
    const val LEFT_THUMB       = 21
    const val RIGHT_THUMB      = 22
    const val LEFT_HIP         = 23
    const val RIGHT_HIP        = 24
    const val LEFT_KNEE        = 25
    const val RIGHT_KNEE       = 26
    const val LEFT_ANKLE       = 27
    const val RIGHT_ANKLE      = 28
    const val LEFT_HEEL        = 29
    const val RIGHT_HEEL       = 30
    const val LEFT_FOOT_INDEX  = 31
    const val RIGHT_FOOT_INDEX = 32

    const val COUNT = 33

    /**
     * Skeleton connection pairs for rendering — mirrors the MediaPipe pose connections.
     * Each pair is (from, to) using PL.* constants.
     */
    val CONNECTIONS = listOf(
        // Face
        NOSE to LEFT_EYE_INNER, LEFT_EYE_INNER to LEFT_EYE, LEFT_EYE to LEFT_EYE_OUTER,
        LEFT_EYE_OUTER to LEFT_EAR,
        NOSE to RIGHT_EYE_INNER, RIGHT_EYE_INNER to RIGHT_EYE, RIGHT_EYE to RIGHT_EYE_OUTER,
        RIGHT_EYE_OUTER to RIGHT_EAR,
        MOUTH_LEFT to MOUTH_RIGHT,
        // Torso
        LEFT_SHOULDER to RIGHT_SHOULDER,
        LEFT_SHOULDER to LEFT_HIP, RIGHT_SHOULDER to RIGHT_HIP,
        LEFT_HIP to RIGHT_HIP,
        // Arms
        LEFT_SHOULDER to LEFT_ELBOW, LEFT_ELBOW to LEFT_WRIST,
        RIGHT_SHOULDER to RIGHT_ELBOW, RIGHT_ELBOW to RIGHT_WRIST,
        // Hands
        LEFT_WRIST to LEFT_PINKY, LEFT_WRIST to LEFT_INDEX, LEFT_WRIST to LEFT_THUMB,
        RIGHT_WRIST to RIGHT_PINKY, RIGHT_WRIST to RIGHT_INDEX, RIGHT_WRIST to RIGHT_THUMB,
        // Legs
        LEFT_HIP to LEFT_KNEE, LEFT_KNEE to LEFT_ANKLE,
        RIGHT_HIP to RIGHT_KNEE, RIGHT_KNEE to RIGHT_ANKLE,
        // Feet
        LEFT_ANKLE to LEFT_HEEL, LEFT_HEEL to LEFT_FOOT_INDEX,
        RIGHT_ANKLE to RIGHT_HEEL, RIGHT_HEEL to RIGHT_FOOT_INDEX
    )
}

// ─── Face ─────────────────────────────────────────────────────────────────────

/** 478-landmark face mesh (MediaPipe FaceLandmarker Tasks SDK model). */
typealias FaceLandmarks = List<Landmark>

/**
 * Key facial landmark indices for expression mapping and rendering.
 * Full 478-point topology documented at:
 * https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/modules/face_geometry/data/canonical_face_model_uv_visualization.png
 */
object FL {
    const val COUNT = 478

    // ── Contour anchors (used for face orientation) ───────────────────────
    const val NOSE_TIP        = 1
    const val CHIN            = 152
    const val LEFT_EYE_OUTER  = 33
    const val RIGHT_EYE_OUTER = 263
    const val LEFT_MOUTH      = 61
    const val RIGHT_MOUTH     = 291

    // ── Eye landmarks ─────────────────────────────────────────────────────
    // Upper/lower lid midpoints — used for blink detection
    const val LEFT_EYE_TOP    = 159
    const val LEFT_EYE_BOTTOM = 145
    const val RIGHT_EYE_TOP   = 386
    const val RIGHT_EYE_BOTTOM= 374

    // ── Eyebrow landmarks ─────────────────────────────────────────────────
    const val LEFT_BROW_INNER  = 107
    const val LEFT_BROW_OUTER  = 70
    const val RIGHT_BROW_INNER = 336
    const val RIGHT_BROW_OUTER = 300

    // ── Mouth landmarks ───────────────────────────────────────────────────
    const val UPPER_LIP_TOP    = 13
    const val LOWER_LIP_BOTTOM = 14
    const val MOUTH_LEFT_CORNER= 61
    const val MOUTH_RIGHT_CORNER = 291

    // ── Jaw ───────────────────────────────────────────────────────────────
    const val JAW_LEFT   = 172
    const val JAW_RIGHT  = 397

    // ── Iris landmarks (FACE-2) ───────────────────────────────────────────────
    // MediaPipe 478-landmark model: indices 468–477 are iris points.
    // 468–471 = left iris ring; 468 = left iris centre (closest to centre of ring).
    // 473–476 = right iris ring; 473 = right iris centre.
    // Eye orbit corners used as the gaze normalisation reference frame:
    const val LEFT_IRIS_CENTER  = 468
    const val RIGHT_IRIS_CENTER = 473
    const val LEFT_EYE_INNER    = 133   // inner eye corner (nose side)
    const val RIGHT_EYE_INNER   = 362   // inner eye corner (nose side)
}

// ─── Unified full-body frame ──────────────────────────────────────────────────

/**
 * One processed frame from all active tracking sources.
 *
 * Any field may be null if that tracker is not enabled or produced no result this frame.
 * The hand slots from [HandPipeline] are kept separate — full-body capture feeds all
 * three sources simultaneously into [BoneRetargeter] and [OscStreamer].
 */
data class FullBodyFrame(
    val pose:  PoseLandmarks?,
    val face:  FaceLandmarks?,
    val leftHand:  List<Landmark>?,   // HandPipeline slot mapped to left hand
    val rightHand: List<Landmark>?,   // HandPipeline slot mapped to right hand
    /**
     * FACE-3 — Derived face expression intensities for the same frame.
     *
     * Populated in [com.arhand.ui.AppViewModel.ensureFullBodyCollector] from
     * [com.arhand.tracking.FacePipeline.expressions] when face tracking is active.
     * Allows [CompositeGestureClassifier.classify] to use the correct overload that
     * reads expression values rather than always returning [FaceModifier.NONE].
     */
    val expressions: FaceExpressions? = null
)
