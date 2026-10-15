package com.neardimension.handy.mocap

import com.neardimension.handy.tracking.Landmark
import com.neardimension.handy.tracking.PL
import com.neardimension.handy.util.Vec3
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class HumanBoneMapperTest {

    @Test fun recognisesCommonNamingSchemes() {
        val cases = mapOf(
            "Hips" to HumanBone.HIPS, "LeftUpperArm" to HumanBone.LEFT_UPPER_ARM,     // Unity
            "leftLowerLeg" to HumanBone.LEFT_LOWER_LEG,                                  // VRM 1.0
            "J_Bip_C_Hips" to HumanBone.HIPS, "J_Bip_R_UpperArm" to HumanBone.RIGHT_UPPER_ARM, // VRM 0.x
            "J_Bip_L_Foot" to HumanBone.LEFT_FOOT,
            "mixamorig:LeftForeArm" to HumanBone.LEFT_LOWER_ARM, "mixamorig:RightUpLeg" to HumanBone.RIGHT_UPPER_LEG,
            "mixamorig:Spine1" to HumanBone.CHEST,
            "Bip001Pelvis_0115" to HumanBone.HIPS, "Bip001LUpperArm_068" to HumanBone.LEFT_UPPER_ARM, // Biped + Sketchfab
            "Bip001 R Calf" to HumanBone.RIGHT_LOWER_LEG, "Bip001LClavicle_067" to HumanBone.LEFT_SHOULDER,
            "Bip001Spine2_0321" to HumanBone.UPPER_CHEST,
            "upperarm_l" to HumanBone.LEFT_UPPER_ARM, "calf_r" to HumanBone.RIGHT_LOWER_LEG,  // Unreal
            "spine_01" to HumanBone.SPINE, "spine_02" to HumanBone.CHEST,
            "Hips_02" to HumanBone.HIPS, "LeftHand.001" to HumanBone.LEFT_HAND
        )
        for ((name, bone) in cases) assertEquals(name, bone, HumanBoneMapper.map(name))
    }

    @Test fun ignoresNonHumanoidBones() {
        for (name in listOf("Root_03", "_rootJoint", "Bip001_04", "Bone_Skirt021_L_0243",
                            "Bip001LFinger0_072", "Bone_Piao026_R_0240", "Armature", "Bip001HeadNub")) {
            assertNull(name, HumanBoneMapper.map(name))
        }
    }
}

class SkinnedBodyTest {

    private val doll: SkinnedGeometry by lazy {
        val bytes = File("../app/src/main/assets/models/body_default.glb").readBytes()
        val (json, bin) = splitGlb(bytes)
        assertNotNull(SkinnedGlbBuilder.build(json, bin))
        SkinnedGlbBuilder.build(json, bin)!!
    }

    @Test fun bundledDollLoadsAsValidSkinnedGeometry() {
        val g = doll
        val vCount = g.positions.size / 3
        assertTrue(vCount > 1000)
        assertEquals(vCount * 3, g.normals.size)
        assertEquals(0, g.indices.size % 3)
        assertTrue(g.indices.all { it in 0 until vCount })
        assertTrue(g.joints.all { it in 0 until g.skeleton.boneCount })
        for (v in 0 until vCount) {
            val s = g.weights[v * 4] + g.weights[v * 4 + 1] + g.weights[v * 4 + 2] + g.weights[v * 4 + 3]
            assertEquals(1f, s, 1e-4f)
        }
        val expected = setOf(HumanBone.HIPS, HumanBone.SPINE, HumanBone.CHEST, HumanBone.NECK, HumanBone.HEAD,
            HumanBone.LEFT_SHOULDER, HumanBone.LEFT_UPPER_ARM, HumanBone.LEFT_LOWER_ARM, HumanBone.LEFT_HAND,
            HumanBone.RIGHT_SHOULDER, HumanBone.RIGHT_UPPER_ARM, HumanBone.RIGHT_LOWER_ARM, HumanBone.RIGHT_HAND,
            HumanBone.LEFT_UPPER_LEG, HumanBone.LEFT_LOWER_LEG, HumanBone.LEFT_FOOT,
            HumanBone.RIGHT_UPPER_LEG, HumanBone.RIGHT_LOWER_LEG, HumanBone.RIGHT_FOOT)
        assertEquals(expected, g.skeleton.human.keys)
        assertTrue(g.skeleton.boneCount <= BodySkeleton.MAX_BONES)
        // Fingers are folded into the hands: the reduced skeleton is exactly the humanoid set.
        assertEquals(expected.size, g.skeleton.boneCount)
        // The doll is ~1.6 m tall, standing on the ground.
        assertEquals(1.6f, g.bounds[4] - g.bounds[1], 0.05f)
    }

