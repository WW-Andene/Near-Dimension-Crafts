package com.arhand.mocap

import com.arhand.tracking.PL
import com.arhand.tracking.PoseLandmarks
import com.arhand.util.Vec3

/**
 * BODY-1 — Converts live [PoseLandmarks] from [com.arhand.tracking.BodyPipeline]
 * into a map of [BodyJoint] rotations suitable for driving a full-body rigged asset.
 *
 * Mirrors the same shortest-arc-quaternion approach used by [BoneRetargeter] for hands.
 * Each body joint is computed from the direction vector between two MediaPipe pose
 * landmarks — the same geometric strategy, applied to the 33-point body topology.
 *
 * BODY-4 — Per-joint visibility gating with grace period.
 * MediaPipe provides a `visibility` score (0–1) per landmark. Joints whose driving
 * landmarks fall below [VISIBILITY_THRESHOLD] enter a grace period: they hold their
 * last confident rotation for up to [GRACE_FRAMES] frames before being dropped from
 * the result map entirely. This prevents leg joints snapping to garbage positions
 * when only the upper body is in frame.
 *
 * Pure function, no internal mutable state — [retarget] takes the previous grace/EMA
 * state explicitly ([BodyRetargeterState]) and returns the updated state alongside the
 * result, instead of holding it as instance fields. This class previously held that state
 * internally, which meant two callers sharing one instance would corrupt each other's
 * grace-period/EMA history — exactly the shape of `AppViewModel`'s two current call sites
 * (the live `SpatialFrameProducer` pipeline, and the dormant `ensureFullBodyCollector`,
 * both retargeting from the *same* `bodyRetargeter` instance). Each caller now owns and
 * threads its own state, so that can't happen even if both run.
 *
 * Output:
 * ```kotlin
 * var state = BodyRetargeterState.INITIAL
 * val (newState, result) = BodyRetargeter.retarget(poseLandmarks, state)
 * state = newState
 * // result?.joints:     Map<BodyJoint, Quaternion> — keyed by BodyJoint enum
 * // result?.wristLeft:  Vec3  — left wrist world position (for BODY-3 wrist anchor)
 * // result?.wristRight: Vec3  — right wrist world position
 * // result?.confidence: Float — mean visibility across all driven landmarks
 * ```
 */
object BodyRetargeter {

    /** BODY-4 — Visibility below this → joint enters grace period. */
    const val VISIBILITY_THRESHOLD = 0.5f

    /** BODY-4 — Hold last confident rotation for this many frames before dropping. */
    const val GRACE_FRAMES = 15

