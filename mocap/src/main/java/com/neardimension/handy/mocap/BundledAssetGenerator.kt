package com.arhand.mocap

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates minimal, self-contained GLB files that are bundled into assets/models/.
 *
 * These are procedurally generated at first launch (not shipped as binary blobs)
 * so the app has zero external IP dependencies for its default puppets.
 *
 * ## Hand puppet (hand_default.glb)
 * - 16-joint hand skeleton matching [BoneRetargeter.JOINT_*] constants
 * - UV sphere mesh (radius 0.1m, 16×8 subdivisions) attached to wrist root
 * - Uses [AssetSlot.HAND_PUPPET] slot (≤ 20 joints)
 *
 * ## Body puppet (body_default.glb)
 * - 24-joint humanoid skeleton using [BodyJoint] Unity naming
 * - Stack of 13 capsule-approximating cylinders forming a stick figure
 * - Uses [AssetSlot.BODY_CHARACTER] slot (≥ 21 joints)
 *
 * Both files are CC0 — entirely procedural, no external rights required.
 */
object BundledAssetGenerator {

    /**
     * Ensure both bundled GLB files exist in [outputDir].
     * Generates them if absent; skips if already present.
     *
     * @param context   Android context for asset path resolution.
     * @param outputDir Target directory (typically getExternalFilesDir("models") or cacheDir).
     * @return Map of filename → absolute path for each generated file.
     */
    fun ensureAssets(context: Context, outputDir: File): Map<String, String> {
        outputDir.mkdirs()
        val result = mutableMapOf<String, String>()

        val handFile = File(outputDir, "hand_default.glb")
        if (!handFile.exists()) generateHandPuppet(handFile)
        result["hand_default.glb"] = handFile.absolutePath

        val bodyFile = File(outputDir, "body_default.glb")
        if (!bodyFile.exists()) generateBodyPuppet(bodyFile)
        result["body_default.glb"] = bodyFile.absolutePath

        return result
    }

    // ── Hand puppet ───────────────────────────────────────────────────────────

    private fun generateHandPuppet(outFile: File) {
        // 16 nodes: wrist root + 15 finger joints
        // Laid out as a flat hand in the XZ plane, Y pointing up
        val jointNames = listOf(
            "Wrist",
            "ThumbCMC", "ThumbMCP", "ThumbIP",
            "IndexMCP",  "IndexPIP",  "IndexDIP",
            "MiddleMCP", "MiddlePIP", "MiddleDIP",
            "RingMCP",   "RingPIP",   "RingDIP",
            "PinkyMCP",  "PinkyPIP",  "PinkyDIP"
        )

        // T-pose joint translations (x, y, z) in metres — palm-up, fingers extended
        val translations = arrayOf(
            floatArrayOf(0f,    0f,     0f),       // Wrist
            floatArrayOf(-0.03f, 0f, 0.04f),       // ThumbCMC
            floatArrayOf(-0.04f, 0f, 0.065f),      // ThumbMCP
            floatArrayOf(-0.04f, 0f, 0.085f),      // ThumbIP
            floatArrayOf(-0.01f, 0f, 0.09f),       // IndexMCP
            floatArrayOf(-0.01f, 0f, 0.115f),      // IndexPIP
            floatArrayOf(-0.01f, 0f, 0.135f),      // IndexDIP
            floatArrayOf(0.005f, 0f, 0.09f),       // MiddleMCP
            floatArrayOf(0.005f, 0f, 0.12f),       // MiddlePIP
            floatArrayOf(0.005f, 0f, 0.14f),       // MiddleDIP
            floatArrayOf(0.02f,  0f, 0.088f),      // RingMCP
            floatArrayOf(0.02f,  0f, 0.115f),      // RingPIP
            floatArrayOf(0.02f,  0f, 0.133f),      // RingDIP
            floatArrayOf(0.034f, 0f, 0.082f),      // PinkyMCP
            floatArrayOf(0.034f, 0f, 0.105f),      // PinkyPIP
            floatArrayOf(0.034f, 0f, 0.12f)        // PinkyDIP
        )

        // Parent indices (-1 = root)
        val parents = intArrayOf(
            -1,   // Wrist
            0,    // ThumbCMC ← Wrist
            1,    // ThumbMCP ← ThumbCMC
            2,    // ThumbIP  ← ThumbMCP
            0,    // IndexMCP ← Wrist
            4, 5,
            0,    // MiddleMCP ← Wrist
            7, 8,
            0,    // RingMCP ← Wrist
            10, 11,
            0,    // PinkyMCP ← Wrist
            13, 14
        )

        // Simple sphere mesh centred at origin (represents the hand volume)
        val (sphereVerts, sphereIdx) = uvSphere(radius = 0.05f, stacks = 8, slices = 12)

        writeGlb(outFile, jointNames, translations, parents, sphereVerts, sphereIdx)
    }

