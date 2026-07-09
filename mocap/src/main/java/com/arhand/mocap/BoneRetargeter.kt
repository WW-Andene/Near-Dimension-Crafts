package com.arhand.mocap

import com.arhand.tracking.HandLandmarks
import com.arhand.tracking.LM
import com.arhand.util.Vec3
import com.arhand.tracking.landmarkToWorld
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Converts live [HandLandmarks] from MediaPipe into a joint rotation map suitable for
 * driving a rigged 3D asset.
 *
 * MediaPipe gives positions. Rigs need rotations. For each of the 16 hand joints
 * (wrist + 3 per finger × 5), [BoneRetargeter] computes the shortest-arc quaternion
 * from the asset's bind-pose bone direction to the live bone direction.
 *
 * Compatible rig layout: VRChat, Mixamo, Unity HumanoidRig, standard glTF skins.
 *
 * Usage:
 * ```kotlin
 * val retargeter = BoneRetargeter(bindPose)
 * val result = retargeter.retarget(landmarks, aspect, mirrorX)
 * // result.jointRotations: Map<Int, Quaternion>  — keyed by JOINT_* constants
 * // result.wristTransform: WristTransform        — root position + rotation
 * ```
 */
class BoneRetargeter(private val bindPose: BindPose) {

    // ─── Joint index constants ────────────────────────────────────────────────
    // Matches MediaPipe bone segment topology: (base landmark) → (tip landmark)
    companion object {

        // Wrist root
        const val JOINT_WRIST         = 0

        // Thumb  — 3 joints
        const val JOINT_THUMB_CMC     = 1   // WRIST      → THUMB_CMC
        const val JOINT_THUMB_MCP     = 2   // THUMB_CMC  → THUMB_MCP
        const val JOINT_THUMB_IP      = 3   // THUMB_MCP  → THUMB_IP

        // Index  — 3 joints
        const val JOINT_INDEX_MCP     = 4   // WRIST      → INDEX_MCP
        const val JOINT_INDEX_PIP     = 5   // INDEX_MCP  → INDEX_PIP
        const val JOINT_INDEX_DIP     = 6   // INDEX_PIP  → INDEX_DIP

        // Middle — 3 joints
        const val JOINT_MIDDLE_MCP    = 7
        const val JOINT_MIDDLE_PIP    = 8
        const val JOINT_MIDDLE_DIP    = 9

        // Ring   — 3 joints
        const val JOINT_RING_MCP      = 10
        const val JOINT_RING_PIP      = 11
        const val JOINT_RING_DIP      = 12

        // Pinky  — 3 joints
        const val JOINT_PINKY_MCP     = 13
        const val JOINT_PINKY_PIP     = 14
        const val JOINT_PINKY_DIP     = 15

        // Total joints driven
        const val JOINT_COUNT = 16

        // BODY-3 — Wrist anchor constants
        /** Minimum body wrist visibility to start blending toward body position. */
        const val BODY_WRIST_VIS_THRESHOLD = 0.6f
        /** Maximum blend weight toward body wrist (keeps hand anchor influence ≥ 30%). */
        const val BODY_WRIST_MAX_BLEND     = 0.70f

        /**
         * Bone segment definitions: each entry is (jointIndex, baseLandmarkIdx, tipLandmarkIdx).
         * Used by [retarget] to compute live bone directions.
         */
        val BONE_SEGMENTS = listOf(
            Triple(JOINT_THUMB_CMC,  LM.WRIST,       LM.THUMB_CMC),
            Triple(JOINT_THUMB_MCP,  LM.THUMB_CMC,   LM.THUMB_MCP),
            Triple(JOINT_THUMB_IP,   LM.THUMB_MCP,   LM.THUMB_IP),
            Triple(JOINT_INDEX_MCP,  LM.WRIST,       LM.INDEX_MCP),
            Triple(JOINT_INDEX_PIP,  LM.INDEX_MCP,   LM.INDEX_PIP),
            Triple(JOINT_INDEX_DIP,  LM.INDEX_PIP,   LM.INDEX_DIP),
            Triple(JOINT_MIDDLE_MCP, LM.WRIST,       LM.MIDDLE_MCP),
            Triple(JOINT_MIDDLE_PIP, LM.MIDDLE_MCP,  LM.MIDDLE_PIP),
            Triple(JOINT_MIDDLE_DIP, LM.MIDDLE_PIP,  LM.MIDDLE_DIP),
            Triple(JOINT_RING_MCP,   LM.WRIST,       LM.RING_MCP),
            Triple(JOINT_RING_PIP,   LM.RING_MCP,    LM.RING_PIP),
            Triple(JOINT_RING_DIP,   LM.RING_PIP,    LM.RING_DIP),
            Triple(JOINT_PINKY_MCP,  LM.WRIST,       LM.PINKY_MCP),
            Triple(JOINT_PINKY_PIP,  LM.PINKY_MCP,   LM.PINKY_PIP),
            Triple(JOINT_PINKY_DIP,  LM.PINKY_PIP,   LM.PINKY_DIP)
        )

        /**
         * Maps a MediaPipe hand landmark index (LM.*) to the joint slot index
         * (JOINT_*, 0..[JOINT_COUNT]-1) whose tip it drives. Used to translate
         * [BONE_SEGMENTS]' landmark-index space into the slot-index space that
         * `jointNodeIndices` (glTF skin joint order) and [JOINT_COUNT]-sized
         * arrays like `bonePalette` are keyed by — landmark indices and joint
         * slot indices are different spaces and must not be used interchangeably.
         */
        val LANDMARK_TO_JOINT_SLOT: Map<Int, Int> =
            BONE_SEGMENTS.associate { (jointIdx, _, tipIdx) -> tipIdx to jointIdx } + (LM.WRIST to JOINT_WRIST)

        /**
         * Build a symmetric bind pose where all fingers point straight up (+Y)
         * and the thumb points diagonally (+X, +Y). Used as the default bind pose
         * when loading an asset with no explicit hand skeleton.
         */
        fun symmetricBindPose(): BindPose {
            val up   = Vec3(0f, 1f, 0f)
            val diag = Vec3(0.5f, 0.866f, 0f)  // 30° from +Y toward +X

            val dirs = mutableMapOf<Int, Vec3>()
            dirs[JOINT_THUMB_CMC]  = diag
            dirs[JOINT_THUMB_MCP]  = diag
            dirs[JOINT_THUMB_IP]   = diag
            dirs[JOINT_INDEX_MCP]  = up
            dirs[JOINT_INDEX_PIP]  = up
            dirs[JOINT_INDEX_DIP]  = up
            dirs[JOINT_MIDDLE_MCP] = up
            dirs[JOINT_MIDDLE_PIP] = up
            dirs[JOINT_MIDDLE_DIP] = up
            dirs[JOINT_RING_MCP]   = up
            dirs[JOINT_RING_PIP]   = up
            dirs[JOINT_RING_DIP]   = up
            dirs[JOINT_PINKY_MCP]  = up
            dirs[JOINT_PINKY_PIP]  = up
            dirs[JOINT_PINKY_DIP]  = up

            // Wrist root sits at world origin in the default bind pose
            val wristPos = Vec3(0f, 0f, 0f)
            return BindPose(dirs, wristPos)
        }
    }

