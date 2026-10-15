package com.neardimension.handy.mocap

import com.neardimension.handy.util.Vec3
import kotlin.math.sqrt

/**
 * Turns a [BodyRetargetResult] into a GPU bone palette for a [BodySkeleton].
 *
 * [BodyRetargeter] reports each segment's rotation from a fixed T-pose direction, so
 * applying those rotations directly only works for rigs modelled in that exact T-pose.
 * Here each live segment direction is recovered ([BodyRetargeter.liveDirection]) and the
 * rig's *own* rest pose — measured from its bind matrices — is aligned onto it, so arms-down
 * A-pose rigs (like the bundled doll) and T-pose rigs both work. Bones are solved parents
 * first: each limb starts from its parent's orientation (keeping its twist) and is then
 * swung onto the live direction; bones without a driving segment (head, fingers, hair…)
 * follow their parent rigidly. The hips stay where they are in the rest pose.
 *
 * Frames: landmarks after [BodyRetargeter]'s conversion are Y-up with +Z toward the
 * camera, the user's left at +X. Rigs are Y-up (scene space, see [BodySkeleton]); their
 * facing is measured from the shoulders and the live directions are turned to match, so a
 * rig exported facing -Z works too.
 * With [mirror] (front camera, mirrored preview) the avatar mirrors the user — the user's
 * right side drives the avatar's left — so it moves like a reflection on screen.
 *
 * Palette entries are column-major 4×4 skin matrices: v' = posed + R · (v − rest).
 */
object BodySkinPose {

    /** Bind pose: every skin matrix is the identity. */
    fun restPalette(skeleton: BodySkeleton): FloatArray =
        FloatArray(skeleton.boneCount * 16).also { p ->
            for (b in 0 until skeleton.boneCount) identity().copyInto(p, b * 16)
        }

