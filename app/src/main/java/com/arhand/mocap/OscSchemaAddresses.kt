package com.arhand.mocap

import java.nio.charset.StandardCharsets

/**
 * Precomputed OSC address byte arrays for all supported schemas.
 * Extracted from OscStreamer to keep each file under 500 lines.
 * All arrays are val — allocated once at class load time.
 */
// ─── Schema address tables ────────────────────────────────────────────────────

/**
 * Maps schema → (jointIndex → OSC address bytes).
 * Built once at class load; all entries are pre-padded to 4-byte boundaries.
 */
internal object SchemaAddresses {

    // --- Handy native ---
    // /hand/<JointName>/rotation
    private val handyJointNames = mapOf(
        BoneRetargeter.JOINT_WRIST       to "Wrist",
        BoneRetargeter.JOINT_THUMB_CMC   to "ThumbCMC",
        BoneRetargeter.JOINT_THUMB_MCP   to "ThumbMCP",
        BoneRetargeter.JOINT_THUMB_IP    to "ThumbIP",
        BoneRetargeter.JOINT_INDEX_MCP   to "IndexMCP",
        BoneRetargeter.JOINT_INDEX_PIP   to "IndexPIP",
        BoneRetargeter.JOINT_INDEX_DIP   to "IndexDIP",
        BoneRetargeter.JOINT_MIDDLE_MCP  to "MiddleMCP",
        BoneRetargeter.JOINT_MIDDLE_PIP  to "MiddlePIP",
        BoneRetargeter.JOINT_MIDDLE_DIP  to "MiddleDIP",
        BoneRetargeter.JOINT_RING_MCP    to "RingMCP",
        BoneRetargeter.JOINT_RING_PIP    to "RingPIP",
        BoneRetargeter.JOINT_RING_DIP    to "RingDIP",
        BoneRetargeter.JOINT_PINKY_MCP   to "PinkyMCP",
        BoneRetargeter.JOINT_PINKY_PIP   to "PinkyPIP",
        BoneRetargeter.JOINT_PINKY_DIP   to "PinkyDIP"
    )

    // --- VMC v2.3 bone names ---
    // Reference: https://protocol.vmc.info/marionette-spec  (VirtualMotionCapture OSC Protocol)
    // Address: /VMC/Ext/Bon/Rot   args: string boneName, float qx, qy, qz, qw
    // The bone name is sent as an OSC string argument, not encoded in the address.
    // We still need per-joint address bytes (all the same "/VMC/Ext/Bon/Rot") and
    // a pre-built bone-name string argument per joint.
    private val vmcBoneNames = mapOf(
        BoneRetargeter.JOINT_WRIST       to "RightHand",
        BoneRetargeter.JOINT_THUMB_CMC   to "RightThumbProximal",
        BoneRetargeter.JOINT_THUMB_MCP   to "RightThumbIntermediate",
        BoneRetargeter.JOINT_THUMB_IP    to "RightThumbDistal",
        BoneRetargeter.JOINT_INDEX_MCP   to "RightIndexProximal",
        BoneRetargeter.JOINT_INDEX_PIP   to "RightIndexIntermediate",
        BoneRetargeter.JOINT_INDEX_DIP   to "RightIndexDistal",
        BoneRetargeter.JOINT_MIDDLE_MCP  to "RightMiddleProximal",
        BoneRetargeter.JOINT_MIDDLE_PIP  to "RightMiddleIntermediate",
        BoneRetargeter.JOINT_MIDDLE_DIP  to "RightMiddleDistal",
        BoneRetargeter.JOINT_RING_MCP    to "RightRingProximal",
        BoneRetargeter.JOINT_RING_PIP    to "RightRingIntermediate",
        BoneRetargeter.JOINT_RING_DIP    to "RightRingDistal",
        BoneRetargeter.JOINT_PINKY_MCP   to "RightLittleProximal",
        BoneRetargeter.JOINT_PINKY_PIP   to "RightLittleIntermediate",
        BoneRetargeter.JOINT_PINKY_DIP   to "RightLittleDistal"
    )