    /**
     * Retarget a single frame of [HandLandmarks] to joint rotations.
     *
     * @param lms            21-point landmark list from the tracking pipeline
     * @param aspect         Viewport aspect ratio (width / height)
     * @param mirrorX        True for front-facing camera
     * @param bodyWristHint  BODY-3 — World-space wrist position from [BodyRetargeter],
     *                       or null when body tracking is inactive. When provided and
     *                       body wrist visibility exceeds [BODY_WRIST_VIS_THRESHOLD],
     *                       the hand wrist root is blended toward the body wrist
     *                       position, eliminating the visible floating seam between
     *                       the hand and body skeletons.
     * @param bodyWristVis   Visibility score of the body wrist landmark (0–1).
     *                       Used as the blend weight: 0 = hand position, 1 = body position.
     * @return [RetargetResult] with all joint rotations and the wrist transform,
     *         or null if the landmark list is incomplete.
     */
    fun retarget(
        lms:           HandLandmarks,
        aspect:        Float,
        mirrorX:       Boolean,
        bodyWristHint: Vec3?  = null,
        bodyWristVis:  Float  = 0f,
        camAspect:     Float  = aspect
    ): RetargetResult? {
        if (lms.size < 21) return null

        // Convert all 21 landmarks to world-space Vec3
        val world: Array<Vec3> = Array(21) { i ->
            val lm = lms[i]
            val (wx, wy, wz) = landmarkToWorld(lm, aspect, mirrorX, camAspect)
            Vec3(wx, wy, wz)
        }

        val rotations = HashMap<Int, Quaternion>(JOINT_COUNT)

        for ((jointIdx, baseIdx, tipIdx) in BONE_SEGMENTS) {
            val liveDir  = (world[tipIdx] - world[baseIdx]).normalized()
            val bindDir  = bindPose.boneDirections[jointIdx]
                ?: Vec3(0f, 1f, 0f)   // fallback: +Y

            rotations[jointIdx] = shortestArcQuaternion(bindDir, liveDir)
        }

        // Wrist root transform
        // BODY-3 — Blend hand wrist toward body wrist when body tracking is active.
        // Weight = bodyWristVis, clamped to [0, BODY_WRIST_MAX_BLEND] so hand
        // tracking always retains at least (1 - BODY_WRIST_MAX_BLEND) influence.
        // This eliminates the floating seam between hand and body skeletons while
        // keeping the hand anchor responsive when body confidence is low.
        val handWristPos = world[LM.WRIST]
        val wristPos = if (bodyWristHint != null && bodyWristVis >= BODY_WRIST_VIS_THRESHOLD) {
            val blend = (bodyWristVis * BODY_WRIST_MAX_BLEND).coerceIn(0f, BODY_WRIST_MAX_BLEND)
            handWristPos.lerp(bodyWristHint, blend)
        } else {
            handWristPos
        }

        // Palm-plane wrist rotation — two-axis construction.
        //
        // Previous: shortest-arc from bind +Y to wrist→middleMCP direction.
        // Problem:  shortest-arc is under-constrained — it leaves roll (axial rotation
        //           around the pointing axis) undefined, so lateral hand rolls produce
        //           a visually incorrect wrist twist in the rig.
        //
        // Fix: build an orthonormal frame from two palm vectors:
        //   forward = wrist → middle MCP (primary pointing axis)
        //   right   = index MCP → pinky MCP (lateral palm axis)
        //   up      = cross(forward, right), re-orthogonalized
        // Then compute the quaternion that maps the bind-pose frame to this live frame.
        // This fully constrains all 3 rotational degrees of freedom including roll.
        //
        // Bind-pose frame (symmetricBindPose): forward = +Y, right = +X, up = +Z.
        val liveForward = (world[LM.MIDDLE_MCP] - world[LM.WRIST]).normalized()
        val liveRight   = (world[LM.PINKY_MCP]  - world[LM.INDEX_MCP]).normalized()
        val liveUp      = liveForward.cross(liveRight).normalized()
        // Re-orthogonalize right to ensure a clean frame
        val liveRightOrtho = liveUp.cross(liveForward).normalized()

        // Bind-pose axes (matches symmetricBindPose and most T-pose rigs)
        val bindForward = bindPose.boneDirections[JOINT_MIDDLE_MCP] ?: Vec3(0f, 1f, 0f)
        val bindRight   = Vec3(1f, 0f, 0f)
        val bindUp      = bindForward.cross(bindRight).normalized()

        // Quaternion from bind frame to live frame via two shortest-arc steps:
        //   q1: align forward axes
        //   q2: align right axes in the plane perpendicular to the aligned forward
        val q1 = shortestArcQuaternion(bindForward, liveForward)
        // Rotate bind right by q1 to get the intermediate right axis
        val bindRightRotated = q1.rotate(bindRight)
        val q2 = shortestArcQuaternion(bindRightRotated, liveRightOrtho)
        val wristRot = (q2 * q1).normalized()

        rotations[JOINT_WRIST] = wristRot

        // Build world-space joint positions from the landmark positions.
        // LiveMeshDeformer needs these to set the translation component of each bone matrix;
        // without them every non-wrist joint pivots around the world origin.
        val positions = HashMap<Int, Vec3>(JOINT_COUNT)
        positions[JOINT_WRIST] = wristPos
        for ((jointIdx, _, tipIdx) in BONE_SEGMENTS) {
            positions[jointIdx] = world[tipIdx]
        }

        return RetargetResult(
            jointRotations = rotations,
            wristTransform = WristTransform(position = wristPos, rotation = wristRot),
            jointPositions = positions
        )
    }

