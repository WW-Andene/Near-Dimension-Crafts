package com.arhand.mocap

import com.arhand.util.Vec3
import java.io.File
import java.io.FileWriter
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Records timestamped frames of joint rotations and wrist root data into a fixed-size
 * ring buffer (default cap: 60 seconds at 30fps = 1,800 frames), then exports to BVH.
 *
 * Usage:
 * ```kotlin
 * val recorder = MotionRecorder()
 *
 * // Each frame while recording is active:
 * recorder.pushFrame(retargetResult, handIndex = 0)
 *
 * // When the user stops recording:
 * val bvhFile = recorder.exportBvh(outputDir, label = "take_01")
 * recorder.reset()
 * ```
 *
 * Thread safety: [pushFrame] is called from the GL / tracking thread; [exportBvh] and
 * [reset] are called from the UI thread. Synchronization is via [synchronized] on
 * [frames]. Keep the critical section short — only the ring-buffer append/copy.
 */
class MotionRecorder(
    /** Maximum number of frames retained. Oldest frames are dropped when the cap is hit. */
    val maxFrames: Int = MAX_FRAMES_DEFAULT
) {

    companion object {
        const val MAX_FRAMES_DEFAULT = 1_800   // 60 s × 30 fps
        const val FPS = 30
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

        // BVH channel order for each joint: ZX rotation (Euler ZXY decomposition)
        // Most DCC tools expect ZXY when importing hand motion BVH.
        private val JOINT_NAMES = mapOf(
            BoneRetargeter.JOINT_WRIST      to "Wrist",
            BoneRetargeter.JOINT_THUMB_CMC  to "ThumbCMC",
            BoneRetargeter.JOINT_THUMB_MCP  to "ThumbMCP",
            BoneRetargeter.JOINT_THUMB_IP   to "ThumbIP",
            BoneRetargeter.JOINT_INDEX_MCP  to "IndexMCP",
            BoneRetargeter.JOINT_INDEX_PIP  to "IndexPIP",
            BoneRetargeter.JOINT_INDEX_DIP  to "IndexDIP",
            BoneRetargeter.JOINT_MIDDLE_MCP to "MiddleMCP",
            BoneRetargeter.JOINT_MIDDLE_PIP to "MiddlePIP",
            BoneRetargeter.JOINT_MIDDLE_DIP to "MiddleDIP",
            BoneRetargeter.JOINT_RING_MCP   to "RingMCP",
            BoneRetargeter.JOINT_RING_PIP   to "RingPIP",
            BoneRetargeter.JOINT_RING_DIP   to "RingDIP",
            BoneRetargeter.JOINT_PINKY_MCP  to "PinkyMCP",
            BoneRetargeter.JOINT_PINKY_PIP  to "PinkyPIP",
            BoneRetargeter.JOINT_PINKY_DIP  to "PinkyDIP"
        )

        // Approximate bone lengths (in world-space units) used for BVH OFFSET values.
        // These are the rest-pose distances between joint origins for a normalised hand.
        // They only affect skeleton display in the BVH viewer — animation data is
        // rotation-only so the values are cosmetic.
        // Not private: reused by GLBAnimationExporter to give exported joint nodes a
        // non-degenerate rest-pose translation (see BoneRetargeter.symmetricBindPose
        // for the matching direction vectors).
        val BONE_LENGTH = mapOf(
            BoneRetargeter.JOINT_WRIST      to 0.00f,   // root — offset from scene origin
            BoneRetargeter.JOINT_THUMB_CMC  to 0.045f,
            BoneRetargeter.JOINT_THUMB_MCP  to 0.030f,
            BoneRetargeter.JOINT_THUMB_IP   to 0.028f,
            BoneRetargeter.JOINT_INDEX_MCP  to 0.070f,
            BoneRetargeter.JOINT_INDEX_PIP  to 0.030f,
            BoneRetargeter.JOINT_INDEX_DIP  to 0.022f,
            BoneRetargeter.JOINT_MIDDLE_MCP to 0.075f,
            BoneRetargeter.JOINT_MIDDLE_PIP to 0.032f,
            BoneRetargeter.JOINT_MIDDLE_DIP to 0.024f,
            BoneRetargeter.JOINT_RING_MCP   to 0.070f,
            BoneRetargeter.JOINT_RING_PIP   to 0.030f,
            BoneRetargeter.JOINT_RING_DIP   to 0.022f,
            BoneRetargeter.JOINT_PINKY_MCP  to 0.055f,
            BoneRetargeter.JOINT_PINKY_PIP  to 0.025f,
            BoneRetargeter.JOINT_PINKY_DIP  to 0.018f
        )

        /** Joint ordering for BVH hierarchy output — wrist first, then finger chains. */
        val HIERARCHY_ORDER: List<Int> = listOf(
            BoneRetargeter.JOINT_WRIST,
            BoneRetargeter.JOINT_THUMB_CMC,
            BoneRetargeter.JOINT_THUMB_MCP,
            BoneRetargeter.JOINT_THUMB_IP,
            BoneRetargeter.JOINT_INDEX_MCP,
            BoneRetargeter.JOINT_INDEX_PIP,
            BoneRetargeter.JOINT_INDEX_DIP,
            BoneRetargeter.JOINT_MIDDLE_MCP,
            BoneRetargeter.JOINT_MIDDLE_PIP,
            BoneRetargeter.JOINT_MIDDLE_DIP,
            BoneRetargeter.JOINT_RING_MCP,
            BoneRetargeter.JOINT_RING_PIP,
            BoneRetargeter.JOINT_RING_DIP,
            BoneRetargeter.JOINT_PINKY_MCP,
            BoneRetargeter.JOINT_PINKY_PIP,
            BoneRetargeter.JOINT_PINKY_DIP
        )
    }

    // ─── Ring buffer ──────────────────────────────────────────────────────────

    /** Immutable snapshot of one recorded frame. */
    data class RecordedFrame(
        /** Elapsed time from recording start, in milliseconds. */
        val timestampMs: Long,
        /** Joint index → quaternion. A frame may have fewer than 16 entries if
         *  retargeting returned partial data (occluded joints). Missing joints
         *  are filled with IDENTITY on export. */
        val jointRotations: Map<Int, Quaternion>,
        /** Wrist world-space root transform for the captured hand. */
        val wristTransform: WristTransform,
        /**
         * IMP-11 — Face blend-shape values captured at the same frame.
         * Null when face tracking was disabled or unavailable.
         * Written to BVH as extension DOF channels; emitted via OSC separately.
         */
        val faceExpressions: com.arhand.tracking.FaceExpressions? = null,
        /**
         * ARCH-2 — Body joint rotations from [BodyRetargetResult] captured at the same frame.
         * Null when body tracking was disabled. Written to BVH body channels and
         * GLB body animation tracks on export.
         */
        val bodyJoints: Map<BodyJoint, Quaternion>? = null
    )

    private val frames = ArrayDeque<RecordedFrame>(maxFrames)
    private var recordingStartMs: Long = -1L

    // ─── Recording control ────────────────────────────────────────────────────

    /** True while the recorder is actively accepting frames. */
    var isRecording: Boolean = false
        private set

    /** Start a new recording session. Clears any previously buffered frames. */
    fun start() {
        synchronized(frames) {
            frames.clear()
            recordingStartMs = System.currentTimeMillis()
            isRecording = true
        }
    }

    /** Stop accepting new frames. Does not clear buffered data. */
    fun stop() {
        synchronized(frames) { isRecording = false }
    }

    /** Stop recording and clear all buffered frames. */
    fun reset() {
        synchronized(frames) {
            isRecording = false
            frames.clear()
            recordingStartMs = -1L
        }
    }

    // ─── Frame ingestion ──────────────────────────────────────────────────────

    /**
     * Push one retargeted frame into the ring buffer.
     *
     * Safe to call from the tracking / GL thread. If the buffer is full the oldest
     * frame is evicted so the most recent [maxFrames] frames are always retained.
     *
     * @param result  Output from [BoneRetargeter.retarget] for the current frame.
     */
    fun pushFrame(result: RetargetResult, faceExpressions: com.arhand.tracking.FaceExpressions? = null, bodyJoints: Map<BodyJoint, Quaternion>? = null) {
        val now = System.currentTimeMillis()
        val frame = RecordedFrame(
            timestampMs     = if (recordingStartMs < 0) 0L else now - recordingStartMs,
            jointRotations  = HashMap(result.jointRotations),   // defensive copy
            wristTransform  = result.wristTransform,
            faceExpressions = faceExpressions,
            bodyJoints      = bodyJoints?.let { HashMap(it) }   // defensive copy
        )

        // the check and the append — a frame must never be added after recording stops.
        synchronized(frames) {
            if (!isRecording) return
            if (frames.size >= maxFrames) frames.removeFirst()
            frames.addLast(frame)
        }
    }

    /**
     * ARCH-1 — Unified push path for a complete [FullBodyRetargetResult] frame.
     *
     * Delegates to the existing [pushFrame] passing hand, face, and body data together.
     * Gracefully skips recording when [full.handPrimary] is null.
     */
    fun pushFrame(full: FullBodyRetargetResult) {
        full.handPrimary?.let { pushFrame(it, full.face, full.body?.joints) }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /** Snapshot of all buffered frames in chronological order. */
    fun frameSnapshot(): List<RecordedFrame> = synchronized(frames) { frames.toList() }

    /** Duration of the buffered recording in milliseconds. 0 if no frames. */
    val durationMs: Long get() = synchronized(frames) {
        if (frames.isEmpty()) 0L else frames.last().timestampMs - frames.first().timestampMs
    }

    /** Number of frames currently buffered. */
    val frameCount: Int get() = synchronized(frames) { frames.size }

    // ─── BVH Export ──────────────────────────────────────────────────────────

    /**
     * Export the current ring buffer as a BVH (Biovision Hierarchy) file.
     *
     * BVH is the universal mocap interchange format — readable by Blender, Maya,
     * MotionBuilder, Unity, and Unreal with no plugin required.
     *
     * Structure:
     * - Root joint: Wrist with 6 channels (Xposition Yposition Zposition Zrotation
     *   Xrotation Yrotation) — root motion enabled.
     * - Finger joints: 3 channels each (Zrotation Xrotation Yrotation) — rotation only.
     * - End sites appended after each leaf joint (required by the BVH spec).
     *
     * Rotation convention: ZXY Euler angles in degrees. This matches the default
     * import settings in Blender and MotionBuilder for hand rigs.
     *
     * @param outputDir  Directory to write the file into (must be writable).
     * @param label      File name stem, e.g. "take_01". Spaces replaced with underscores.
     * @return           The written [File], or null if the buffer is empty or IO fails.
     */
    /**
     * Export the buffered recording to a BVH file.
     *
     * HAND-2 — [jointOffsets] carries real metric joint positions from the scan
     * (mean of [RetargetResult.jointPositions] across the 8 scan poses). When provided,
     * these are written into the BVH HIERARCHY offsets so receivers import the animation
     * with the correct user-specific hand proportions rather than generic defaults.
     *
     * @param outputDir    Directory to write the BVH file into.
     * @param label        File name label (spaces and special chars are sanitised).
     * @param jointOffsets Optional map from [BoneRetargeter] JOINT_* constant to world-space
     *                     Vec3 position. Used as OFFSET values in the BVH HIERARCHY section.
     */
    fun exportBvh(
        outputDir:    File,
        label:        String = "mocap",
        jointOffsets: Map<Int, com.arhand.util.Vec3>? = null
    ): File? {
        val snapshot = frameSnapshot()
        if (snapshot.isEmpty()) return null

        val safeName = label.replace(' ', '_').replace(Regex("[^A-Za-z0-9_\\-]"), "")
        val outFile  = File(outputDir, "$safeName.bvh")

        return try {
            FileWriter(outFile).use { w ->
                writeBvhHierarchy(w, jointOffsets)
                writeBvhMotion(w, snapshot)
            }
            outFile
        } catch (e: Exception) {
            null
        }
    }

    // ─── BVH Hierarchy section ────────────────────────────────────────────────

    private fun writeBvhHierarchy(
        w:            FileWriter,
        jointOffsets: Map<Int, com.arhand.util.Vec3>? = null
    ) {
        w.write("HIERARCHY\n")

        // HAND-2 — Use scanned wrist offset when available (world-space metres → BVH centimetres).
        val wristOff = jointOffsets?.get(BoneRetargeter.JOINT_WRIST)
        val wristOffStr = if (wristOff != null)
            "%.4f %.4f %.4f".format(wristOff.x * 100f, wristOff.y * 100f, wristOff.z * 100f)
        else "0.00 0.00 0.00"

        // ARCH-2 — Full-body BVH hierarchy.
        //
        // Structure when body joints are present:
        //   Hips (root — body root with position + rotation)
        //     ├── Spine → Chest → Neck → Head → End
        //     ├── LeftUpperArm → LeftLowerArm → LeftHand (wrist anchor)
        //     │     └── [hand finger chains]
        //     ├── RightUpperArm → RightLowerArm → RightHand (wrist, actual hand root)
        //     │     └── [hand finger chains]
        //     ├── LeftUpperLeg → LeftLowerLeg → LeftFoot → End
        //     └── RightUpperLeg → RightLowerLeg → RightFoot → End
        //
        // When no body data was recorded (all frames have bodyJoints == null),
        // falls back to the original hand-only BVH with Wrist as root.

        // Always write the full-body hierarchy so files are importable regardless of
        // whether body tracking was active. Body joints default to IDENTITY when absent.
        w.write("{\n")
        w.write("\tOFFSET 0.00 0.00 0.00\n")
        w.write("\tCHANNELS 6 Xposition Yposition Zposition Zrotation Xrotation Yrotation\n")

        // ── Spine chain ─────────────────────────────────────────────────────
        // Written inline to manage nested brace closing precisely.
        // Spine(d=1) > Chest(d=2) > Neck(d=3) > Head(d=4, leaf)
        w.write("\tJOINT Spine\n\t{\n\t\tOFFSET 0.00 0.10 0.00\n\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\tJOINT Chest\n\t\t{\n\t\t\tOFFSET 0.00 0.10 0.00\n\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\t\tJOINT Neck\n\t\t\t{\n\t\t\t\tOFFSET 0.00 0.10 0.00\n\t\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\t\t\tJOINT Head\n\t\t\t\t{\n\t\t\t\t\tOFFSET 0.00 0.10 0.00\n\t\t\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\t\t\t\tEnd Site\n\t\t\t\t\t{\n\t\t\t\t\t\tOFFSET 0.00 0.10 0.00\n\t\t\t\t\t}\n")
        w.write("\t\t\t\t}\n")   // Head
        w.write("\t\t\t}\n")     // Neck
        w.write("\t\t}\n")       // Chest
        w.write("\t}\n")         // Spine

        // ── Left arm → hand ──────────────────────────────────────────────
        w.write("\tJOINT LeftUpperArm\n\t{\n\t\tOFFSET 0.00 0.28 0.00\n\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\tJOINT LeftLowerArm\n\t\t{\n\t\t\tOFFSET 0.00 0.25 0.00\n\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\t\tJOINT LeftHand\n\t\t\t{\n\t\t\t\tOFFSET 0.00 0.25 0.00\n\t\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\t\t\tEnd Site\n\t\t\t\t{\n\t\t\t\t\tOFFSET 0.00 0.07 0.00\n\t\t\t\t}\n")
        w.write("\t\t\t}\n")   // LeftHand
        w.write("\t\t}\n")     // LeftLowerArm
        w.write("\t}\n")       // LeftUpperArm

        // ── Right arm → hand (primary tracked hand) ───────────────────────
        w.write("\tJOINT RightUpperArm\n\t{\n\t\tOFFSET 0.00 0.28 0.00\n\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\tJOINT RightLowerArm\n\t\t{\n\t\t\tOFFSET 0.00 0.25 0.00\n\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\t\tJOINT Wrist\n\t\t\t{\n\t\t\t\tOFFSET $wristOffStr\n\t\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")

        // Hand finger chains parented to Wrist
        writeFingerChain(w, "Thumb",  listOf("ThumbCMC",  "ThumbMCP",  "ThumbIP",  "ThumbTip"),  4, jointOffsets)
        writeFingerChain(w, "Index",  listOf("IndexMCP",  "IndexPIP",  "IndexDIP",  "IndexTip"),  4, jointOffsets)
        writeFingerChain(w, "Middle", listOf("MiddleMCP", "MiddlePIP", "MiddleDIP", "MiddleTip"), 4, jointOffsets)
        writeFingerChain(w, "Ring",   listOf("RingMCP",   "RingPIP",   "RingDIP",   "RingTip"),   4, jointOffsets)
        writeFingerChain(w, "Pinky",  listOf("PinkyMCP",  "PinkyPIP",  "PinkyDIP",  "PinkyTip"),  4, jointOffsets)

        // FaceBlendshapes stub (IMP-11 / Bug 14)
        w.write("\t\t\t\tJOINT FaceBlendshapes\n\t\t\t\t{\n")
        w.write("\t\t\t\t\tOFFSET 0.00 0.00 0.00\n")
        w.write("\t\t\t\t\tCHANNELS 7 eyeBlinkLeft eyeBlinkRight jawOpen browInnerUp browOuterUpRight mouthSmileLeft mouthSmileRight\n")
        w.write("\t\t\t\t\tEnd Site\n\t\t\t\t\t{\n\t\t\t\t\t\tOFFSET 0.00 0.00 0.00\n\t\t\t\t\t}\n")
        w.write("\t\t\t\t}\n")   // FaceBlendshapes

        w.write("\t\t\t}\n")   // Wrist
        w.write("\t\t}\n")     // RightLowerArm
        w.write("\t}\n")       // RightUpperArm

        // ── Left leg ─────────────────────────────────────────────────────
        w.write("\tJOINT LeftUpperLeg\n\t{\n\t\tOFFSET 0.00 0.42 0.00\n\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\tJOINT LeftLowerLeg\n\t\t{\n\t\t\tOFFSET 0.00 0.40 0.00\n\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\t\tJOINT LeftFoot\n\t\t\t{\n\t\t\t\tOFFSET 0.00 0.10 0.00\n\t\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\t\t\tEnd Site\n\t\t\t\t{\n\t\t\t\t\tOFFSET 0.00 0.10 0.00\n\t\t\t\t}\n")
        w.write("\t\t\t}\n\t\t}\n\t}\n")   // LeftFoot, LeftLowerLeg, LeftUpperLeg

        // ── Right leg ─────────────────────────────────────────────────────
        w.write("\tJOINT RightUpperLeg\n\t{\n\t\tOFFSET 0.00 0.42 0.00\n\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\tJOINT RightLowerLeg\n\t\t{\n\t\t\tOFFSET 0.00 0.40 0.00\n\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\t\tJOINT RightFoot\n\t\t\t{\n\t\t\t\tOFFSET 0.00 0.10 0.00\n\t\t\t\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
        w.write("\t\t\t\tEnd Site\n\t\t\t\t{\n\t\t\t\t\tOFFSET 0.00 0.10 0.00\n\t\t\t\t}\n")
        w.write("\t\t\t}\n\t\t}\n\t}\n")   // RightFoot, RightLowerLeg, RightUpperLeg

        w.write("}\n")
    }

    private fun writeFingerChain(
        w:            FileWriter,
        finger:       String,
        joints:       List<String>,
        depth:        Int,
        jointOffsets: Map<Int, com.arhand.util.Vec3>? = null
    ) {
        if (joints.isEmpty()) return
        val indent = "\t".repeat(depth)

        // Map display name back to joint index for bone length lookup
        val jointIndexForName: (String) -> Int? = { name ->
            JOINT_NAMES.entries.firstOrNull { it.value == name }?.key
        }

        val jointName = joints[0]
        val jointIdx  = jointIndexForName(jointName)

        // HAND-2 — Use scanned bone length when available.
        // Compute distance between this joint and its parent from restJointPositions.
        val len: Float = if (jointOffsets != null && jointIdx != null && joints.size > 1) {
            val parentName = joints.getOrNull(-1) // not available here; use default
            val nextIdx    = jointIndexForName(joints.getOrNull(1) ?: "")
            if (nextIdx != null) {
                val cur  = jointOffsets[jointIdx]
                val next = jointOffsets[nextIdx]
                if (cur != null && next != null) {
                    val dx = next.x - cur.x; val dy = next.y - cur.y; val dz = next.z - cur.z
                    kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) * 100f  // metres → cm
                } else BONE_LENGTH[jointIdx] ?: 0.03f
            } else BONE_LENGTH[jointIdx] ?: 0.03f
        } else BONE_LENGTH[jointIdx] ?: 0.03f

        val offset = "%.4f %.4f %.4f".format(0f, len, 0f)

        // C3: Tip joints (ThumbTip, IndexTip, etc.) are written as named stub bones
        // with CHANNELS 0 — they appear in Blender as IK-target bones without requiring
        // motion data channels in the MOTION section.
        val isTipJoint = jointName.endsWith("Tip")

        w.write("${indent}JOINT $jointName\n")
        w.write("$indent{\n")
        w.write("$indent\tOFFSET $offset\n")

        if (isTipJoint) {
            // Named end-site stub: 0 channels, leaf End Site
            w.write("$indent\tCHANNELS 0\n")
            val endLen = len * 0.6f
            w.write("$indent\tEnd Site\n")
            w.write("$indent\t{\n")
            w.write("$indent\t\tOFFSET 0.0000 %.4f 0.0000\n".format(endLen))
            w.write("$indent\t}\n")
        } else if (joints.size > 1) {
            w.write("$indent\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
            // Recurse into the remaining joints in this finger chain
            writeFingerChain(w, finger, joints.subList(1, joints.size), depth + 1, jointOffsets)
        } else {
            w.write("$indent\tCHANNELS 3 Zrotation Xrotation Yrotation\n")
            // Leaf — append End Site
            val endLen = len * 0.6f
            w.write("$indent\tEnd Site\n")
            w.write("$indent\t{\n")
            w.write("$indent\t\tOFFSET 0.0000 %.4f 0.0000\n".format(endLen))
            w.write("$indent\t}\n")
        }

        w.write("$indent}\n")
    }

    // ─── BVH Motion section ───────────────────────────────────────────────────

    private fun writeBvhMotion(w: FileWriter, snapshot: List<RecordedFrame>) {
        val frameCount  = snapshot.size

        // playback speed is correct even when FrameThrottler varies the capture rate.
        val frameTimeSeconds = if (snapshot.size >= 2) {
            val totalMs = snapshot.last().timestampMs - snapshot.first().timestampMs
            (totalMs.toDouble() / (snapshot.size - 1)) / 1000.0
        } else {
            1.0 / FPS
        }

        w.write("MOTION\n")
        w.write("Frames: $frameCount\n")
        w.write("Frame Time: %.6f\n".format(frameTimeSeconds))

        // ARCH-2 — Channel write order must exactly match the HIERARCHY section:
        //
        // Hips:            Xpos Ypos Zpos Zrot Xrot Yrot
        // Spine:           Zrot Xrot Yrot
        // Chest:           Zrot Xrot Yrot
        // Neck:            Zrot Xrot Yrot
        // Head:            Zrot Xrot Yrot
        // LeftUpperArm:    Zrot Xrot Yrot
        // LeftLowerArm:    Zrot Xrot Yrot
        // LeftHand:        Zrot Xrot Yrot
        // RightUpperArm:   Zrot Xrot Yrot
        // RightLowerArm:   Zrot Xrot Yrot
        // Wrist:           Zrot Xrot Yrot          ← hand root (was BVH root in hand-only)
        // ThumbCMC..DIP:   Zrot Xrot Yrot  (×3)
        // IndexMCP..DIP:   Zrot Xrot Yrot  (×3)
        // MiddleMCP..DIP:  Zrot Xrot Yrot  (×3)
        // RingMCP..DIP:    Zrot Xrot Yrot  (×3)
        // PinkyMCP..DIP:   Zrot Xrot Yrot  (×3)
        // FaceBlendshapes: 7 floats
        // LeftUpperLeg:    Zrot Xrot Yrot
        // LeftLowerLeg:    Zrot Xrot Yrot
        // LeftFoot:        Zrot Xrot Yrot
        // RightUpperLeg:   Zrot Xrot Yrot
        // RightLowerLeg:   Zrot Xrot Yrot
        // RightFoot:       Zrot Xrot Yrot

        // Body joints written in hierarchy order (matches HIERARCHY section)
        val bodyWriteOrder = listOf(
            BodyJoint.HIPS,
            BodyJoint.SPINE, BodyJoint.CHEST, BodyJoint.NECK, BodyJoint.HEAD,
            BodyJoint.LEFT_UPPER_ARM, BodyJoint.LEFT_LOWER_ARM,
            BodyJoint.RIGHT_UPPER_ARM, BodyJoint.RIGHT_LOWER_ARM
        )
        val legWriteOrder = listOf(
            BodyJoint.LEFT_UPPER_LEG, BodyJoint.LEFT_LOWER_LEG, BodyJoint.LEFT_FOOT,
            BodyJoint.RIGHT_UPPER_LEG, BodyJoint.RIGHT_LOWER_LEG, BodyJoint.RIGHT_FOOT
        )

        for (frame in snapshot) {
            val sb = StringBuilder()
            val body = frame.bodyJoints

            // ── Hips root (position + rotation) ─────────────────────────────
            val hipsRot = body?.get(BodyJoint.HIPS) ?: Quaternion.IDENTITY
            val (hipsZ, hipsX, hipsY) = quaternionToZXY(hipsRot)
            sb.append("0.0000 0.0000 0.0000 ")   // Hips position (centred)
            sb.append("%.4f %.4f %.4f ".format(hipsZ, hipsX, hipsY))

            // ── Spine chain + arms (no position channels) ────────────────────
            for (joint in bodyWriteOrder.drop(1)) {  // skip Hips (written above)
                val q = body?.get(joint) ?: Quaternion.IDENTITY
                val (ez, ex, ey) = quaternionToZXY(q)
                sb.append("%.4f %.4f %.4f ".format(ez, ex, ey))
            }

            // LeftHand stub (not in BodyJoint enum — write identity)
            sb.append("0.0000 0.0000 0.0000 ")

            // ── Wrist (hand root) + finger chains ────────────────────────────
            for ((idx, jointIdx) in HIERARCHY_ORDER.withIndex()) {
                val q = frame.jointRotations[jointIdx] ?: Quaternion.IDENTITY
                val (ez, ex, ey) = quaternionToZXY(q)

                if (jointIdx == BoneRetargeter.JOINT_WRIST) {
                    // Wrist rotation only (position now on Hips root)
                    sb.append("%.4f %.4f %.4f ".format(ez, ex, ey))
                } else {
                    sb.append("%.4f %.4f %.4f".format(ez, ex, ey))
                    if (idx < HIERARCHY_ORDER.size - 1) sb.append(' ')
                }
            }

            // ── Face blend-shapes ─────────────────────────────────────────────
            val fe = frame.faceExpressions
            if (fe != null) {
                sb.append(" %.4f %.4f %.4f %.4f %.4f %.4f %.4f".format(
                    fe.leftBlink, fe.rightBlink, fe.jawOpen,
                    fe.leftBrowRaise, fe.rightBrowRaise,
                    fe.mouthSmile, fe.mouthSmile
                ))
            } else {
                sb.append(" 0.0000 0.0000 0.0000 0.0000 0.0000 0.0000 0.0000")
            }

            // ── Leg joints ────────────────────────────────────────────────────
            for (joint in legWriteOrder) {
                val q = body?.get(joint) ?: Quaternion.IDENTITY
                val (ez, ex, ey) = quaternionToZXY(q)
                sb.append(" %.4f %.4f %.4f".format(ez, ex, ey))
            }

            w.write(sb.toString())
            w.write("\n")
        }
    }

    // ─── Euler conversion ─────────────────────────────────────────────────────

    /**
     * Decompose a [Quaternion] into ZXY intrinsic Euler angles (degrees).
     *
     * ZXY is the standard decomposition order used by most BVH importers when
     * reconstructing hand joint rotations from motion capture data.
     *
     * Returns a [Triple] of (Z, X, Y) angles in degrees.
     *
     * Reference: Ken Shoemake, "Euler Angle Conversion", Graphics Gems IV, 1994.
     */
    private fun quaternionToZXY(q: Quaternion): Triple<Float, Float, Float> {
        val x = q.x; val y = q.y; val z = q.z; val w = q.w

        // Convert quaternion → rotation matrix elements needed for ZXY decomposition.
        // R = Rz * Rx * Ry  (intrinsic ZXY ≡ extrinsic YXZ)
        //
        // Key matrix elements (row-major):
        //   R[2][1] =  2(xy + wz)         ← sinX
        //   R[2][0] =  2(yz - wx)         ← -sinZ * cosX  (used for Z extraction)
        //   R[2][2] =  1 - 2(x² + y²)     ← cosZ * cosX   (used for Z extraction)
        //   R[1][1] =  1 - 2(x² + z²)     ← cosX * cosY   (used for Y extraction)
        //   R[0][1] =  2(xy - wz)         ← sinX * sinY * cosZ + ...  <- not needed
        //   R[0][0] =  ... not needed
        //
        // Standard ZXY extraction:
        //   sinX        =  2(w*z + x*y)          (R[2][1])
        //   cosX        =  sqrt(1 - sinX²)        (always non-negative; no quadrant issue for X)
        //   sinZ/cosX   =  2(w*x - y*z) / cosX   (derived from R[2][0] = -sinZ*cosX)
        //   cosZ/cosX   =  (1 - 2(x²+y²)) / cosX (derived from R[2][2] = cosZ*cosX)
        //   sinY/cosX   = -2(w*y - x*z) / cosX   (derived from R[2][?])  <- handled via atan2 below
        //   cosY/cosX   =  (1 - 2(y²+z²)) / cosX (derived from R[0][0])
        //
        // When cosX ≈ 0 (gimbal: X = ±90°) we fall back to setting Y = 0 and solving
        // for Z from the remaining off-diagonal element — standard gimbal lock handling.

        val sinX = (2f * (w * z + x * y)).coerceIn(-1f, 1f)
        val ex   = asin(sinX)
        val cosX = kotlin.math.cos(ex)

        val ey: Float
        val ez: Float

        if (cosX > 1e-5f) {
            // Normal case
            val sinZ = 2f * (w * x - y * z)
            val cosZ = 1f - 2f * (x * x + y * y)
            val sinY = -2f * (w * y - x * z)
            val cosY = 1f - 2f * (y * y + z * z)
            ez = atan2(sinZ, cosZ)
            ey = atan2(sinY, cosY)
        } else {
            // Gimbal lock: X = ±90°. Set Y = 0, solve Z from off-diagonal.
            val sinZ = 2f * (w * x + y * z)
            val cosZ = 1f - 2f * (x * x + z * z)
            ez = atan2(sinZ, cosZ)
            ey = 0f
        }

        return Triple(ez * RAD_TO_DEG, ex * RAD_TO_DEG, ey * RAD_TO_DEG)
    }
}