    // ── Body puppet ───────────────────────────────────────────────────────────

    private fun generateBodyPuppet(outFile: File) {
        // 24 nodes: full humanoid using BodyJoint Unity names
        val jointNames = listOf(
            "Hips",
            "Spine", "Chest", "Neck", "Head",
            "LeftUpperArm", "LeftLowerArm", "LeftHand",
            "RightUpperArm", "RightLowerArm", "RightHand",
            "LeftUpperLeg", "LeftLowerLeg", "LeftFoot",
            "RightUpperLeg", "RightLowerLeg", "RightFoot",
            // Extras for completeness
            "LeftShoulder", "RightShoulder",
            "LeftToeBase", "RightToeBase",
            "HeadTop_End",
            "LeftHandIndex1", "RightHandIndex1"
        )

        // T-pose translations — standard humanoid at 1.7m height, centred at hips
        val translations = arrayOf(
            floatArrayOf(0f,   1.0f,  0f),     // Hips
            floatArrayOf(0f,   0.10f, 0f),     // Spine ← Hips
            floatArrayOf(0f,   0.20f, 0f),     // Chest ← Spine
            floatArrayOf(0f,   0.25f, 0f),     // Neck ← Chest
            floatArrayOf(0f,   0.12f, 0f),     // Head ← Neck
            floatArrayOf(-0.18f, 0.05f, 0f),   // LeftUpperArm ← Chest (via LeftShoulder)
            floatArrayOf(-0.25f,  0f,  0f),    // LeftLowerArm ← LeftUpperArm
            floatArrayOf(-0.25f,  0f,  0f),    // LeftHand ← LeftLowerArm
            floatArrayOf( 0.18f, 0.05f, 0f),   // RightUpperArm
            floatArrayOf( 0.25f,  0f,  0f),    // RightLowerArm
            floatArrayOf( 0.25f,  0f,  0f),    // RightHand
            floatArrayOf(-0.09f, -0.05f, 0f),  // LeftUpperLeg ← Hips
            floatArrayOf(0f,   -0.42f,  0f),   // LeftLowerLeg ← LeftUpperLeg
            floatArrayOf(0f,   -0.40f,  0f),   // LeftFoot ← LeftLowerLeg
            floatArrayOf( 0.09f, -0.05f, 0f),  // RightUpperLeg
            floatArrayOf(0f,   -0.42f,  0f),   // RightLowerLeg
            floatArrayOf(0f,   -0.40f,  0f),   // RightFoot
            floatArrayOf(-0.08f, 0.20f, 0f),   // LeftShoulder ← Chest
            floatArrayOf( 0.08f, 0.20f, 0f),   // RightShoulder ← Chest
            floatArrayOf(0f,   -0.10f,  0.08f),// LeftToeBase ← LeftFoot
            floatArrayOf(0f,   -0.10f,  0.08f),// RightToeBase ← RightFoot
            floatArrayOf(0f,    0.12f,  0f),   // HeadTop_End ← Head
            floatArrayOf(-0.05f, 0f, 0.03f),   // LeftHandIndex1 ← LeftHand
            floatArrayOf( 0.05f, 0f, 0.03f)    // RightHandIndex1 ← RightHand
        )

        val parents = intArrayOf(
            -1,   // Hips
            0, 1, 2, 3,       // spine chain
            17, 5, 6,         // left arm: LeftShoulder←Chest, LeftUpperArm←LeftShoulder
            18, 8, 9,         // right arm
            0, 11, 12,        // left leg
            0, 14, 15,        // right leg
            2, 2,             // shoulders ← Chest
            13, 16,           // toes ← feet
            4,                // HeadTop_End ← Head
            7, 10             // finger indices ← hands
        )

        // Simple capsule-stack mesh for the body (vertical cylinder)
        val (bodyVerts, bodyIdx) = cylinder(radius = 0.12f, height = 1.6f, stacks = 6, slices = 10)

        writeGlb(outFile, jointNames, translations, parents, bodyVerts, bodyIdx)
    }

    // ── GLB writer ────────────────────────────────────────────────────────────