    // ─── Quaternion math ──────────────────────────────────────────────────────

    /**
     * Shortest-arc quaternion from [from] to [to] (both should be unit vectors).
     *
     * Uses the half-vector construction: axis = cross(from, to), w = 1 + dot(from, to),
     * then normalise. Handles the anti-parallel edge case (180° rotation) by picking
     * a perpendicular axis.
     */
    // Rotate a vector by a unit quaternion: v' = q * (0,v) * q⁻¹
    // Used by the palm-plane wrist rotation to transform the bind right axis.
    private fun Quaternion.rotate(v: Vec3): Vec3 {
        // Optimized Rodrigues: v' = v + 2w(q×v) + 2(q×(q×v))
        val qv = Vec3(x, y, z)
        val uv = qv.cross(v)
        val uuv = qv.cross(uv)
        return Vec3(
            v.x + 2f * (w * uv.x + uuv.x),
            v.y + 2f * (w * uv.y + uuv.y),
            v.z + 2f * (w * uv.z + uuv.z)
        )
    }

    private fun shortestArcQuaternion(from: Vec3, to: Vec3): Quaternion {
        val dot = from.dot(to).coerceIn(-1f, 1f)

        // Anti-parallel: 180° rotation — pick a perpendicular axis
        if (dot < -0.9999f) {
            val perp = if (Math.abs(from.x) < 0.9f)
                Vec3(1f, 0f, 0f).cross(from).normalized()
            else
                Vec3(0f, 1f, 0f).cross(from).normalized()
            return Quaternion(perp.x, perp.y, perp.z, 0f).normalized()
        }

        // Same direction — identity
        if (dot > 0.9999f) return Quaternion(0f, 0f, 0f, 1f)

        val axis = from.cross(to)   // not yet normalised — length = sin(angle)
        val w    = 1f + dot         // = 2 * cos²(angle/2)
        return Quaternion(axis.x, axis.y, axis.z, w).normalized()
    }
}

