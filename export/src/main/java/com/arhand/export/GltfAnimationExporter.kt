package com.arhand.export

import com.arhand.mocap.BoneRetargeter
import com.arhand.mocap.LoadedAsset
import com.arhand.mocap.MotionRecorder
import com.arhand.mocap.Quaternion
import com.arhand.mocap.WristTransform
import com.arhand.util.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exports a recorded motion capture session as a self-contained animated GLB file.
 *
 * The output is a standard binary glTF 2.0 file that contains:
 *   - The skinned hand mesh geometry (from [LoadedAsset], if present)
 *   - A `skins` entry with inverse bind matrices and a joint node hierarchy
 *   - An `animations` block with one animation named "HandMocap" containing:
 *       • One rotation sampler per joint (16 joints × 1 sampler = 16 samplers)
 *       • One translation sampler for the wrist root
 *       • Keyframe times and quaternion values stored as binary accessors in the BIN chunk
 *
 * The file opens in any glTF viewer (Blender, three.js, Babylon.js, Windows 3D Viewer,
 * model-viewer) with no separate mocap file — the animation is baked in.
 *
 * Usage:
 * ```kotlin
 * val file = GltfAnimationExporter.export(
 *     frames    = motionRecorder.frameSnapshot(),
 *     asset     = loadedAsset,
 *     outputDir = getExternalFilesDir("mocap")!!,
 *     label     = "take_01"
 * )
 * ```
 *
 * Thread safety: [export] is a pure function — no shared state. Run it on Dispatchers.IO.
 */
object GltfAnimationExporter {

    // ─── glTF constants ───────────────────────────────────────────────────────
    private const val GLB_MAGIC        = 0x46546C67   // "glTF"
    private const val GLB_VERSION      = 2
    private const val CHUNK_JSON       = 0x4E4F534A   // "JSON"
    private const val CHUNK_BIN        = 0x004E4942   // "BIN\0"
    private const val COMPONENT_FLOAT  = 5126
    private const val COMPONENT_USHORT = 5123
    private const val TYPE_SCALAR      = "SCALAR"
    private const val TYPE_VEC3        = "VEC3"
    private const val TYPE_VEC4        = "VEC4"
    private const val TYPE_MAT4        = "MAT4"
    private const val PATH_ROTATION    = "rotation"
    private const val PATH_TRANSLATION = "translation"
    private const val INTERP_LINEAR    = "LINEAR"

