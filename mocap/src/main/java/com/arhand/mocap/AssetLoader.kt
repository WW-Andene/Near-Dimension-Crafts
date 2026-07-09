package com.arhand.mocap

import android.content.Context
import android.net.Uri
import com.arhand.util.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ─── Asset slot ──────────────────────────────────────────────────────────────

/**
 * ASSET-2 — Identifies how a loaded GLB is to be used by the retargeting pipeline.
 *
 * Auto-detected in [AssetLoader.detectSlot] by inspecting the parsed joint list.
 * Hand rigs have ≤ 20 joints; full-body rigs have ≥ 21 and include spine/hip names.
 */
enum class AssetSlot {
    /** Default hand puppet — 16 joints max, hand-only retargeting. */
    HAND_PUPPET,
    /** User-uploaded full-body GLB/VRM — spine, hips, arms, legs present. */
    BODY_CHARACTER
}

// ─── Body joint enum ─────────────────────────────────────────────────────────

/**
 * ASSET-4 — Unity HumanBodyBones naming for full-body retargeting.
 *
 * Used as the key in [LoadedAsset.bodyJointMap] and [BodyRetargetResult.joints].
 * Names match VMC v2.3 /VMC/Ext/Bon/Rot bone name strings exactly.
 */
enum class BodyJoint(val vmcName: String) {
    HIPS            ("Hips"),
    SPINE           ("Spine"),
    CHEST           ("Chest"),
    NECK            ("Neck"),
    HEAD            ("Head"),
    LEFT_UPPER_ARM  ("LeftUpperArm"),
    LEFT_LOWER_ARM  ("LeftLowerArm"),
    RIGHT_UPPER_ARM ("RightUpperArm"),
    RIGHT_LOWER_ARM ("RightLowerArm"),
    LEFT_UPPER_LEG  ("LeftUpperLeg"),
    LEFT_LOWER_LEG  ("LeftLowerLeg"),
    RIGHT_UPPER_LEG ("RightUpperLeg"),
    RIGHT_LOWER_LEG ("RightLowerLeg"),
    LEFT_FOOT       ("LeftFoot"),
    RIGHT_FOOT      ("RightFoot")
}

// ─── VRM joint name mapper ───────────────────────────────────────────────────

/**
 * ASSET-4 — Maps known VRM / Mixamo / Unreal bone name variants to [BodyJoint] slots.
 *
 * Covers:
 *   - VRM 0.x  : J_Bip_C_Hips, J_Bip_L_UpperArm, …
 *   - VRM 1.0  : hips, leftUpperArm, …  (camelCase)
 *   - Mixamo   : mixamorig:Hips, mixamorig:LeftArm, …
 *   - Unreal   : pelvis, upperarm_l, …
 *   - Generic  : Hips, Spine, Chest, LeftUpperArm, … (Unity default)
 *
 * Matching is case-insensitive and strips common prefixes before lookup.
 */
object VrmJointMapper {

    /** Returns the [BodyJoint] for a glTF node name, or null if not recognised. */
    fun map(nodeName: String): BodyJoint? {
        val n = normalise(nodeName)
        return TABLE[n]
    }

    private fun normalise(raw: String): String =
        raw.lowercase()
            .removePrefix("j_bip_c_")
            .removePrefix("j_bip_l_")
            .removePrefix("j_bip_r_")
            .removePrefix("mixamorig:")
            .removePrefix("mixamorig_")
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")