    @Test fun restPoseLandmarksLeaveTheRigInItsBindPose() {
        val palette = solve(landmarks(), mirror = false)
        for (b in 0 until doll.skeleton.boneCount) assertIdentity(doll.skeleton.names[b], palette, b)
    }

    @Test fun raisingTheLeftArmMovesTheAvatarsLeftHandUp() {
        val hand = posedJoint(solve(landmarks(raiseLeftArm = true), mirror = false), HumanBone.LEFT_HAND)
        val shoulder = rest(HumanBone.LEFT_UPPER_ARM)
        assertEquals("hand at shoulder height", shoulder.y, hand.y, 0.03f)
        assertTrue("hand out to the avatar's left (+X): $hand", hand.x > shoulder.x + 0.3f)
        // The other arm does not move.
        val other = posedJoint(solve(landmarks(raiseLeftArm = true), mirror = false), HumanBone.RIGHT_HAND)
        assertEquals(rest(HumanBone.RIGHT_HAND).y, other.y, 1e-3f)
    }

    @Test fun mirroredAvatarRaisesItsRightArmInstead() {
        val palette = solve(landmarks(raiseLeftArm = true), mirror = true)
        val right = posedJoint(palette, HumanBone.RIGHT_HAND)
        val shoulder = rest(HumanBone.RIGHT_UPPER_ARM)
        assertEquals(shoulder.y, right.y, 0.03f)
        assertTrue("right hand out to -X: $right", right.x < shoulder.x - 0.3f)
        // The avatar's left arm now copies the user's (unmoved) right arm. The doll's rig is not
        // perfectly symmetric (its right shoulder sits ~2 cm lower), so allow a few centimetres.
        assertEquals(rest(HumanBone.LEFT_HAND).y, posedJoint(palette, HumanBone.LEFT_HAND).y, 0.05f)
    }

    @Test fun mirroredRestPoseIsStillTheBindPose() {
        // Mirroring swaps sides, so each arm takes the other side's rest pose; the doll's slight
        // asymmetry (~2 cm at the shoulders) is the only difference left.
        val palette = solve(landmarks(), mirror = true)
        for (bone in listOf(HumanBone.LEFT_HAND, HumanBone.RIGHT_HAND, HumanBone.LEFT_FOOT, HumanBone.RIGHT_FOOT)) {
            assertEquals(bone.name, 0f, (posedJoint(palette, bone) - rest(bone)).length(), 0.05f)
        }
    }