    // Joint index → node name (matches BoneRetargeter constants)
    private val JOINT_NODE_NAMES = mapOf(
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

    // Joint ordering for the glTF skin joints array and node list
    private val JOINT_ORDER = MotionRecorder.HIERARCHY_ORDER

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Build and write a self-contained animated GLB.
     *
     * @param frames     Snapshot from [MotionRecorder.frameSnapshot]. Must be non-empty.
     * @param asset      Loaded asset supplying mesh geometry and inverse bind matrices.
     *                   Pass [LoadedAsset] with empty [LoadedAsset.meshPositions] to export
     *                   animation-only (no visible mesh — useful for retargeting in other tools).
     * @param outputDir  Writable directory for the output file.
     * @param label      File name stem. Illegal characters are stripped.
     * @return           The written [File], or null on empty input or IO failure.
     */
    fun export(
        frames:    List<MotionRecorder.RecordedFrame>,
        asset:     LoadedAsset,
        outputDir: File,
        label:     String = "animation"
    ): File? {
        if (frames.isEmpty()) return null

        val safeName = label.replace(' ', '_').replace(Regex("[^A-Za-z0-9_\\-]"), "")
        val outFile  = File(outputDir, "$safeName.glb")

        return try {
            val glbBytes = buildGlb(frames, asset)
            outputDir.mkdirs()
            FileOutputStream(outFile).use { it.write(glbBytes) }
            outFile
        } catch (e: Exception) {
            null
        }
    }

    // ─── GLB builder ─────────────────────────────────────────────────────────

    private fun buildGlb(
        frames: List<MotionRecorder.RecordedFrame>,
        asset:  LoadedAsset
    ): ByteArray {
        // 1. Accumulate all binary data into a single BIN buffer.
        //    We track byte offsets as we append, then reference them in accessors.
        val bin    = mutableListOf<ByteArray>()
        var binOff = 0

        data class AccessorDef(
            val bufferViewIdx: Int,
            val componentType: Int,
            val count: Int,
            val type: String,
            val min: List<Float>? = null,
            val max: List<Float>? = null
        )
        data class BufferViewDef(val byteOffset: Int, val byteLength: Int)

        val bufferViews = mutableListOf<BufferViewDef>()
        val accessors   = mutableListOf<AccessorDef>()

        fun appendBin(bytes: ByteArray): Int {
            val off = binOff
            bin.add(bytes)
            binOff += bytes.size
            return off
        }

        fun addBufferView(bytes: ByteArray): Int {
            val off = appendBin(bytes)
            bufferViews.add(BufferViewDef(off, bytes.size))
            return bufferViews.size - 1
        }

        fun addAccessor(bvIdx: Int, componentType: Int, count: Int, type: String,
                        min: List<Float>? = null, max: List<Float>? = null): Int {
            accessors.add(AccessorDef(bvIdx, componentType, count, type, min, max))
            return accessors.size - 1
        }

        // ── Keyframe times ────────────────────────────────────────────────────
        val frameCount = frames.size
        val times      = FloatArray(frameCount) { i ->
            frames[i].timestampMs / 1000f   // milliseconds → seconds
        }
        val maxTime    = times.last()
        val timeBytes  = floatArrayToBytes(times)
        val timeBvIdx  = addBufferView(timeBytes)
        val timeAccIdx = addAccessor(
            timeBvIdx, COMPONENT_FLOAT, frameCount, TYPE_SCALAR,
            min = listOf(times.first()), max = listOf(maxTime)
        )

        // ── Per-joint rotation channels ───────────────────────────────────────
        // Each joint gets one sampler: input = timeAccIdx, output = rotation accessor
        data class AnimChannel(val samplerIdx: Int, val nodeIdx: Int, val path: String)
        val animChannels  = mutableListOf<AnimChannel>()
        val animSamplers  = mutableListOf<Pair<Int, Int>>()   // (inputAcc, outputAcc)

        // Node index for each joint = position in JOINT_ORDER + meshNodeOffset
        // We'll have: node 0 = mesh (if present), nodes 1..16 = joints
        val meshNodeCount   = if (asset.meshPositions.isNotEmpty()) 1 else 0
        val jointNodeOffset = meshNodeCount   // joint nodes start at this index

        for (jointIdx in JOINT_ORDER) {
            val nodeIdx = jointNodeOffset + JOINT_ORDER.indexOf(jointIdx)

            // Build quaternion array for this joint across all frames (x,y,z,w)
            val quatBytes = ByteBuffer
                .allocate(frameCount * 4 * 4)
                .order(ByteOrder.LITTLE_ENDIAN)

            for (frame in frames) {
                val q = frame.jointRotations[jointIdx] ?: Quaternion.IDENTITY
                quatBytes.putFloat(q.x)
                quatBytes.putFloat(q.y)
                quatBytes.putFloat(q.z)
                quatBytes.putFloat(q.w)
            }

            val rotBvIdx  = addBufferView(quatBytes.array())
            val rotAccIdx = addAccessor(rotBvIdx, COMPONENT_FLOAT, frameCount, TYPE_VEC4)

            val samplerIdx = animSamplers.size
            animSamplers.add(timeAccIdx to rotAccIdx)
            animChannels.add(AnimChannel(samplerIdx, nodeIdx, PATH_ROTATION))
        }

        // ── Wrist root translation channel ────────────────────────────────────
        val wristTransBytes = ByteBuffer
            .allocate(frameCount * 3 * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (frame in frames) {
            val pos = frame.wristTransform.position
            wristTransBytes.putFloat(pos.x)
            wristTransBytes.putFloat(pos.y)
            wristTransBytes.putFloat(pos.z)
        }
        val wristTransBvIdx  = addBufferView(wristTransBytes.array())
        val wristTransAccIdx = addAccessor(wristTransBvIdx, COMPONENT_FLOAT, frameCount, TYPE_VEC3)
        val wristSamplerIdx  = animSamplers.size
        animSamplers.add(timeAccIdx to wristTransAccIdx)
        animChannels.add(AnimChannel(wristSamplerIdx, jointNodeOffset, PATH_TRANSLATION))

        // ── Mesh geometry (optional) ──────────────────────────────────────────
        var meshAccessorStart = -1
        var posAccIdx         = -1
        var skinWeightStart   = -1   // accessor index for JOINTS_0 / WEIGHTS_0

        if (asset.meshPositions.isNotEmpty()) {
            val posBytes = floatArrayToBytes(asset.meshPositions)
            val posBvIdx = addBufferView(posBytes)

            val vc = asset.meshPositions.size / 3
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (i in 0 until vc) {
                val x = asset.meshPositions[i * 3]
                val y = asset.meshPositions[i * 3 + 1]
                val z = asset.meshPositions[i * 3 + 2]
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }

            meshAccessorStart = accessors.size
            posAccIdx = addAccessor(
                posBvIdx, COMPONENT_FLOAT, vc, TYPE_VEC3,
                min = listOf(minX, minY, minZ), max = listOf(maxX, maxY, maxZ)
            )
        }

        // ── Inverse bind matrices ─────────────────────────────────────────────
        val ibmCount = JOINT_ORDER.size
        val ibmBytes = ByteBuffer.allocate(ibmCount * 16 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in JOINT_ORDER.indices) {
            val mat = asset.inverseBindMatrices.getOrNull(i) ?: identityMatrix()
            mat.forEach { ibmBytes.putFloat(it) }
        }
        val ibmBvIdx  = addBufferView(ibmBytes.array())
        val ibmAccIdx = addAccessor(ibmBvIdx, COMPONENT_FLOAT, ibmCount, TYPE_MAT4)

        // 2. Build JSON ────────────────────────────────────────────────────────
        val root = JSONObject()
        root.put("asset", JSONObject().apply {
            put("version", "2.0")
            put("generator", "Handy AR — GltfAnimationExporter")
        })

        // Nodes: [mesh node (optional)] + [16 joint nodes]
        //
        // glTF skinning spec: each joint node's global transform = product of all ancestor
        // local transforms up to the skeleton root. Without parent-child "children" links,
        // every joint's local transform IS its world transform — correct only for world-space
        // absolute rotations, not the relative shortest-arc rotations produced by BoneRetargeter.
        // We must wire up the hand skeleton topology:
        //   Wrist → [ThumbCMC→ThumbMCP→ThumbIP,
        //            IndexMCP→IndexPIP→IndexDIP,
        //            MiddleMCP→MiddlePIP→MiddleDIP,
        //            RingMCP→RingPIP→RingDIP,
        //            PinkyMCP→PinkyPIP→PinkyDIP]
        //
        // jointNodeOffset maps from jointIdx to its node array position.
        fun jointNodeIdx(j: Int) = jointNodeOffset + JOINT_ORDER.indexOf(j)

        // Build children map: parent jointIdx → list of child jointIdx
        // Matches BoneRetargeter.BONE_SEGMENTS topology (base → tip means parent → child).
        val jointChildren = mapOf(
            BoneRetargeter.JOINT_WRIST      to listOf(
                BoneRetargeter.JOINT_THUMB_CMC,
                BoneRetargeter.JOINT_INDEX_MCP,
                BoneRetargeter.JOINT_MIDDLE_MCP,
                BoneRetargeter.JOINT_RING_MCP,
                BoneRetargeter.JOINT_PINKY_MCP
            ),
            BoneRetargeter.JOINT_THUMB_CMC  to listOf(BoneRetargeter.JOINT_THUMB_MCP),
            BoneRetargeter.JOINT_THUMB_MCP  to listOf(BoneRetargeter.JOINT_THUMB_IP),
            BoneRetargeter.JOINT_INDEX_MCP  to listOf(BoneRetargeter.JOINT_INDEX_PIP),
            BoneRetargeter.JOINT_INDEX_PIP  to listOf(BoneRetargeter.JOINT_INDEX_DIP),
            BoneRetargeter.JOINT_MIDDLE_MCP to listOf(BoneRetargeter.JOINT_MIDDLE_PIP),
            BoneRetargeter.JOINT_MIDDLE_PIP to listOf(BoneRetargeter.JOINT_MIDDLE_DIP),
            BoneRetargeter.JOINT_RING_MCP   to listOf(BoneRetargeter.JOINT_RING_PIP),
            BoneRetargeter.JOINT_RING_PIP   to listOf(BoneRetargeter.JOINT_RING_DIP),
            BoneRetargeter.JOINT_PINKY_MCP  to listOf(BoneRetargeter.JOINT_PINKY_PIP),
            BoneRetargeter.JOINT_PINKY_PIP  to listOf(BoneRetargeter.JOINT_PINKY_DIP)
        )

        val nodesArr = JSONArray()

        if (asset.meshPositions.isNotEmpty()) {
            // Mesh node — references mesh 0 and skin 0
            val meshNode = JSONObject().apply {
                put("name", "HandMesh")
                put("mesh", 0)
                put("skin", 0)
            }
            nodesArr.put(meshNode)
        }

        // Joint nodes — include "children" arrays for proper hierarchy composition.
        // Each non-root joint also gets a rest-pose "translation": glTF nodes default
        // to [0,0,0], which would place every joint at its parent's origin — the
        // hierarchy would be topologically correct but geometrically collapsed to a
        // point. Derive it from the asset's own bind-pose bone direction (falling
        // back to the generic symmetric pose) scaled by the same per-joint bone
        // lengths MotionRecorder's BVH export uses, so the two exporters agree.
        val fallbackBindPose = BoneRetargeter.symmetricBindPose()
        for (jointIdx in JOINT_ORDER) {
            val name     = JOINT_NODE_NAMES[jointIdx] ?: "Joint$jointIdx"
            val children = jointChildren[jointIdx]
            val node     = JSONObject().put("name", name)

            if (jointIdx != BoneRetargeter.JOINT_WRIST) {
                val dir = asset.bindPose.boneDirections[jointIdx]
                    ?: fallbackBindPose.boneDirections[jointIdx]
                    ?: Vec3(0f, 1f, 0f)
                val length = MotionRecorder.BONE_LENGTH[jointIdx] ?: 0.03f
                val t = dir * length
                node.put("translation", JSONArray().put(t.x.toDouble()).put(t.y.toDouble()).put(t.z.toDouble()))
            }

            if (!children.isNullOrEmpty()) {
                val childArr = JSONArray()
                children.forEach { childArr.put(jointNodeIdx(it)) }
                node.put("children", childArr)
            }
            nodesArr.put(node)
        }

        root.put("nodes", nodesArr)

        // Scene — root node
        root.put("scene", 0)
        root.put("scenes", JSONArray().put(
            JSONObject().put("nodes", JSONArray().put(0))
        ))

        // Skin
        val skinJointsArr = JSONArray()
        for (i in JOINT_ORDER.indices) skinJointsArr.put(jointNodeOffset + i)

        root.put("skins", JSONArray().put(JSONObject().apply {
            put("name", "HandSkin")
            put("inverseBindMatrices", ibmAccIdx)
            put("joints", skinJointsArr)
            put("skeleton", jointNodeOffset)   // wrist is the root joint
        }))

        // Mesh (optional)
        if (posAccIdx >= 0) {
            val attribs = JSONObject().put("POSITION", posAccIdx)
            root.put("meshes", JSONArray().put(JSONObject().apply {
                put("name", "HandMesh")
                put("primitives", JSONArray().put(JSONObject().apply {
                    put("attributes", attribs)
                    put("mode", 4)   // TRIANGLES
                }))
            }))
        }

        // Animation
        val samplerJsonArr = JSONArray()
        for ((inputAcc, outputAcc) in animSamplers) {
            samplerJsonArr.put(JSONObject().apply {
                put("input", inputAcc)
                put("output", outputAcc)
                put("interpolation", INTERP_LINEAR)
            })
        }

        val channelJsonArr = JSONArray()
        for (ch in animChannels) {
            channelJsonArr.put(JSONObject().apply {
                put("sampler", ch.samplerIdx)
                put("target", JSONObject().apply {
                    put("node", ch.nodeIdx)
                    put("path", ch.path)
                })
            })
        }

        root.put("animations", JSONArray().put(JSONObject().apply {
            put("name", "HandMocap")
            put("samplers", samplerJsonArr)
            put("channels", channelJsonArr)
        }))

        // Accessors
        val accessorArr = JSONArray()
        for (acc in accessors) {
            accessorArr.put(JSONObject().apply {
                put("bufferView", acc.bufferViewIdx)
                put("componentType", acc.componentType)
                put("count", acc.count)
                put("type", acc.type)
                acc.min?.let { put("min", JSONArray(it)) }
                acc.max?.let { put("max", JSONArray(it)) }
            })
        }
        root.put("accessors", accessorArr)

        // BufferViews
        val bvArr = JSONArray()
        for (bv in bufferViews) {
            bvArr.put(JSONObject().apply {
                put("buffer", 0)
                put("byteOffset", bv.byteOffset)
                put("byteLength", bv.byteLength)
            })
        }
        root.put("bufferViews", bvArr)

        // Buffer
        root.put("buffers", JSONArray().put(JSONObject().put("byteLength", binOff)))

        // 3. Serialize and pack into GLB ──────────────────────────────────────
        val jsonBytes    = root.toString().toByteArray(Charsets.UTF_8)
        val jsonPad      = (4 - jsonBytes.size % 4) % 4
        val jsonPadded   = jsonBytes + ByteArray(jsonPad) { 0x20 }

        val allBin       = ByteArray(binOff).also { out ->
            var pos = 0
            for (chunk in bin) { chunk.copyInto(out, pos); pos += chunk.size }
        }
        val binPad       = (4 - allBin.size % 4) % 4
        val binPadded    = allBin + ByteArray(binPad) { 0x00 }

        val totalLength  = 12 + 8 + jsonPadded.size + 8 + binPadded.size
        val glb          = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)

        glb.putInt(GLB_MAGIC)
        glb.putInt(GLB_VERSION)
        glb.putInt(totalLength)
        glb.putInt(jsonPadded.size);  glb.putInt(CHUNK_JSON); glb.put(jsonPadded)
        glb.putInt(binPadded.size);   glb.putInt(CHUNK_BIN);  glb.put(binPadded)

        return glb.array()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun floatArrayToBytes(arr: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        arr.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun identityMatrix(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )
}