    /**
     * Body bone segment definitions.
     *
     * Each entry: (BodyJoint, baseLandmarkIdx, tipLandmarkIdx, bindDir).
     *
     * [bindDir] is the direction the bone points in a standard T-pose:
     *   - Spine chain:    +Y (upward)
     *   - Arms:           ±X (outward horizontally)
     *   - Legs:           -Y (downward)
     *
     * The shortest-arc quaternion rotates [bindDir] to the live direction.
     * Visibility gating uses the min(base, tip) visibility of the two driving landmarks.
     */
    private val BONE_SEGMENTS: List<BoneSegment> = listOf(
        // ── Spine chain ─────────────────────────────────────────────────
        BoneSegment(
            joint   = BodyJoint.HIPS,
            baseIdx = PL.LEFT_HIP,
            tipIdx  = PL.RIGHT_HIP,
            bindDir = Vec3(1f, 0f, 0f)      // hip-to-hip → lateral axis
        ),
        BoneSegment(
            joint   = BodyJoint.SPINE,
            baseIdx = PL.LEFT_HIP,
            tipIdx  = PL.LEFT_SHOULDER,
            bindDir = Vec3(0f, 1f, 0f)      // hip centre → shoulder centre, upward
        ),
        BoneSegment(
            joint   = BodyJoint.CHEST,
            baseIdx = PL.RIGHT_HIP,
            tipIdx  = PL.RIGHT_SHOULDER,
            bindDir = Vec3(0f, 1f, 0f)
        ),
        BoneSegment(
            joint   = BodyJoint.NECK,
            baseIdx = PL.LEFT_SHOULDER,
            tipIdx  = PL.RIGHT_SHOULDER,
            bindDir = Vec3(1f, 0f, 0f)      // shoulder-to-shoulder → lateral
        ),
        BoneSegment(
            joint   = BodyJoint.HEAD,
            baseIdx = PL.LEFT_SHOULDER,
            tipIdx  = PL.NOSE,
            bindDir = Vec3(0f, 1f, 0f)      // shoulders → nose, upward
        ),

        // ── Left arm ───────────────────────────────────────────────────
        BoneSegment(
            joint   = BodyJoint.LEFT_UPPER_ARM,
            baseIdx = PL.LEFT_SHOULDER,
            tipIdx  = PL.LEFT_ELBOW,
            bindDir = Vec3(-1f, 0f, 0f)     // T-pose: shoulder → elbow, leftward
        ),
        BoneSegment(
            joint   = BodyJoint.LEFT_LOWER_ARM,
            baseIdx = PL.LEFT_ELBOW,
            tipIdx  = PL.LEFT_WRIST,
            bindDir = Vec3(-1f, 0f, 0f)
        ),

        // ── Right arm ──────────────────────────────────────────────────
        BoneSegment(
            joint   = BodyJoint.RIGHT_UPPER_ARM,
            baseIdx = PL.RIGHT_SHOULDER,
            tipIdx  = PL.RIGHT_ELBOW,
            bindDir = Vec3(1f, 0f, 0f)      // T-pose: shoulder → elbow, rightward
        ),
        BoneSegment(
            joint   = BodyJoint.RIGHT_LOWER_ARM,
            baseIdx = PL.RIGHT_ELBOW,
            tipIdx  = PL.RIGHT_WRIST,
            bindDir = Vec3(1f, 0f, 0f)
        ),

        // ── Left leg ───────────────────────────────────────────────────
        BoneSegment(
            joint   = BodyJoint.LEFT_UPPER_LEG,
            baseIdx = PL.LEFT_HIP,
            tipIdx  = PL.LEFT_KNEE,
            bindDir = Vec3(0f, -1f, 0f)     // T-pose: hip → knee, downward
        ),
        BoneSegment(
            joint   = BodyJoint.LEFT_LOWER_LEG,
            baseIdx = PL.LEFT_KNEE,
            tipIdx  = PL.LEFT_ANKLE,
            bindDir = Vec3(0f, -1f, 0f)
        ),
        BoneSegment(
            joint   = BodyJoint.LEFT_FOOT,
            baseIdx = PL.LEFT_ANKLE,
            tipIdx  = PL.LEFT_FOOT_INDEX,
            bindDir = Vec3(0f, 0f, 1f)      // T-pose: ankle → toe, forward
        ),

        // ── Right leg ──────────────────────────────────────────────────
        BoneSegment(
            joint   = BodyJoint.RIGHT_UPPER_LEG,
            baseIdx = PL.RIGHT_HIP,
            tipIdx  = PL.RIGHT_KNEE,
            bindDir = Vec3(0f, -1f, 0f)
        ),
        BoneSegment(
            joint   = BodyJoint.RIGHT_LOWER_LEG,
            baseIdx = PL.RIGHT_KNEE,
            tipIdx  = PL.RIGHT_ANKLE,
            bindDir = Vec3(0f, -1f, 0f)
        ),
        BoneSegment(
            joint   = BodyJoint.RIGHT_FOOT,
            baseIdx = PL.RIGHT_ANKLE,
            tipIdx  = PL.RIGHT_FOOT_INDEX,
            bindDir = Vec3(0f, 0f, 1f)
        )
    )

    private data class BoneSegment(
        val joint:   BodyJoint,
        val baseIdx: Int,
        val tipIdx:  Int,
        val bindDir: Vec3
    )

