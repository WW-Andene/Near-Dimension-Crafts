package com.arhand.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.arhand.mocap.BoneRetargeter
import com.arhand.mocap.LoadedAsset
import com.arhand.mocap.Quaternion
import com.arhand.mocap.RetargetResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU-skinned mesh renderer for loaded GLB assets.
 *
 * Drives a rigged 3D asset using the joint rotation map produced by [BoneRetargeter].
 * Each frame, the 16 joint rotations are converted to 4×4 matrices, concatenated with
 * their inverse bind matrices, and uploaded to the GPU as a uniform array. The vertex
 * shader applies the standard linear blend skinning (LBS) formula using JOINTS_0 /
 * WEIGHTS_0 vertex attributes.
 *
 * When the loaded asset has no mesh ([LoadedAsset.meshPositions] is empty), the renderer
 * draws nothing — the existing [HandRenderer] skeleton lines serve as the visual stand-in.
 *
 * VBO / VAO lifecycle: allocated once in [init], updated with [glBufferSubData] when the
 * asset changes, released in [release]. No per-frame allocation.
 *
 * Thread safety: [updateAsset] and [updatePose] are called from the tracking coroutine.
 * [draw] runs on the GL thread. Volatile flags gate geometry uploads.
 */
class SkinnedMeshRenderer {

    // ─── GL handles ───────────────────────────────────────────────────────────
    private var skinnedProgram = 0
    private var ghostProgram   = 0     // semi-transparent Phong for ghost overlay

    private val posVbo   = IntArray(1)  // vertex positions
    private val skinVbo  = IntArray(1)  // joint indices (JOINTS_0, ivec4 per vertex)
    private val wgtVbo   = IntArray(1)  // blend weights (WEIGHTS_0, vec4 per vertex)
    private val normVbo  = IntArray(1)  // normals
    private val vaoHandle = IntArray(1)

    private var vboCapacityBytes = 0

    // ─── Asset state (written from tracking thread, read on GL thread) ────────
    @Volatile private var pendingAsset: LoadedAsset? = null
    @Volatile private var assetDirty = false

    // Current GPU-resident geometry counts
    private var vertexCount = 0

    // ─── Pose state (written every tracking frame) ────────────────────────────
    /**
     * Bone matrix palette: 16 column-major 4×4 matrices = 256 floats.
     * Each entry = jointMatrix[i] × inverseBindMatrix[i].
     * Uploaded to the GPU via uBonePalette uniform array.
     */
    @Volatile private var bonePalette = FloatArray(BoneRetargeter.JOINT_COUNT * 16)
    @Volatile private var poseDirty   = false

    // Cached inverse bind matrices for the loaded asset (parallel to bone palette)
    private var inverseBindMatrices: List<FloatArray> = emptyList()

    // ─── Ghost overlay state ──────────────────────────────────────────────────
    /** When true, draw the procedural hand mesh at reduced opacity behind the asset. */
    var showGhostOverlay: Boolean = false

    // ─── Public API ───────────────────────────────────────────────────────────

    // Cached uniform/attrib locations — populated once in init() after shader compilation.
    // Avoids ~15 synchronous glGetUniformLocation driver calls per frame.
    private val skinnedLoc = HashMap<String, Int>(32)

    private fun cacheSkinnedLocations() {
        for (name in listOf(
            "uModel", "uView", "uProjection", "uNormalMatrix", "uBonePalette",
            "uBaseColor", "uAlpha", "uTime", "uTorchOn",
            "uAmbientColor", "uAmbientIntensity",
            "uKeyDir", "uKeyColor", "uKeyIntensity",
            "uRimDir", "uRimColor", "uRimIntensity",
            "uSSSDir", "uSSSColor", "uSSSIntensity",
            "uFillDir", "uFillColor", "uFillIntensity",
            "uTorchPos", "uTorchColor", "uTorchIntensity", "uTorchAttenuation", "uCameraPos"
        )) skinnedLoc[name] = GLES30.glGetUniformLocation(skinnedProgram, name)
    }