    fun solve(skeleton: BodySkeleton, body: BodyRetargetResult, mirror: Boolean): FloatArray {
        val h = skeleton.human
        fun rest(bone: HumanBone): Vec3? = h[bone]?.let { skeleton.restPosition(it).toVec3() }
        fun restDir(from: HumanBone, to: HumanBone): Vec3? {
            val a = rest(from) ?: return null
            val b = rest(to) ?: return null
            return (b - a).takeIf { it.length() > 1e-6f }?.normalized()
        }
        // Rigs are expected to face +Z with their left at +X; measure the actual facing from
        // the rig (right → left shoulder, else hip) and turn the live directions to match.
        val left = (restDir(HumanBone.RIGHT_UPPER_ARM, HumanBone.LEFT_UPPER_ARM)
            ?: restDir(HumanBone.RIGHT_UPPER_LEG, HumanBone.LEFT_UPPER_LEG))
            ?.let { Vec3(it.x, 0f, it.z) }?.takeIf { it.length() > 1e-3f }
        // Snapped to quarter turns: this detects a rig exported facing another axis, it must
        // not "correct" a few degrees of modelling asymmetry.
        val facing = left?.let { l ->
            val quarter = kotlin.math.round(kotlin.math.atan2(-l.z, l.x) / (kotlin.math.PI.toFloat() / 2f))
            val half = quarter * (kotlin.math.PI.toFloat() / 4f)        // half of quarter·90°
            Quaternion(0f, kotlin.math.sin(half), 0f, kotlin.math.cos(half))
        } ?: Quaternion.IDENTITY
        fun live(joint: BodyJoint): Vec3? = liveDirection(body, joint, mirror)?.let { facing.rotate(it) }

        val world = arrayOfNulls<Quaternion>(skeleton.boneCount)
        val driven = HashMap<Int, Quaternion>()

        // ── Torso: full orientation from a lateral axis plus the trunk's up axis ──
        val upRest = average(restDir(HumanBone.LEFT_UPPER_LEG, HumanBone.LEFT_UPPER_ARM),
                             restDir(HumanBone.RIGHT_UPPER_LEG, HumanBone.RIGHT_UPPER_ARM))
        val upLive = average(live(BodyJoint.SPINE), live(BodyJoint.CHEST))
        val hipsW = torso(restDir(HumanBone.LEFT_UPPER_LEG, HumanBone.RIGHT_UPPER_LEG), upRest,
                          live(BodyJoint.HIPS), upLive)
        val chestW = torso(restDir(HumanBone.LEFT_UPPER_ARM, HumanBone.RIGHT_UPPER_ARM), upRest,
                           live(BodyJoint.NECK), upLive)
        hipsW?.let { q -> h[HumanBone.HIPS]?.let { driven[it] = q } }
        if (hipsW != null && chestW != null) h[HumanBone.SPINE]?.let { driven[it] = hipsW.slerp(chestW, 0.5f) }
        chestW?.let { q -> (h[HumanBone.CHEST] ?: h[HumanBone.UPPER_CHEST])?.let { driven[it] = q } }

        // ── Limbs: inherit the parent's orientation, then swing onto the live direction ──
        val limbs = listOf(
            Triple(HumanBone.LEFT_UPPER_ARM, HumanBone.LEFT_LOWER_ARM, BodyJoint.LEFT_UPPER_ARM),
            Triple(HumanBone.LEFT_LOWER_ARM, HumanBone.LEFT_HAND, BodyJoint.LEFT_LOWER_ARM),
            Triple(HumanBone.RIGHT_UPPER_ARM, HumanBone.RIGHT_LOWER_ARM, BodyJoint.RIGHT_UPPER_ARM),
            Triple(HumanBone.RIGHT_LOWER_ARM, HumanBone.RIGHT_HAND, BodyJoint.RIGHT_LOWER_ARM),
            Triple(HumanBone.LEFT_UPPER_LEG, HumanBone.LEFT_LOWER_LEG, BodyJoint.LEFT_UPPER_LEG),
            Triple(HumanBone.LEFT_LOWER_LEG, HumanBone.LEFT_FOOT, BodyJoint.LEFT_LOWER_LEG),
            Triple(HumanBone.LEFT_FOOT, HumanBone.LEFT_TOES, BodyJoint.LEFT_FOOT),
            Triple(HumanBone.RIGHT_UPPER_LEG, HumanBone.RIGHT_LOWER_LEG, BodyJoint.RIGHT_UPPER_LEG),
            Triple(HumanBone.RIGHT_LOWER_LEG, HumanBone.RIGHT_FOOT, BodyJoint.RIGHT_LOWER_LEG),
            Triple(HumanBone.RIGHT_FOOT, HumanBone.RIGHT_TOES, BodyJoint.RIGHT_FOOT)
        )
        val limbOf = HashMap<Int, Pair<Vec3, Vec3>>()   // bone → (rest dir, live dir)
        for ((bone, tip, joint) in limbs) {
            val b = h[bone] ?: continue
            val r = restDir(bone, tip) ?: continue
            val d = live(joint) ?: continue
            limbOf[b] = r to d
        }

        // ── Solve rotations and positions, parents first ──
        val restPos = Array(skeleton.boneCount) { skeleton.restPosition(it).toVec3() }
        val posed = arrayOfNulls<Vec3>(skeleton.boneCount)
        val palette = FloatArray(skeleton.boneCount * 16)
        for (b in 0 until skeleton.boneCount) {
            val p = skeleton.parent[b]
            val parentW = if (p >= 0) world[p] ?: Quaternion.IDENTITY else Quaternion.IDENTITY
            world[b] = driven[b] ?: limbOf[b]?.let { (r, d) ->
                shortestArc(parentW.rotate(r), d) * parentW
            } ?: parentW
            posed[b] = if (p >= 0) posed[p]!! + parentW.rotate(restPos[b] - restPos[p]) else restPos[b]
            skinMatrix(posed[b]!!, world[b]!!, restPos[b]).copyInto(palette, b * 16)
        }
        return palette
    }

    // ─── Live directions ─────────────────────────────────────────────────────

    private val MIRROR_OF = mapOf(
        BodyJoint.SPINE to BodyJoint.CHEST, BodyJoint.CHEST to BodyJoint.SPINE,
        BodyJoint.LEFT_UPPER_ARM to BodyJoint.RIGHT_UPPER_ARM, BodyJoint.RIGHT_UPPER_ARM to BodyJoint.LEFT_UPPER_ARM,
        BodyJoint.LEFT_LOWER_ARM to BodyJoint.RIGHT_LOWER_ARM, BodyJoint.RIGHT_LOWER_ARM to BodyJoint.LEFT_LOWER_ARM,
        BodyJoint.LEFT_UPPER_LEG to BodyJoint.RIGHT_UPPER_LEG, BodyJoint.RIGHT_UPPER_LEG to BodyJoint.LEFT_UPPER_LEG,
        BodyJoint.LEFT_LOWER_LEG to BodyJoint.RIGHT_LOWER_LEG, BodyJoint.RIGHT_LOWER_LEG to BodyJoint.LEFT_LOWER_LEG,
        BodyJoint.LEFT_FOOT to BodyJoint.RIGHT_FOOT, BodyJoint.RIGHT_FOOT to BodyJoint.LEFT_FOOT
    )