    // --- Unreal LiveLink OSC bone names ---
    // Address: /LiveLink/<SubjectName>/Bone/<BoneName>  args: float qx, qy, qz, qw
    // Subject name is configurable; we default to "Hand".
    // UE LiveLink uses Mixamo-compatible bone names for hand rigs.
    private val unrealBoneNames = mapOf(
        BoneRetargeter.JOINT_WRIST       to "RightHand",
        BoneRetargeter.JOINT_THUMB_CMC   to "RightHandThumb1",
        BoneRetargeter.JOINT_THUMB_MCP   to "RightHandThumb2",
        BoneRetargeter.JOINT_THUMB_IP    to "RightHandThumb3",
        BoneRetargeter.JOINT_INDEX_MCP   to "RightHandIndex1",
        BoneRetargeter.JOINT_INDEX_PIP   to "RightHandIndex2",
        BoneRetargeter.JOINT_INDEX_DIP   to "RightHandIndex3",
        BoneRetargeter.JOINT_MIDDLE_MCP  to "RightHandMiddle1",
        BoneRetargeter.JOINT_MIDDLE_PIP  to "RightHandMiddle2",
        BoneRetargeter.JOINT_MIDDLE_DIP  to "RightHandMiddle3",
        BoneRetargeter.JOINT_RING_MCP    to "RightHandRing1",
        BoneRetargeter.JOINT_RING_PIP    to "RightHandRing2",
        BoneRetargeter.JOINT_RING_DIP    to "RightHandRing3",
        BoneRetargeter.JOINT_PINKY_MCP   to "RightHandPinky1",
        BoneRetargeter.JOINT_PINKY_PIP   to "RightHandPinky2",
        BoneRetargeter.JOINT_PINKY_DIP   to "RightHandPinky3"
    )

    // --- VSeeFace legacy: same addresses as VMC, kept as a separate preset so
    //     users on the VSeeFace "Direct" OSC tab (which does not follow v2.3 spec
    //     fully) can select it without touching VMC settings. Address is
    //     /VMC/Ext/Bone/Pos (note: "Bone" not "Bon", and "Pos" not "Rot") —
    //     VSeeFace legacy sends position + quaternion as a single blob.
    // We encode it the same as VMC but with the legacy address so the receiver
    // picks it up on the correct address filter.
    // Reference: VSeeFace OSC documentation (https://www.vseeface.icu/#osc-control)
    // ─────────────────────────────────────────────────────────────────────────

    // Pre-built address byte arrays ───────────────────────────────────────────

    val handyRotAddr:  Map<Int, ByteArray> = MotionRecorder.HIERARCHY_ORDER.associateWith { j ->
        val name = handyJointNames[j] ?: "Joint$j"
        oscString("/hand/$name/rotation")
    }
    val handyPosAddr:  ByteArray = oscString("/hand/wrist/position")

    // VMC: single address "/VMC/Ext/Bon/Rot" for all joints; bone name in args
    val vmcRotAddr:    ByteArray = oscString("/VMC/Ext/Bon/Rot")
    val vmcPosAddr:    ByteArray = oscString("/VMC/Ext/Bon/Rot")   // wrist pos also via Bon/Rot
    val vmcBoneBytes:  Map<Int, ByteArray> = MotionRecorder.HIERARCHY_ORDER.associateWith { j ->
        oscString(vmcBoneNames[j] ?: "RightHand")
    }
    val vmcWristBone:  ByteArray = oscString("RightHand")

    // Unreal LiveLink: address per bone "/LiveLink/Hand/Bone/<BoneName>"
    val unrealRotAddr: Map<Int, ByteArray> = MotionRecorder.HIERARCHY_ORDER.associateWith { j ->
        val bone = unrealBoneNames[j] ?: "RightHand"
        oscString("/LiveLink/Hand/Bone/$bone")
    }
    val unrealPosAddr: ByteArray = oscString("/LiveLink/Hand/Root")

    // VSeeFace legacy: "/VMC/Ext/Bone/Pos" with bone name in args
    val vsfRotAddr:    ByteArray = oscString("/VMC/Ext/Bone/Pos")
    val vsfPosAddr:    ByteArray = oscString("/VMC/Ext/Bone/Pos")
    // VSeeFace reuses VMC bone names
    val vsfBoneBytes:  Map<Int, ByteArray> = vmcBoneBytes
    val vsfWristBone:  ByteArray = vmcWristBone

    // ── OSC-1: VMC body bone byte arrays ─────────────────────────────────────
    //
    // VMC v2.3 HumanBodyBones names for body joints.
    // BodyJoint.vmcName matches these exactly — pre-built here to avoid
    // per-frame string allocation in the hot path.
    val vmcBodyBoneBytes: Map<BodyJoint, ByteArray> = BodyJoint.entries.associateWith { joint ->
        oscString(joint.vmcName)
    }

    /** Pad a string to the next 4-byte boundary with null bytes, as required by OSC 1.0. */
    fun oscString(s: String): ByteArray {
        val raw = s.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0)
        val pad = (4 - raw.size % 4) % 4
        return raw + ByteArray(pad)
    }
}