// ─── Supporting data classes ─────────────────────────────────────────────────

/**
 * Rest-pose bone directions for each joint index.
 * Loaded from the asset's glTF skin inverse bind matrices via [AssetLoader],
 * or synthesised by [BoneRetargeter.symmetricBindPose].
 */
data class BindPose(
    /** Map from [BoneRetargeter] JOINT_* constant → unit direction vector in asset local space. */
    val boneDirections: Map<Int, Vec3>,
    /** Wrist joint position in asset local space (usually origin). */
    val wristPosition: Vec3
)

/**
 * Per-frame output of [BoneRetargeter.retarget].
 */
data class RetargetResult(
    /** Joint index → quaternion rotation. Apply to asset joint nodes each frame. */
    val jointRotations: Map<Int, Quaternion>,
    /** Wrist root position + rotation in world space. */
    val wristTransform: WristTransform,
    /**
     * World-space position of each joint in the current frame, keyed by JOINT_* constants.
     * Required by [com.arhand.render.LiveMeshDeformer] to build correct LBS bone matrices —
     * without per-joint translation, non-wrist joints all pivot around the world origin.
     */
    val jointPositions: Map<Int, Vec3> = emptyMap()
)

// ─── ARCH-1: Unified full-body frame ─────────────────────────────────────────

/**
 * ARCH-1 — One data structure representing the complete tracked performance for a frame.
 *
 * Replaces the three independent streams (hand [RetargetResult], [BodyRetargetResult],
 * [com.arhand.tracking.FaceExpressions]) that previously flowed through separate paths
 * to [com.arhand.mocap.OscStreamer], [MotionRecorder], and [com.arhand.export.GltfAnimationExporter].
 *
 * Any field may be null if that pipeline is inactive or produced no result this frame.
 * Consumers check nullability and degrade gracefully — e.g. OSC skips body bones when
 * [body] is null, BVH skips face channels when [face] is null.
 */