    // ─── Quaternion EMA tuning ────────────────────────────────────────────────
    // Speed-adaptive quaternion EMA per body joint — same approach as
    // [QuaternionEmaFilter] for hands, tuned for slower body motion.
    private const val EMA_ALPHA_MIN    = 0.15f   // more smoothing than hands (body is slower)
    private const val EMA_ALPHA_MAX    = 1.00f
    private const val EMA_SPEED_THRESH = 0.5f    // rad/frame threshold to fully open

    // ─── API ─────────────────────────────────────────────────────────────────

    /**
     * Retarget a single frame of [PoseLandmarks] to [BodyRetargetResult].
     *
     * @param prevState Grace-period/EMA state from the caller's previous call (its own
     *   copy — see the class doc on why this must not be shared between callers).
     *   Pass [BodyRetargeterState.INITIAL] for the first call or after a reset.
     * @return The updated state to pass into the next call, paired with the result
     *   (null if [lms] has fewer than [PL.COUNT] landmarks — state is unchanged in that case).
     *
     * BODY-4: joints whose visibility is below [VISIBILITY_THRESHOLD] enter a grace
     * period — the held last-good rotation is used and the grace counter decremented.
     * Once grace expires the joint is omitted from [BodyRetargetResult.joints] entirely.
     * Joints above threshold reset the grace counter.
     */
    fun retarget(lms: PoseLandmarks, prevState: BodyRetargeterState = BodyRetargeterState.INITIAL):
        Pair<BodyRetargeterState, BodyRetargetResult?> {
        if (lms.size < PL.COUNT) return prevState to null

        val joints          = HashMap<BodyJoint, Quaternion>(BONE_SEGMENTS.size)
        val lastGoodRotation = HashMap(prevState.lastGoodRotation)
        val graceRemaining   = HashMap(prevState.graceRemaining)
        val emaPrev          = HashMap(prevState.emaPrev)
        var visSum    = 0f
        var visCount  = 0

        for (seg in BONE_SEGMENTS) {
            val baseLm = lms[seg.baseIdx]
            val tipLm  = lms[seg.tipIdx]

            // BODY-4 — Use min visibility of the two driving landmarks
            val vis = minOf(baseLm.visibility, tipLm.visibility)
            visSum  += vis
            visCount++

            if (vis >= VISIBILITY_THRESHOLD) {
                // Good visibility — compute rotation normally and replenish the
                // grace period so a future visibility dip holds GRACE_FRAMES frames.
                graceRemaining[seg.joint] = GRACE_FRAMES

                val baseVec  = lms.toVec3(seg.baseIdx)
                val tipVec   = lms.toVec3(seg.tipIdx)
                val liveDir  = (tipVec - baseVec).normalized()
                val rawRot   = shortestArcQuaternion(seg.bindDir, liveDir)
                val smoothed = applyEma(seg.joint, rawRot, emaPrev)

                joints[seg.joint]                 = smoothed
                lastGoodRotation[seg.joint]       = smoothed

            } else {
                // BODY-4 — Below threshold: grace period or drop
                val grace = graceRemaining.getOrDefault(seg.joint, 0)
                if (grace > 0) {
                    graceRemaining[seg.joint] = grace - 1
                    lastGoodRotation[seg.joint]?.let { joints[seg.joint] = it }
                } else {
                    // Grace expired — joint absent from result; receiver uses T-pose
                    graceRemaining[seg.joint] = 0
                    // Do NOT add to joints map
                }
            }
        }

        // Extract wrist positions for BODY-3 wrist anchor
        val wristLeft  = lms.toVec3(PL.LEFT_WRIST)
        val wristRight = lms.toVec3(PL.RIGHT_WRIST)
        val confidence = if (visCount > 0) visSum / visCount else 0f

        val newState = BodyRetargeterState(lastGoodRotation, graceRemaining, emaPrev)
        val result = BodyRetargetResult(
            joints      = joints,
            wristLeft   = wristLeft,
            wristRight  = wristRight,
            confidence  = confidence
        )
        return newState to result
    }

    // ─── Per-joint EMA ────────────────────────────────────────────────────────