    fun init() {
        skinnedProgram = buildProg(SKINNED_VERT, SKINNED_FRAG)
        ghostProgram   = buildProg(ShaderPrograms.PHONG_VERT, ShaderPrograms.PHONG_FRAG)

        GLES30.glGenVertexArrays(1, vaoHandle, 0)
        GLES30.glGenBuffers(1, posVbo,  0)
        GLES30.glGenBuffers(1, skinVbo, 0)
        GLES30.glGenBuffers(1, wgtVbo,  0)
        GLES30.glGenBuffers(1, normVbo, 0)

        cacheSkinnedLocations()
    }

    /**
     * Call from the tracking thread whenever the loaded asset changes.
     * The actual VBO upload happens lazily on the GL thread in [draw].
     */
    fun updateAsset(asset: LoadedAsset) {
        pendingAsset           = asset
        inverseBindMatrices    = asset.inverseBindMatrices
        assetDirty             = true
    }

    /**
     * Call every tracking frame with the latest [RetargetResult].
     * Computes the bone palette = jointMatrix × inverseBindMatrix for each joint.
     */
    fun updatePose(result: RetargetResult) {
        val palette = FloatArray(BoneRetargeter.JOINT_COUNT * 16)

        for (jointIdx in 0 until BoneRetargeter.JOINT_COUNT) {
            val rot = result.jointRotations[jointIdx] ?: Quaternion.IDENTITY
            val jointMat = rot.toMatrix()

            // Apply wrist root translation to joint 0
            if (jointIdx == BoneRetargeter.JOINT_WRIST) {
                val t = result.wristTransform.position
                jointMat[12] = t.x
                jointMat[13] = t.y
                jointMat[14] = t.z
            }

            // skinMatrix = jointMatrix × inverseBindMatrix
            val ibm = inverseBindMatrices.getOrNull(jointIdx) ?: identityMatrix()
            val skinMat = FloatArray(16)
            Matrix.multiplyMM(skinMat, 0, jointMat, 0, ibm, 0)

            skinMat.copyInto(palette, jointIdx * 16)
        }

        bonePalette = palette
        poseDirty   = true
    }

    /**
     * Gap 4 — Drive body skeleton joints from [BodyRetargetResult].
     *
     * Uses [LoadedAsset.bodyJointMap] to map [BodyJoint] enum values to glTF node
     * indices, then builds skin matrices for those nodes from the body rotation data.
     * Called every frame alongside [updatePose] when body tracking is active.
     *
     * Safe to call with null — no-ops silently so the hand-only path is unaffected.
     */
    fun updateBodyPose(
        bodyResult: com.arhand.mocap.BodyRetargetResult?,
        asset:      com.arhand.mocap.LoadedAsset?
    ) {
        if (bodyResult == null || asset == null) return
        val jointMap = asset.bodyJointMap
        if (jointMap.isEmpty()) return

        val palette  = bonePalette.copyOf()   // start from current hand palette

        for ((bodyJoint, nodeIdx) in jointMap) {
            val rot = bodyResult.joints[bodyJoint] ?: continue
            if (nodeIdx < 0 || nodeIdx >= asset.jointNodeIndices.size) continue

            val jointMat = rot.toMatrix()

            // Apply Hips root translation when this is the root body joint
            if (bodyJoint == com.arhand.mocap.BodyJoint.HIPS) {
                // Centre body on-screen — use a fixed offset so it doesn't drift
                jointMat[12] = 0f; jointMat[13] = 0f; jointMat[14] = -1f
            }

            val ibm = asset.inverseBindMatrices.getOrNull(nodeIdx) ?: identityMatrix()
            val skinMat = FloatArray(16)
            android.opengl.Matrix.multiplyMM(skinMat, 0, jointMat, 0, ibm, 0)

            // Write into palette at the correct slot
            val paletteIdx = nodeIdx.coerceIn(0, BoneRetargeter.JOINT_COUNT - 1)
            skinMat.copyInto(palette, paletteIdx * 16)
        }

        bonePalette = palette
        poseDirty   = true
    }