    /** Segments that run from a left landmark to its right counterpart. */
    private val LATERAL = setOf(BodyJoint.HIPS, BodyJoint.NECK)

    private fun liveDirection(body: BodyRetargetResult, joint: BodyJoint, mirror: Boolean): Vec3? {
        val source = if (mirror) MIRROR_OF[joint] ?: joint else joint
        val q = body.joints[source] ?: return null
        val d = BodyRetargeter.liveDirection(source, q) ?: return null
        if (!mirror) return d
        // Reflect across the YZ plane. A left→right segment also swaps its ends.
        return if (joint in LATERAL) Vec3(d.x, -d.y, -d.z) else Vec3(-d.x, d.y, d.z)
    }

    // ─── Math ────────────────────────────────────────────────────────────────

    private fun torso(latRest: Vec3?, upRest: Vec3?, latLive: Vec3?, upLive: Vec3?): Quaternion? {
        if (upRest == null || upLive == null) return null
        if (latRest == null || latLive == null) return shortestArc(upRest, upLive)
        val rest = basis(latRest, upRest) ?: return shortestArc(upRest, upLive)
        val live = basis(latLive, upLive) ?: return shortestArc(upRest, upLive)
        // R = live · restᵀ (columns are the basis vectors)
        val m = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            var s = 0f
            for (k in 0..2) s += live[k * 3 + r] * rest[k * 3 + c]
            m[c * 3 + r] = s
        }
        return fromMatrix(m)
    }

    /** Orthonormal basis (x = lateral, y = up made orthogonal, z = x × y), column-major 3×3. */
    private fun basis(lateral: Vec3, up: Vec3): FloatArray? {
        val x = lateral.normalized()
        val yRaw = up - x * up.dot(x)
        if (yRaw.length() < 1e-4f) return null
        val y = yRaw.normalized()
        val z = x.cross(y)
        return floatArrayOf(x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z)
    }

    private fun fromMatrix(m: FloatArray): Quaternion {
        val m00 = m[0]; val m11 = m[4]; val m22 = m[8]
        val m01 = m[3]; val m10 = m[1]; val m02 = m[6]; val m20 = m[2]; val m12 = m[7]; val m21 = m[5]
        val trace = m00 + m11 + m22
        val q = when {
            trace > 0f -> { val s = sqrt(trace + 1f) * 2f
                Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s) }
            m00 > m11 && m00 > m22 -> { val s = sqrt(1f + m00 - m11 - m22) * 2f
                Quaternion(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s) }
            m11 > m22 -> { val s = sqrt(1f + m11 - m00 - m22) * 2f
                Quaternion((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s) }
            else -> { val s = sqrt(1f + m22 - m00 - m11) * 2f
                Quaternion((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s) }
        }
        return q.normalized()
    }

    private fun shortestArc(from: Vec3, to: Vec3): Quaternion {
        val f = from.normalized(); val t = to.normalized()
        val dot = f.dot(t).coerceIn(-1f, 1f)
        if (dot > 0.99999f) return Quaternion.IDENTITY
        if (dot < -0.99999f) {
            val axis = (if (kotlin.math.abs(f.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)).cross(f).normalized()
            return Quaternion(axis.x, axis.y, axis.z, 0f)
        }
        val a = f.cross(t)
        return Quaternion(a.x, a.y, a.z, 1f + dot).normalized()
    }

    private fun average(a: Vec3?, b: Vec3?): Vec3? = when {
        a != null && b != null -> (a + b).takeIf { it.length() > 1e-6f }?.normalized()
        else -> a ?: b
    }

    private fun skinMatrix(posed: Vec3, rotation: Quaternion, rest: Vec3): FloatArray {
        val m = rotation.toMatrix()
        val rr = rotation.rotate(rest)
        m[12] = posed.x - rr.x; m[13] = posed.y - rr.y; m[14] = posed.z - rr.z
        return m
    }

    private fun FloatArray.toVec3() = Vec3(this[0], this[1], this[2])
}