    private fun applyEma(joint: BodyJoint, q: Quaternion, emaPrev: MutableMap<BodyJoint, Quaternion>): Quaternion {
        val p = emaPrev[joint] ?: run { emaPrev[joint] = q; return q }

        val dot = (p.x * q.x + p.y * q.y + p.z * q.z + p.w * q.w)
            .let { if (it < 0f) -it else it }.coerceIn(0f, 1f)
        val angularSpeed = 2f * kotlin.math.acos(dot)
        val t     = (angularSpeed / EMA_SPEED_THRESH).coerceIn(0f, 1f)
        val alpha = EMA_ALPHA_MIN + (EMA_ALPHA_MAX - EMA_ALPHA_MIN) * t

        val blended = p.slerp(q, alpha)
        emaPrev[joint] = blended
        return blended
    }

    // ─── Quaternion math ──────────────────────────────────────────────────────

    private fun shortestArcQuaternion(from: Vec3, to: Vec3): Quaternion {
        val dot = from.dot(to).coerceIn(-1f, 1f)
        if (dot < -0.9999f) {
            val perp = if (kotlin.math.abs(from.x) < 0.9f)
                Vec3(1f, 0f, 0f).cross(from).normalized()
            else
                Vec3(0f, 1f, 0f).cross(from).normalized()
            return Quaternion(perp.x, perp.y, perp.z, 0f).normalized()
        }
        if (dot > 0.9999f) return Quaternion(0f, 0f, 0f, 1f)
        val axis = from.cross(to)
        val w    = 1f + dot
        return Quaternion(axis.x, axis.y, axis.z, w).normalized()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Convert a pose landmark to a Vec3.
     *
     * MediaPipe PoseLandmarker provides world-space coordinates in metres when
     * [Landmark.worldX] / [worldY] / [worldZ] are non-zero. Falls back to the
     * normalised screen-space x/y/z if world coordinates are absent.
     */
    private fun PoseLandmarks.toVec3(idx: Int): Vec3 {
        val lm = this[idx]
        return if (lm.worldX != 0f || lm.worldY != 0f) {
            Vec3(lm.worldX, -lm.worldY, -lm.worldZ)
        } else {
            Vec3(lm.x, -lm.y, lm.z)
        }
    }
}

// ─── Result data class ───────────────────────────────────────────────────────

/**
 * Per-frame output of [BodyRetargeter.retarget].
 *
 * [joints] contains only joints with sufficient visibility (or still within grace
 * period). OSC and SkinnedMeshRenderer skip absent joints, leaving the receiver's
 * T-pose for that bone.
 *
 * [wristLeft] / [wristRight] are always populated when the result is non-null —
 * they come from PL.LEFT_WRIST / PL.RIGHT_WRIST and are used by BODY-3 to anchor
 * the hand skeleton to the body skeleton's wrist position.
 */
data class BodyRetargetResult(
    /** Driven body joint rotations. Only joints with sufficient visibility are present. */
    val joints:      Map<BodyJoint, Quaternion>,
    /** Left wrist world-space position from body pose (for BODY-3 wrist anchor). */
    val wristLeft:   Vec3,
    /** Right wrist world-space position from body pose (for BODY-3 wrist anchor). */
    val wristRight:  Vec3,
    /** Mean landmark visibility across all driven joints (0–1). */
    val confidence:  Float
)

/**
 * Grace-period/EMA state threaded explicitly through [BodyRetargeter.retarget] — see the
 * class doc for why this is a parameter/return value rather than internal mutable fields.
 * Each caller owns one of these (e.g. a `private var` beside its own `retarget()` call site)
 * and passes the value back in on the next call.
 */
data class BodyRetargeterState(
    /** Last confident rotation per joint. Held during grace period. */
    val lastGoodRotation: Map<BodyJoint, Quaternion> = emptyMap(),
    /** Remaining grace frames per joint. 0/absent = not in grace period. */
    val graceRemaining:   Map<BodyJoint, Int>         = emptyMap(),
    /** Previous EMA-blended rotation per joint. */
    val emaPrev:          Map<BodyJoint, Quaternion>  = emptyMap()
) {
    companion object { val INITIAL = BodyRetargeterState() }
}