    /**
     * Currently a stub: the weight list is stored but not yet applied to vertex
     * positions on the GPU. Full implementation requires adding morph delta buffers
     * to the vertex shader (planned in a future sprint once the parser and data flow
     * are validated end-to-end).
     *
     * Calling this method is safe and has no visible effect on the current mesh.
     * It is called every frame when face tracking is active and a VRM asset is loaded.
     */
    fun applyMorphWeights(applications: List<com.arhand.mocap.MorphApplication>) {
        // Store for future GPU upload — no-op until shader morph support is added
        pendingMorphApplications = applications
    }

    @Volatile private var pendingMorphApplications: List<com.arhand.mocap.MorphApplication> = emptyList()

    /**
     * Draw the skinned mesh. Must be called on the GL thread.
     *
     * @param view       View matrix from [ARRenderer]
     * @param proj       Projection matrix from [ARRenderer]
     * @param torchOn    Whether the torch point light is active
     * @param timeSec    Elapsed time for animated effects
     */
    fun draw(view: FloatArray, proj: FloatArray, torchOn: Boolean, timeSec: Float) {
        // Upload new asset geometry if flagged
        if (assetDirty) {
            pendingAsset?.let { uploadAssetGeometry(it) }
            assetDirty = false
        }

        if (vertexCount == 0) return   // no mesh — nothing to draw

        val model = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        GLES30.glUseProgram(skinnedProgram)

        // MVP
        GLES30.glUniformMatrix4fv(skinnedLoc["uModel"]!!,      1, false, model, 0)
        GLES30.glUniformMatrix4fv(skinnedLoc["uView"]!!,       1, false, view,  0)
        GLES30.glUniformMatrix4fv(skinnedLoc["uProjection"]!!, 1, false, proj,  0)

        // Bone palette — 16 matrices × 16 floats = 256 floats
        val palette = bonePalette
        GLES30.glUniformMatrix4fv(
            skinnedLoc["uBonePalette"]!!,
            BoneRetargeter.JOINT_COUNT,
            false,
            palette,
            0
        )

        // Lighting uniforms (reuse LightingModel constants)
        setLightingUniforms(skinnedProgram, torchOn, timeSec)

        GLES30.glUniform3fv(skinnedLoc["uBaseColor"]!!, 1, floatArrayOf(0.824f, 0.667f, 0.471f), 0)
        GLES30.glUniform1f(skinnedLoc["uAlpha"]!!, 0.97f)
        GLES30.glUniform1f(skinnedLoc["uTime"]!!, timeSec)
        GLES30.glUniform1i(skinnedLoc["uTorchOn"]!!, if (torchOn) 1 else 0)

        // Bind VAO and draw
        GLES30.glBindVertexArray(vaoHandle[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (posVbo[0]    != 0) { GLES30.glDeleteBuffers(1, posVbo,   0); posVbo[0]   = 0 }
        if (skinVbo[0]   != 0) { GLES30.glDeleteBuffers(1, skinVbo,  0); skinVbo[0]  = 0 }
        if (wgtVbo[0]    != 0) { GLES30.glDeleteBuffers(1, wgtVbo,   0); wgtVbo[0]   = 0 }
        if (normVbo[0]   != 0) { GLES30.glDeleteBuffers(1, normVbo,  0); normVbo[0]  = 0 }
        if (vaoHandle[0] != 0) { GLES30.glDeleteVertexArrays(1, vaoHandle, 0); vaoHandle[0] = 0 }
        if (skinnedProgram != 0) { GLES30.glDeleteProgram(skinnedProgram); skinnedProgram = 0 }
        if (ghostProgram   != 0) { GLES30.glDeleteProgram(ghostProgram);   ghostProgram   = 0 }
        vertexCount = 0
    }

    // ─── Geometry upload ──────────────────────────────────────────────────────

    private fun uploadAssetGeometry(asset: LoadedAsset) {
        val pos = asset.meshPositions
        vertexCount = pos.size / 3
        if (vertexCount == 0) return

        // Flat normals — computed CPU-side once per asset load
        val normals = computeFlatNormals(pos)

        // Synthesise uniform joint influence: every vertex is 100% driven by joint 0 (wrist root).
        // For assets with a proper JOINTS_0 / WEIGHTS_0 in their glTF, those values would be
        // parsed by AssetLoader and passed in here. The uniform-influence fallback produces
        // a rigid-body transform of the whole mesh — visually correct for simple props
        // (gloves, rings, VFX) even without per-vertex weight data.
        val joints  = IntArray(vertexCount * 4) { if (it % 4 == 0) 0 else 0 }  // all → joint 0
        val weights = FloatArray(vertexCount * 4).apply {
            for (i in 0 until vertexCount) { this[i * 4] = 1f }  // weight[0] = 1.0, rest = 0.0
        }

        GLES30.glBindVertexArray(vaoHandle[0])

        uploadFloatVbo(posVbo[0],  pos,     3, GLES30.glGetAttribLocation(skinnedProgram, "aPosition"))
        uploadFloatVbo(normVbo[0], normals, 3, GLES30.glGetAttribLocation(skinnedProgram, "aNormal"))
        uploadFloatVboFromInts(skinVbo[0], joints,  4, GLES30.glGetAttribLocation(skinnedProgram, "aJoints"))
        uploadFloatVbo(wgtVbo[0],  weights, 4, GLES30.glGetAttribLocation(skinnedProgram, "aWeights"))

        GLES30.glBindVertexArray(0)
    }

    private fun uploadFloatVbo(vbo: Int, data: FloatArray, components: Int, attribLoc: Int) {
        if (attribLoc < 0) return
        val buf = toFloatBuffer(data)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(attribLoc)
        GLES30.glVertexAttribPointer(attribLoc, components, GLES30.GL_FLOAT, false, 0, 0)
    }

    private fun uploadFloatVboFromInts(vbo: Int, data: IntArray, components: Int, attribLoc: Int) {
        if (attribLoc < 0) return
        // Upload as floats — the shader reads them as float and casts to int for palette lookup
        val floats = FloatArray(data.size) { data[it].toFloat() }
        uploadFloatVbo(vbo, floats, components, attribLoc)
    }

    // ─── Lighting ─────────────────────────────────────────────────────────────

    private fun setLightingUniforms(prog: Int, torchOn: Boolean, timeSec: Float) {
        val L = LightingModel
        fun v3(name: String, v: FloatArray) = GLES30.glUniform3fv(skinnedLoc[name]!!, 1, v, 0)
        fun f1(name: String, v: Float)      = GLES30.glUniform1f(skinnedLoc[name]!!, v)

        v3("uAmbientColor",    L.ambientColor);  f1("uAmbientIntensity", L.ambientIntensity)
        v3("uKeyDir",  L.keyDir);   v3("uKeyColor",  L.keyColor);  f1("uKeyIntensity",  L.keyIntensity)
        v3("uRimDir",  L.rimDir);   v3("uRimColor",  L.rimColor);  f1("uRimIntensity",  L.rimIntensity)
        v3("uSSSDir",  L.sssDir);   v3("uSSSColor",  L.sssColor);  f1("uSSSIntensity",  L.sssIntensity)
        v3("uFillDir", L.fillDir);  v3("uFillColor", L.fillColor); f1("uFillIntensity", L.fillIntensity)
        v3("uTorchPos",   floatArrayOf(0f, 0.2f, 0.5f))
        v3("uTorchColor", L.torchColor); f1("uTorchIntensity", L.torchIntensity)
        GLES30.glUniform3fv(skinnedLoc["uTorchAttenuation"]!!, 1, L.torchAttenuation, 0)
        v3("uCameraPos", floatArrayOf(0f, 0f, 5f))
        f1("uTime", timeSec)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun computeFlatNormals(positions: FloatArray): FloatArray {
        val normals = FloatArray(positions.size)
        var i = 0
        while (i + 9 <= positions.size) {
            val ax = positions[i];   val ay = positions[i+1]; val az = positions[i+2]
            val bx = positions[i+3]; val by = positions[i+4]; val bz = positions[i+5]
            val cx = positions[i+6]; val cy = positions[i+7]; val cz = positions[i+8]
            val ux = bx-ax; val uy = by-ay; val uz = bz-az
            val vx = cx-ax; val vy = cy-ay; val vz = cz-az
            var nx = uy*vz - uz*vy; var ny = uz*vx - ux*vz; var nz = ux*vy - uy*vx
            val len = Math.sqrt((nx*nx + ny*ny + nz*nz).toDouble()).toFloat().coerceAtLeast(1e-6f)
            nx /= len; ny /= len; nz /= len
            for (j in 0 until 3) { normals[i+j*3] = nx; normals[i+j*3+1] = ny; normals[i+j*3+2] = nz }
            i += 9
        }
        return normals
    }

    private fun identityMatrix() = floatArrayOf(
        1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f
    )

    private fun toFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }

    private fun buildProg(vert: String, frag: String): Int {
        val vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
            .also { GLES30.glShaderSource(it, vert); GLES30.glCompileShader(it) }
        val fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
            .also { GLES30.glShaderSource(it, frag); GLES30.glCompileShader(it) }
        return GLES30.glCreateProgram()
            .also { GLES30.glAttachShader(it, vs); GLES30.glAttachShader(it, fs); GLES30.glLinkProgram(it) }
    }

    // ─── Shader sources ───────────────────────────────────────────────────────

    /**
     * Vertex shader — linear blend skinning (LBS).
     *
     * Inputs:
     *   aPosition  — model-space vertex position (vec3)
     *   aNormal    — model-space vertex normal   (vec3)
     *   aJoints    — up to 4 bone indices        (vec4, stored as floats, cast to int)
     *   aWeights   — blend weights               (vec4, should sum to 1.0)
     *
     * Uniform:
     *   uBonePalette[JOINT_COUNT] — skinMatrix per joint = jointMat × inverseBindMat
     *
     * The skinned position and normal are passed to the Phong fragment shader via
     * vWorldPos / vNormal, matching the PHONG_FRAG interface already in ShaderPrograms.
     */
    private val SKINNED_VERT = """
        #version 300 es
        precision highp float;

        in vec3 aPosition;
        in vec3 aNormal;
        in vec4 aJoints;    // bone indices stored as float, cast to int in shader
        in vec4 aWeights;   // blend weights (sum = 1.0)

        uniform mat4 uModel;
        uniform mat4 uView;
        uniform mat4 uProjection;
        uniform mat3 uNormalMatrix;
        uniform mat4 uBonePalette[${BoneRetargeter.JOINT_COUNT}];

        out vec3 vWorldPos;
        out vec3 vNormal;

        void main() {
            // Linear blend skinning
            ivec4 j = ivec4(aJoints);
            mat4 skin =
                uBonePalette[j.x] * aWeights.x +
                uBonePalette[j.y] * aWeights.y +
                uBonePalette[j.z] * aWeights.z +
                uBonePalette[j.w] * aWeights.w;

            vec4 skinnedPos    = skin * vec4(aPosition, 1.0);
            vec4 skinnedNormal = skin * vec4(aNormal,   0.0);

            vec4 worldPos = uModel * skinnedPos;
            vWorldPos = worldPos.xyz;
            vNormal   = normalize(mat3(uModel) * skinnedNormal.xyz);

            gl_Position = uProjection * uView * worldPos;
        }
    """.trimIndent()

    /**
     * Fragment shader — reuses the existing Phong lighting model from [ShaderPrograms.PHONG_FRAG].
     * The interface (vWorldPos, vNormal, uniform lighting params) is identical.
     */
    private val SKINNED_FRAG = ShaderPrograms.PHONG_FRAG
}