    private val TABLE: Map<String, BodyJoint> = buildMap {
        // ── Hips / Pelvis ──────────────────────────────────────────────────
        for (k in listOf("hips","hip","pelvis","root")) put(k, BodyJoint.HIPS)

        // ── Spine ──────────────────────────────────────────────────────────
        for (k in listOf("spine","spine1","spine01","lowerback")) put(k, BodyJoint.SPINE)

        // ── Chest ──────────────────────────────────────────────────────────
        for (k in listOf("chest","spine2","spine02","upperchest","thorax")) put(k, BodyJoint.CHEST)

        // ── Neck ───────────────────────────────────────────────────────────
        for (k in listOf("neck","neck1","neck01")) put(k, BodyJoint.NECK)

        // ── Head ───────────────────────────────────────────────────────────
        for (k in listOf("head","head1")) put(k, BodyJoint.HEAD)

        // ── Left arm ───────────────────────────────────────────────────────
        for (k in listOf("leftupperarm","leftarm","lupperarm","larm","arm_l","upperarml",
                         "shoulder_l","leftshoulder")) put(k, BodyJoint.LEFT_UPPER_ARM)
        for (k in listOf("leftlowerarm","leftforearm","lforearm","lowerarml","forearm_l",
                         "elbow_l","leftforearm")) put(k, BodyJoint.LEFT_LOWER_ARM)

        // ── Right arm ──────────────────────────────────────────────────────
        for (k in listOf("rightupperarm","rightarm","rupperarm","rarm","arm_r","upperarmr",
                         "shoulder_r","rightshoulder")) put(k, BodyJoint.RIGHT_UPPER_ARM)
        for (k in listOf("rightlowerarm","rightforearm","rforearm","lowerarmr","forearm_r",
                         "elbow_r","rightforearm")) put(k, BodyJoint.RIGHT_LOWER_ARM)

        // ── Left leg ───────────────────────────────────────────────────────
        for (k in listOf("leftupperleg","leftthigh","lthigh","lupperleg","thigh_l",
                         "upleg_l","leftupleg")) put(k, BodyJoint.LEFT_UPPER_LEG)
        for (k in listOf("leftlowerleg","leftshin","lshin","llowerleg","shin_l",
                         "leg_l","calf_l","leftleg")) put(k, BodyJoint.LEFT_LOWER_LEG)

        // ── Right leg ──────────────────────────────────────────────────────
        for (k in listOf("rightupperleg","rightthigh","rthigh","rupperleg","thigh_r",
                         "upleg_r","rightupleg")) put(k, BodyJoint.RIGHT_UPPER_LEG)
        for (k in listOf("rightlowerleg","rightshin","rshin","rlowerleg","shin_r",
                         "leg_r","calf_r","rightleg")) put(k, BodyJoint.RIGHT_LOWER_LEG)

        // ── Feet ───────────────────────────────────────────────────────────
        for (k in listOf("leftfoot","lfoot","foot_l","ankle_l")) put(k, BodyJoint.LEFT_FOOT)
        for (k in listOf("rightfoot","rfoot","foot_r","ankle_r")) put(k, BodyJoint.RIGHT_FOOT)
    }
}

/**
 * Loads a user-provided GLB asset from storage, extracts its skin joint hierarchy and
 * inverse bind matrices, and produces a [LoadedAsset] ready for [BoneRetargeter] and
 * the forthcoming [SkinnedMeshRenderer].
 *
 * Supported asset formats:
 *   - Binary glTF 2.0 (.glb) with a `skins` entry — full skeletal mesh.
 *   - GLB with no skin — mesh is loaded but retargeted via the symmetric bind pose.
 *   - No asset / null URI — falls back to [AssetLoader.DEFAULT_PUPPET].
 *
 * Thread safety: [load] is a blocking call intended to run on Dispatchers.IO.
 *
 * Usage:
 * ```kotlin
 * viewModelScope.launch(Dispatchers.IO) {
 *     val asset = AssetLoader.load(context, uri)
 *     withContext(Dispatchers.Main) { viewModel.onAssetLoaded(asset) }
 * }
 * ```
 */
object AssetLoader {

    // ─── GLB binary constants ─────────────────────────────────────────────────
    private const val GLB_MAGIC   = 0x46546C67  // "glTF"
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON  = 0x4E4F534A  // "JSON"
    private const val CHUNK_BIN   = 0x004E4942  // "BIN\0"

    // ─── glTF accessor component types ───────────────────────────────────────
    private const val COMPONENT_FLOAT = 5126