    private fun writeGlb(
        outFile:    File,
        jointNames: List<String>,
        translations: Array<FloatArray>,
        parents:    IntArray,
        vertices:   FloatArray,
        indices:    IntArray
    ) {
        val n = jointNames.size

        // Build glTF JSON
        val nodesJson = buildString {
            append("[")
            for (i in 0 until n) {
                if (i > 0) append(",")
                val t = translations[i]
                val childList = parents.indices.filter { parents[it] == i }
                append("""{"name":"${jointNames[i]}","translation":[${t[0]},${t[1]},${t[2]}]""")
                if (childList.isNotEmpty()) append(""","children":[${childList.joinToString(",")}]""")
                append("}")
            }
            // Mesh node
            append(""",{"name":"body_mesh","mesh":0}""")
            append("]")
        }

        val jointsJson = (0 until n).joinToString(",")
        val ibmBytes   = n * 16 * 4   // n × 4×4 identity matrices
        val vtxBytes   = vertices.size * 4
        val idxBytes   = indices.size * 4
        val vtxCount   = vertices.size / 3
        val idxCount   = indices.size
        val totalBin   = ibmBytes + vtxBytes + idxBytes
        val idxOffset  = ibmBytes + vtxBytes

        val json = """{"asset":{"version":"2.0","generator":"Handy BundledAssetGenerator"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":$nodesJson,"meshes":[{"name":"body_mesh","primitives":[{"attributes":{"POSITION":1},"indices":2,"mode":4}]}],"skins":[{"name":"HandSkin","inverseBindMatrices":0,"joints":[$jointsJson],"skeleton":0}],"accessors":[{"bufferView":0,"componentType":5126,"count":$n,"type":"MAT4"},{"bufferView":1,"componentType":5126,"count":$vtxCount,"type":"VEC3"},{"bufferView":2,"componentType":5125,"count":$idxCount,"type":"SCALAR"}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$ibmBytes},{"buffer":0,"byteOffset":$ibmBytes,"byteLength":$vtxBytes},{"buffer":0,"byteOffset":$idxOffset,"byteLength":$idxBytes}],"buffers":[{"byteLength":$totalBin}]}"""

        val jsonBytes  = json.toByteArray(Charsets.US_ASCII)
        val jsonPad    = (4 - jsonBytes.size % 4) % 4
        val jsonPadded = jsonBytes + ByteArray(jsonPad) { 0x20 }

        val binPad    = (4 - totalBin % 4) % 4
        val totalLen  = 12 + 8 + jsonPadded.size + 8 + totalBin + binPad

        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46546C67)   // magic "glTF"
        buf.putInt(2)             // version
        buf.putInt(totalLen)
        buf.putInt(jsonPadded.size)
        buf.putInt(0x4E4F534A)   // "JSON"
        buf.put(jsonPadded)
        buf.putInt(totalBin + binPad)
        buf.putInt(0x004E4942)   // "BIN\0"

        // Inverse bind matrices — all identity (4×4 floats × n)
        val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        repeat(n) { identity.forEach { v -> buf.putFloat(v) } }

        // Vertex positions
        vertices.forEach { buf.putFloat(it) }

        // Indices as uint32
        indices.forEach { buf.putInt(it) }

        // Padding
        repeat(binPad) { buf.put(0) }

        outFile.writeBytes(buf.array())
    }

    // ── Mesh generators ───────────────────────────────────────────────────────

    /** UV sphere: returns (flat vertex positions, flat uint32 indices). */
    private fun uvSphere(radius: Float, stacks: Int, slices: Int): Pair<FloatArray, IntArray> {
        val verts = mutableListOf<Float>()
        val idx   = mutableListOf<Int>()

        for (i in 0..stacks) {
            val phi = PI.toFloat() * i / stacks
            for (j in 0..slices) {
                val theta = 2f * PI.toFloat() * j / slices
                verts += radius * sin(phi) * cos(theta)
                verts += radius * cos(phi)
                verts += radius * sin(phi) * sin(theta)
            }
        }

        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = i * (slices + 1) + j
                val b = a + slices + 1
                idx += a; idx += b; idx += a + 1
                idx += b; idx += b + 1; idx += a + 1
            }
        }

        return Pair(verts.toFloatArray(), idx.toIntArray())
    }

    /** Cylinder: returns (flat vertex positions, flat uint32 indices). */
    private fun cylinder(
        radius: Float, height: Float, stacks: Int, slices: Int
    ): Pair<FloatArray, IntArray> {
        val verts = mutableListOf<Float>()
        val idx   = mutableListOf<Int>()
        val halfH = height * 0.5f

        for (i in 0..stacks) {
            val y = -halfH + height * i / stacks
            for (j in 0..slices) {
                val theta = 2f * PI.toFloat() * j / slices
                verts += radius * cos(theta)
                verts += y
                verts += radius * sin(theta)
            }
        }

        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = i * (slices + 1) + j
                val b = a + slices + 1
                idx += a; idx += b; idx += a + 1
                idx += b; idx += b + 1; idx += a + 1
            }
        }

        return Pair(verts.toFloatArray(), idx.toIntArray())
    }
}