data class FullBodyRetargetResult(
    /** Unix timestamp of this frame in milliseconds. */
    val timestamp:       Long,
    /** Primary hand retarget result (slot 0, typically right hand). Null when hand absent. */
    val handPrimary:     RetargetResult?,
    /** Secondary hand retarget result (slot 1, typically left hand). Null when absent. */
    val handSecondary:   RetargetResult?,
    /** Body joint rotations from [com.arhand.mocap.BodyRetargeter]. Null when body tracking off. */
    val body:            BodyRetargetResult?,
    /** Face expression intensities from [com.arhand.tracking.FacePipeline]. Null when face tracking off. */
    val face:            com.arhand.tracking.FaceExpressions?,
    /** Mean tracking confidence across all active pipelines (0–1). */
    val frameConfidence: Float,

    // ── Spatial layer fields (always populated when SpatialLayer is running) ──

    /**
     * True when ARCore is in TRACKING state and positions in this frame are
     * metric world-space (metres, gravity-aligned Y-up). False when positions
     * are camera-relative estimates (MediaPipe world landmarks, approximately metric).
     *
     * Consumers (OSC, BVH, rendering) can use this to tag output quality or
     * choose between world-space and camera-space coordinate systems.
     */
    val metricGrounded:  Boolean = false,

    /**
     * ARCore camera world position (metres) at the time this frame was captured.
     * NaN when [metricGrounded] is false. Used by receivers to reconstruct absolute
     * hand position in the scene coordinate frame.
     */
    val cameraWorldX:    Float   = Float.NaN,
    val cameraWorldY:    Float   = Float.NaN,
    val cameraWorldZ:    Float   = Float.NaN,

    /**
     * Depth sensing confidence for this frame (0–1).
     * 1.0 = ARCore high-confidence points dominate.
     * 0.0 = no depth data available.
     */
    val depthConfidence: Float   = 0f
)

data class WristTransform(
    val position: Vec3,
    val rotation: Quaternion
)

/**
 * Minimal quaternion (x, y, z, w) with the operations needed for retargeting.
 */
data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float) {

    fun length(): Float = sqrt(x * x + y * y + z * z + w * w)

    fun normalized(): Quaternion {
        val l = length()
        return if (l < 1e-8f) Quaternion(0f, 0f, 0f, 1f)
        else Quaternion(x / l, y / l, z / l, w / l)
    }

    /** Quaternion multiplication: this × other (Hamilton product). */
    operator fun times(o: Quaternion) = Quaternion(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z
    )

    /** Spherical linear interpolation toward [target] by factor [t]. */
    fun slerp(target: Quaternion, t: Float): Quaternion {
        var dot = x * target.x + y * target.y + z * target.z + w * target.w
        val tq = if (dot < 0f) {
            dot = -dot
            Quaternion(-target.x, -target.y, -target.z, -target.w)
        } else target

        return if (dot > 0.9995f) {
            // Near-identical — linear interpolate and normalise
            Quaternion(
                x + t * (tq.x - x),
                y + t * (tq.y - y),
                z + t * (tq.z - z),
                w + t * (tq.w - w)
            ).normalized()
        } else {
            val theta0 = acos(dot)
            val theta  = theta0 * t
            val sinT0  = kotlin.math.sin(theta0)
            val sinT   = kotlin.math.sin(theta)
            val s0 = kotlin.math.cos(theta) - dot * sinT / sinT0
            val s1 = sinT / sinT0
            Quaternion(
                s0 * x + s1 * tq.x,
                s0 * y + s1 * tq.y,
                s0 * z + s1 * tq.z,
                s0 * w + s1 * tq.w
            ).normalized()
        }
    }

    /**
     * Convert to a column-major 4×4 rotation matrix (FloatArray(16)) for GPU upload.
     * Compatible with OpenGL ES `glUniformMatrix4fv`.
     */
    fun toMatrix(): FloatArray {
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2
        return floatArrayOf(
            1f - (yy + zz),  xy + wz,         xz - wy,         0f,
            xy - wz,         1f - (xx + zz),  yz + wx,         0f,
            xz + wy,         yz - wx,         1f - (xx + yy),  0f,
            0f,              0f,              0f,              1f
        )
    }

    companion object {
        val IDENTITY = Quaternion(0f, 0f, 0f, 1f)
    }
}