    /**
     * Load a [LoadedAsset] from [uri]. Returns [DEFAULT_PUPPET] if uri is null,
     * the file is unreadable, or parsing fails.
     *
     * @param context Android context for ContentResolver access.
     * @param uri     Content URI from the file picker, or null to get the default puppet.
     */
    fun load(context: Context, uri: Uri?): LoadedAsset {
        if (uri == null) return DEFAULT_PUPPET
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseGlb(stream)
            } ?: DEFAULT_PUPPET
        } catch (e: Exception) {
            DEFAULT_PUPPET
        }
    }

    /**
     * ASSET-3 — Load a [LoadedAsset] bundled in the APK's assets/ folder.
     *
     * @param context  Android context for [android.content.res.AssetManager] access.
     * @param filename Relative path inside assets/ (e.g. "models/hand_default.glb").
     */
    fun loadFromAssets(context: Context, filename: String): LoadedAsset {
        return try {
            context.assets.open(filename).use { stream -> parseGlb(stream) }
        } catch (e: Exception) {
            DEFAULT_PUPPET
        }
    }

    /**
     * ASSET-2 — Infer the [AssetSlot] from a loaded asset's joint count and topology.
     *
     * Heuristic: full-body rigs have ≥ 21 joints. Hand-only rigs have ≤ 20.
     * This is conservative — a hand rig will never be misclassified as body.
     */
    fun detectSlot(asset: LoadedAsset): AssetSlot =
        if (asset.jointCount >= 21) AssetSlot.BODY_CHARACTER else AssetSlot.HAND_PUPPET

    /** Bundled asset paths inside assets/models/ (fallback) and generated paths. */
    object Bundled {
        const val HAND_GLB      = "models/hand_default.glb"
        const val BODY_GLB      = "models/body_default.glb"
        val ALL = listOf(
            "Hand (built-in)"  to HAND_GLB,
            "Body (built-in)"  to BODY_GLB
        )
    }

    /**
     * Item 3 — Load a bundled GLB from an absolute filesystem path.
     * Used for procedurally generated assets in cacheDir/models/.
     *
     * @param path Absolute path to the .glb file.
     */
    fun loadFromPath(path: String): LoadedAsset {
        return try {
            java.io.File(path).inputStream().use { stream -> parseGlb(stream) }
        } catch (e: Exception) {
            DEFAULT_PUPPET
        }
    }

    // ─── GLB Parser ──────────────────────────────────────────────────────────

    private fun parseGlb(stream: InputStream): LoadedAsset {
        val bytes = stream.readBytes()
        val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Validate header
        val magic   = buf.int
        val version = buf.int
        val _total  = buf.int   // total file length (unused beyond validation)
        if (magic != GLB_MAGIC || version != GLB_VERSION) return DEFAULT_PUPPET

        // Read chunks
        var jsonBytes: ByteArray? = null
        var binBytes:  ByteArray? = null

        while (buf.remaining() >= 8) {
            val chunkLen  = buf.int
            val chunkType = buf.int
            if (chunkLen < 0 || chunkLen > buf.remaining()) break
            val chunkData = ByteArray(chunkLen).also { buf.get(it) }
            when (chunkType) {
                CHUNK_JSON -> jsonBytes = chunkData
                CHUNK_BIN  -> binBytes  = chunkData
            }
        }

        val jsonRaw = jsonBytes ?: return DEFAULT_PUPPET
        val root    = JSONObject(String(jsonRaw, Charsets.UTF_8))

        return buildAsset(root, binBytes)
    }

    // ─── Asset builder ────────────────────────────────────────────────────────

    private fun buildAsset(root: JSONObject, bin: ByteArray?): LoadedAsset {
        val bufferViews = root.optJSONArray("bufferViews")
        val accessors   = root.optJSONArray("accessors")

        // ── Mesh geometry ─────────────────────────────────────────────────────
        val meshPositions = extractMeshPositions(root, accessors, bufferViews, bin)

        // ── Skin + bind pose ──────────────────────────────────────────────────
        val skins = root.optJSONArray("skins")
        if (skins == null || skins.length() == 0) {
            // No skeleton — use mesh with symmetric bind pose
            val asset = LoadedAsset(
                meshPositions = meshPositions,
                jointNodeIndices = emptyList(),
                inverseBindMatrices = emptyList(),
                bindPose = BoneRetargeter.symmetricBindPose(),
                hasSkin = false,
                slot = AssetSlot.HAND_PUPPET,
                bodyJointMap = emptyMap()
            )
            return asset
        }

        val skin        = skins.getJSONObject(0)
        val joints      = skin.getJSONArray("joints")
        val ibmAccessor = skin.optInt("inverseBindMatrices", -1)

        val jointNodeIndices = (0 until joints.length()).map { joints.getInt(it) }

        // Extract inverse bind matrices (one 4×4 float matrix per joint)
        val ibmList: List<FloatArray> = if (ibmAccessor >= 0 && accessors != null && bin != null) {
            extractMat4Array(accessors, bufferViews, bin, ibmAccessor, jointNodeIndices.size)
        } else {
            List(jointNodeIndices.size) { identityMatrix() }
        }

        // Derive bind pose bone directions from the inverse bind matrices
        val bindPose = deriveBindPose(root, jointNodeIndices, ibmList)

        val slot = if (jointNodeIndices.size >= 21) AssetSlot.BODY_CHARACTER else AssetSlot.HAND_PUPPET

        // GAP-2 — Parse morph target delta buffers from mesh primitives[0].targets
        val morphDeltas = extractMorphDeltas(root, accessors, bufferViews, bin)

        return LoadedAsset(
            meshPositions       = meshPositions,
            jointNodeIndices    = jointNodeIndices,
            inverseBindMatrices = ibmList,
            bindPose            = bindPose,
            hasSkin             = true,
            slot                = slot,
            bodyJointMap        = buildBodyJointMap(root, jointNodeIndices),
            blendShapeMap       = VrmBlendShapeParser.parse(root),
            morphDeltas         = morphDeltas
        )
    }

    // ─── Geometry extraction ─────────────────────────────────────────────────

    private fun extractMeshPositions(
        root: JSONObject,
        accessors: JSONArray?,
        bufferViews: JSONArray?,
        bin: ByteArray?
    ): FloatArray {
        if (accessors == null || bufferViews == null || bin == null) return FloatArray(0)

        val meshes = root.optJSONArray("meshes") ?: return FloatArray(0)
        if (meshes.length() == 0) return FloatArray(0)

        val prim       = meshes.getJSONObject(0)
            .optJSONArray("primitives")?.getJSONObject(0) ?: return FloatArray(0)
        val posAccIdx  = prim.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
        if (posAccIdx < 0) return FloatArray(0)

        return extractFloatVec3(accessors, bufferViews, bin, posAccIdx)
    }

    /**
     * GAP-2 — Parse morph target delta buffers from `mesh.primitives[0].targets`.
     *
     * Each target is a JSON object with an optional "POSITION" accessor index.
     * Returns a list of FloatArrays — one per target — each containing (dx, dy, dz)
     * deltas in the same vertex order as [extractMeshPositions]. Targets that lack a
     * POSITION accessor or whose accessor fails to parse are returned as empty arrays
     * (so morph target indices stay stable across the list).
     */
    private fun extractMorphDeltas(
        root: JSONObject,
        accessors: JSONArray?,
        bufferViews: JSONArray?,
        bin: ByteArray?
    ): List<FloatArray> {
        if (accessors == null || bufferViews == null || bin == null) return emptyList()
        val meshes = root.optJSONArray("meshes") ?: return emptyList()
        if (meshes.length() == 0) return emptyList()
        val prim    = meshes.getJSONObject(0)
            .optJSONArray("primitives")?.getJSONObject(0) ?: return emptyList()
        val targets = prim.optJSONArray("targets") ?: return emptyList()

        return (0 until targets.length()).map { i ->
            val target   = targets.getJSONObject(i)
            val posAccIdx = target.optInt("POSITION", -1)
            if (posAccIdx < 0) FloatArray(0)
            else runCatching {
                extractFloatVec3(accessors, bufferViews, bin, posAccIdx)
            }.getOrDefault(FloatArray(0))
        }
    }

    private fun extractFloatVec3(
        accessors: JSONArray,
        bufferViews: JSONArray,
        bin: ByteArray,
        accessorIdx: Int
    ): FloatArray {
        if (accessorIdx >= accessors.length()) return FloatArray(0)
        val acc      = accessors.getJSONObject(accessorIdx)
        if (acc.optInt("componentType") != COMPONENT_FLOAT) return FloatArray(0)
        if (acc.optString("type") != "VEC3") return FloatArray(0)

        val count    = acc.getInt("count")
        val bvIdx    = acc.getInt("bufferView")
        val byteOff  = acc.optInt("byteOffset", 0)

        if (bvIdx >= bufferViews.length()) return FloatArray(0)
        val bv       = bufferViews.getJSONObject(bvIdx)
        val bvOffset = bv.optInt("byteOffset", 0)
        val stride   = bv.optInt("byteStride", 12)  // 12 = 3 floats × 4 bytes

        val result = FloatArray(count * 3)
        val base   = bvOffset + byteOff
        val bbuf   = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until count) {
            result[i * 3]     = bbuf.getFloat(base + i * stride)
            result[i * 3 + 1] = bbuf.getFloat(base + i * stride + 4)
            result[i * 3 + 2] = bbuf.getFloat(base + i * stride + 8)
        }
        return result
    }

    private fun extractMat4Array(
        accessors: JSONArray,
        bufferViews: JSONArray?,
        bin: ByteArray,
        accessorIdx: Int,
        count: Int
    ): List<FloatArray> {
        if (bufferViews == null || accessorIdx >= accessors.length())
            return List(count) { identityMatrix() }

        val acc     = accessors.getJSONObject(accessorIdx)
        val bvIdx   = acc.optInt("bufferView", -1)
        val byteOff = acc.optInt("byteOffset", 0)
        if (bvIdx < 0 || bvIdx >= bufferViews.length()) return List(count) { identityMatrix() }

        val bv      = bufferViews.getJSONObject(bvIdx)
        val bvOff   = bv.optInt("byteOffset", 0)
        val base    = bvOff + byteOff

        return List(count) { i ->
            val mat = FloatArray(16)
            val bbuf = ByteBuffer.wrap(bin, base + i * 64, 64).order(ByteOrder.LITTLE_ENDIAN)
            for (j in 0 until 16) mat[j] = bbuf.float
            mat
        }
    }

    // ─── Bind pose derivation ─────────────────────────────────────────────────

    /**
     * Derive [BindPose] bone directions from a glTF node hierarchy.
     *
     * Strategy: for each [BoneRetargeter] JOINT_* bone segment (base→tip), look up the
     * asset's joint node index for both ends of that segment, read their translations
     * from the glTF nodes array, and compute the unit direction vector.
     *
     * If the node indices can't be mapped (asset uses a different naming convention),
     * falls back to the symmetric bind pose for those joints.
     */
    private fun deriveBindPose(
        root: JSONObject,
        jointNodeIndices: List<Int>,
        ibmList: List<FloatArray>
    ): BindPose {
        val nodes = root.optJSONArray("nodes")

        // Map each joint node index to its translation (bind-pose position)
        // from its inverse bind matrix (last column = -R^T * t).
        val jointPositions: Map<Int, Vec3> = jointNodeIndices.mapIndexed { slotIdx, nodeIdx ->
            val ibm = ibmList.getOrNull(slotIdx) ?: identityMatrix()
            // Extract translation from IBM: t = -R * lastColumn(IBM)
            // For column-major mat4, last column is indices 12,13,14
            val pos = Vec3(ibm[12], ibm[13], ibm[14])
            nodeIdx to pos
        }.toMap()

        // Try to match glTF joint node indices to BoneRetargeter bone segments.
        // Heuristic: use the joint index order — most hand rigs follow MediaPipe
        // or VRChat order (wrist first, then thumb CMC→MCP→IP, index MCP→PIP→DIP, …).
        // For assets that don't match, the symmetric fallback fills in the gaps.
        val fallback = BoneRetargeter.symmetricBindPose()
        val dirs = mutableMapOf<Int, Vec3>()

        // The first `min(JOINT_COUNT, jointNodeIndices.size)` joint slots are mapped
        // in BoneRetargeter order. Each bone direction = normalize(tip_pos - base_pos).
        // BONE_SEGMENTS' baseIdx/tipIdx are MediaPipe landmark indices (LM.*), not
        // slot indices into jointNodeIndices — translate through LANDMARK_TO_JOINT_SLOT
        // before indexing, or every finger past the thumb reads the wrong node.
        for ((jointIdx, baseLmIdx, tipLmIdx) in BoneRetargeter.BONE_SEGMENTS) {
            val baseSlot = BoneRetargeter.LANDMARK_TO_JOINT_SLOT[baseLmIdx]
            val tipSlot  = BoneRetargeter.LANDMARK_TO_JOINT_SLOT[tipLmIdx]
            val basePos = baseSlot?.let { jointPositions[jointNodeIndices.getOrElse(it) { -1 }] }
            val tipPos  = tipSlot?.let  { jointPositions[jointNodeIndices.getOrElse(it) { -1 }] }

            dirs[jointIdx] = if (basePos != null && tipPos != null) {
                val d = (tipPos - basePos)
                if (d.length() > 1e-6f) d.normalized()
                else fallback.boneDirections[jointIdx] ?: Vec3(0f, 1f, 0f)
            } else {
                fallback.boneDirections[jointIdx] ?: Vec3(0f, 1f, 0f)
            }
        }

        val wristPos = jointPositions[jointNodeIndices.firstOrNull() ?: -1]
            ?: fallback.wristPosition

        return BindPose(dirs, wristPos)
    }

    // ─── ASSET-4: Body joint map ──────────────────────────────────────────────

    /**
     * Walk the glTF nodes array and map any node whose name is recognised by
     * [VrmJointMapper] to its glTF node index.
     *
     * Returns an empty map for hand-only rigs (< 21 joints) — the caller checks
     * [LoadedAsset.slot] before using this map.
     */
    private fun buildBodyJointMap(
        root: JSONObject,
        jointNodeIndices: List<Int>
    ): Map<BodyJoint, Int> {
        if (jointNodeIndices.size < 21) return emptyMap()
        val nodes = root.optJSONArray("nodes") ?: return emptyMap()
        val result = mutableMapOf<BodyJoint, Int>()
        for (nodeIdx in jointNodeIndices) {
            if (nodeIdx >= nodes.length()) continue
            val name = nodes.getJSONObject(nodeIdx).optString("name", "")
            if (name.isEmpty()) continue
            val joint = VrmJointMapper.map(name) ?: continue
            // First match wins — prevents duplicate assignments if rig has alias nodes
            result.putIfAbsent(joint, nodeIdx)
        }
        return result
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun identityMatrix(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    // ─── Default puppet ───────────────────────────────────────────────────────

    /**
     * Built-in fallback asset used when no user GLB is loaded.
     *
     * The puppet has no mesh geometry (empty positions) — it drives retargeting only,
     * so the live skeleton lines from [HandRenderer] serve as the visual stand-in until
     * a real asset is loaded. [SkinnedMeshRenderer] should show nothing (or the skeleton)
     * when [LoadedAsset.meshPositions] is empty.
     */
    val DEFAULT_PUPPET = LoadedAsset(
        meshPositions       = FloatArray(0),
        jointNodeIndices    = emptyList(),
        inverseBindMatrices = emptyList(),
        bindPose            = BoneRetargeter.symmetricBindPose(),
        hasSkin             = false,
        slot                = AssetSlot.HAND_PUPPET,
        bodyJointMap        = emptyMap()
    )
}

// ─── LoadedAsset ─────────────────────────────────────────────────────────────

/**
 * The fully parsed result of [AssetLoader.load].
 *
 * Passed to [BoneRetargeter] (for the bind pose) and to [SkinnedMeshRenderer]
 * (for mesh geometry + inverse bind matrices).
 */
data class LoadedAsset(
    /**
     * Flat float array of mesh vertex positions (x, y, z × vertexCount).
     * Empty for the default puppet.
     */
    val meshPositions: FloatArray,

    /**
     * Ordered list of glTF node indices that make up the skin's joint hierarchy.
     * Index 0 = wrist root; order matches BoneRetargeter JOINT_* constants when
     * the asset uses standard hand rig layout.
     */
    val jointNodeIndices: List<Int>,

    /**
     * Inverse bind matrices — one column-major 4×4 float matrix per joint.
     * Used by the GPU skinning shader to transform vertices from model space
     * to joint space before applying the live rotation.
     */
    val inverseBindMatrices: List<FloatArray>,

    /**
     * Bind pose derived from the skin's joint hierarchy, ready for [BoneRetargeter].
     */
    val bindPose: BindPose,

    /** False when asset has no skin — retargeting still works via symmetric bind pose. */
    val hasSkin: Boolean,

    /**
     * ASSET-2 — Which retargeting slot this asset occupies.
     * Auto-detected by [AssetLoader.detectSlot] at load time.
     */
    val slot: AssetSlot = AssetSlot.HAND_PUPPET,

    /**
     * ASSET-4 — Maps [BodyJoint] enum values to glTF node indices in this asset's skeleton.
     *
     * Built by [AssetLoader] by matching glTF node names against [VrmJointMapper].
     * Null or empty for hand-only assets. Used by [SkinnedMeshRenderer] to drive body
     * bone matrices from [BodyRetargetResult] once BODY-1 is implemented.
     */
    val bodyJointMap: Map<BodyJoint, Int> = emptyMap(),

    /**
     * FACE-1 — VRM blend shape map parsed from `extensions.VRM.blendShapeMaster`.
     *
     * Null when the asset has no VRM extension or no recognised blend shape groups.
     * Used by [VrmBlendShapeParser.resolve] to convert [FaceExpressions] values into
     * morph target weight applications each frame.
     */
    val blendShapeMap: BlendShapeMap? = null,

    /**
     * GAP-2 — Morph target delta position buffers parsed from glTF `mesh.primitives[0].targets`.
     *
     * Each entry is a flat FloatArray of (dx, dy, dz) deltas — one Vec3 per vertex, same
     * vertex order as [meshPositions]. Index N in this list corresponds to morph target index N
     * in the VRM blend shape map.
     *
     * Empty when the asset has no morph targets (non-VRM assets, default puppet).
     * Used by [SkinnedMeshRenderer] to allocate morph delta VBOs and upload weights per frame.
     */
    val morphDeltas: List<FloatArray> = emptyList()
) {
    /** Number of skin joints. */
    val jointCount: Int get() = jointNodeIndices.size

    /** Vertex count derived from flat positions array. */
    val vertexCount: Int get() = meshPositions.size / 3

    override fun equals(other: Any?) = other is LoadedAsset &&
        meshPositions.contentEquals(other.meshPositions) &&
        jointNodeIndices == other.jointNodeIndices &&
        hasSkin == other.hasSkin

    override fun hashCode(): Int {
        var result = meshPositions.contentHashCode()
        result = 31 * result + jointNodeIndices.hashCode()
        result = 31 * result + hasSkin.hashCode()
        return result
    }
}