    @Test fun rigFacingMinusZIsTurnedToMatch() {
        // Same doll rotated 180° about Y: it now faces -Z and its left side is at -X.
        val sk = doll.skeleton
        val turned = BodySkeleton(sk.names, sk.parent,
            sk.restWorld.map { m -> m.copyOf().also { it[12] = -m[12]; it[14] = -m[14] } },
            sk.inverseBind, sk.human)
        val (_, result) = BodyRetargeter.retarget(landmarks(raiseLeftArm = true), BodyRetargeterState.INITIAL)
        val palette = BodySkinPose.solve(turned, result!!, mirror = false)
        val b = sk.human.getValue(HumanBone.LEFT_HAND)
        val p = turned.restPosition(b)
        val m = palette.copyOfRange(b * 16, b * 16 + 16)
        val y = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13]
        val x = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12]
        val shoulder = turned.restPosition(sk.human.getValue(HumanBone.LEFT_UPPER_ARM))
        assertEquals("hand at shoulder height", shoulder[1], y, 0.03f)
        assertTrue("hand out to the turned rig's left (-X): x=$x", x < shoulder[0] - 0.3f)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun rest(bone: HumanBone): Vec3 =
        doll.skeleton.restPosition(doll.skeleton.human.getValue(bone)).let { Vec3(it[0], it[1], it[2]) }

    private fun posedJoint(palette: FloatArray, bone: HumanBone): Vec3 {
        val b = doll.skeleton.human.getValue(bone)
        val p = rest(bone)
        val m = palette.copyOfRange(b * 16, b * 16 + 16)
        return Vec3(m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
                    m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
                    m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14])
    }

    private fun solve(lms: List<Landmark>, mirror: Boolean): FloatArray {
        val (_, result) = BodyRetargeter.retarget(lms, BodyRetargeterState.INITIAL)
        return BodySkinPose.solve(doll.skeleton, result!!, mirror)
    }

    /** MediaPipe world landmarks placed on the doll's own rest joints (BodyRetargeter frame: (x, -y, -z)). */
    private fun landmarks(raiseLeftArm: Boolean = false): List<Landmark> {
        val pts = Array(PL.COUNT) { rest(HumanBone.HIPS) }
        pts[PL.LEFT_HIP] = rest(HumanBone.LEFT_UPPER_LEG); pts[PL.RIGHT_HIP] = rest(HumanBone.RIGHT_UPPER_LEG)
        pts[PL.LEFT_SHOULDER] = rest(HumanBone.LEFT_UPPER_ARM); pts[PL.RIGHT_SHOULDER] = rest(HumanBone.RIGHT_UPPER_ARM)
        pts[PL.LEFT_ELBOW] = rest(HumanBone.LEFT_LOWER_ARM); pts[PL.RIGHT_ELBOW] = rest(HumanBone.RIGHT_LOWER_ARM)
        pts[PL.LEFT_WRIST] = rest(HumanBone.LEFT_HAND); pts[PL.RIGHT_WRIST] = rest(HumanBone.RIGHT_HAND)
        pts[PL.LEFT_KNEE] = rest(HumanBone.LEFT_LOWER_LEG); pts[PL.RIGHT_KNEE] = rest(HumanBone.RIGHT_LOWER_LEG)
        pts[PL.LEFT_ANKLE] = rest(HumanBone.LEFT_FOOT); pts[PL.RIGHT_ANKLE] = rest(HumanBone.RIGHT_FOOT)
        pts[PL.LEFT_FOOT_INDEX] = rest(HumanBone.LEFT_FOOT) + Vec3(0f, -0.08f, 0.12f)
        pts[PL.RIGHT_FOOT_INDEX] = rest(HumanBone.RIGHT_FOOT) + Vec3(0f, -0.08f, 0.12f)
        pts[PL.NOSE] = rest(HumanBone.HEAD) + Vec3(0f, 0.06f, 0.09f)
        if (raiseLeftArm) {
            val s = pts[PL.LEFT_SHOULDER]
            val upper = (rest(HumanBone.LEFT_LOWER_ARM) - s).length()
            val lower = (rest(HumanBone.LEFT_HAND) - rest(HumanBone.LEFT_LOWER_ARM)).length()
            pts[PL.LEFT_ELBOW] = s + Vec3(upper, 0f, 0f)
            pts[PL.LEFT_WRIST] = s + Vec3(upper + lower, 0f, 0f)
        }
        return pts.map { Landmark(x = 0.5f, y = 0.5f, z = 0f, worldX = it.x, worldY = -it.y, worldZ = -it.z) }
    }

    private fun assertIdentity(name: String, palette: FloatArray, bone: Int) {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        for (i in 0 until 16) {
            // BodyRetargeter snaps rotations under ~0.8° to identity, hence the 1e-2 tolerance (≈0.6°, 5 mm).
            val tol = if (i in 12..14) 5e-3f else 1e-2f
            assertTrue("$name[$i] = ${palette[bone * 16 + i]}", abs(palette[bone * 16 + i] - id[i]) < tol)
        }
    }

    private fun splitGlb(bytes: ByteArray): Pair<JSONObject, ByteArray> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12)
        var json: JSONObject? = null
        var bin = ByteArray(0)
        while (buf.remaining() >= 8) {
            val len = buf.int; val type = buf.int
            val chunk = ByteArray(len).also { buf.get(it) }
            if (type == 0x4E4F534A) json = JSONObject(String(chunk, Charsets.UTF_8))
            if (type == 0x004E4942) bin = chunk
        }
        return json!! to bin
    }
}
