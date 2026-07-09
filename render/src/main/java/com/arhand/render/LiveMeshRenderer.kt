package com.arhand.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * G1 — GL renderer for the live-deforming scanned hand mesh.
 *
 * Receives a pre-deformed position array from [LiveMeshDeformer] every tracking frame
 * (written from the tracking coroutine via [updateMesh]) and draws it on the GL thread
 * via [draw].
 *
 * Normals are re-computed CPU-side from the deformed positions each frame. At 4 000–8 000
 * vertices this costs < 0.5ms and avoids the complexity of transforming a separate normal
 * buffer through the skinning matrix palette.
 *
 * Uses the existing [ShaderPrograms.PHONG_VERT] / [ShaderPrograms.PHONG_FRAG] shaders with
 * a distinctive teal skin colour (`#1FC8A8`) so the live-deforming mesh is visually distinct
 * from the static snapshot mesh and the procedural `HandRenderer`.
 */
class LiveMeshRenderer {

    // ─── GL handles ───────────────────────────────────────────────────────────
    private var program     = 0
    private val vao         = IntArray(1)
    private val posVbo      = IntArray(1)
    private val normVbo     = IntArray(1)

    // ─── Cached uniform / attrib locations (populated once in init()) ─────────
    private var uModel      = -1
    private var uView       = -1
    private var uProjection = -1
    private var uNormalMtx  = -1
    private var uBaseColor  = -1
    private var uAlpha      = -1
    private var uTime       = -1
    private var uInferred   = -1
    private var uTorchOn    = -1
    // Lighting
    private var uAmbCol     = -1; private var uAmbInt    = -1
    private var uKeyDir     = -1; private var uKeyCol    = -1; private var uKeyInt    = -1
    private var uRimDir     = -1; private var uRimCol    = -1; private var uRimInt    = -1
    private var uSSSDir     = -1; private var uSSSCol    = -1; private var uSSSInt    = -1
    private var uFillDir    = -1; private var uFillCol   = -1; private var uFillInt   = -1
    private var uTorchPos   = -1; private var uTorchCol  = -1; private var uTorchInt  = -1
    private var uTorchAtten = -1; private var uCamPos    = -1; private var uTimeLt    = -1
    // Attribs
    private var aPosition   = -1
    private var aNormal     = -1

    // ─── Mesh state (written from tracking thread, read on GL thread) ─────────
    @Volatile private var pendingPositions: FloatArray? = null
    @Volatile private var positionsDirty               = false
    private var vertexCount  = 0

    // ─── Colour ───────────────────────────────────────────────────────────────
    private val liveColor = floatArrayOf(0.122f, 0.784f, 0.659f)   // #1FC8A8

    // ─── Pre-allocated per-draw scratch ──────────────────────────────────────
    private val modelMatrix  = FloatArray(16)
    private val normalMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    // Persistent upload buffer — grown as needed, never shrunk
    private var uploadBuf: FloatBuffer? = null
    private var uploadBufCap = 0

    // ─── Public API ───────────────────────────────────────────────────────────

    fun init() {
        program = buildProg(ShaderPrograms.PHONG_VERT, ShaderPrograms.PHONG_FRAG)
        GLES30.glGenVertexArrays(1, vao,     0)
        GLES30.glGenBuffers(1,    posVbo,    0)
        GLES30.glGenBuffers(1,    normVbo,   0)

        // Cache all uniform + attrib locations once — eliminates per-frame string-hash lookups
        uModel      = GLES30.glGetUniformLocation(program, "uModel")
        uView       = GLES30.glGetUniformLocation(program, "uView")
        uProjection = GLES30.glGetUniformLocation(program, "uProjection")
        uNormalMtx  = GLES30.glGetUniformLocation(program, "uNormalMatrix")
        uBaseColor  = GLES30.glGetUniformLocation(program, "uBaseColor")
        uAlpha      = GLES30.glGetUniformLocation(program, "uAlpha")
        uTime       = GLES30.glGetUniformLocation(program, "uTime")
        uInferred   = GLES30.glGetUniformLocation(program, "uInferred")
        uTorchOn    = GLES30.glGetUniformLocation(program, "uTorchOn")
        uAmbCol     = GLES30.glGetUniformLocation(program, "uAmbientColor")
        uAmbInt     = GLES30.glGetUniformLocation(program, "uAmbientIntensity")
        uKeyDir     = GLES30.glGetUniformLocation(program, "uKeyDir")
        uKeyCol     = GLES30.glGetUniformLocation(program, "uKeyColor")
        uKeyInt     = GLES30.glGetUniformLocation(program, "uKeyIntensity")
        uRimDir     = GLES30.glGetUniformLocation(program, "uRimDir")
        uRimCol     = GLES30.glGetUniformLocation(program, "uRimColor")
        uRimInt     = GLES30.glGetUniformLocation(program, "uRimIntensity")
        uSSSDir     = GLES30.glGetUniformLocation(program, "uSSSDir")
        uSSSCol     = GLES30.glGetUniformLocation(program, "uSSSColor")
        uSSSInt     = GLES30.glGetUniformLocation(program, "uSSSIntensity")
        uFillDir    = GLES30.glGetUniformLocation(program, "uFillDir")
        uFillCol    = GLES30.glGetUniformLocation(program, "uFillColor")
        uFillInt    = GLES30.glGetUniformLocation(program, "uFillIntensity")
        uTorchPos   = GLES30.glGetUniformLocation(program, "uTorchPos")
        uTorchCol   = GLES30.glGetUniformLocation(program, "uTorchColor")
        uTorchInt   = GLES30.glGetUniformLocation(program, "uTorchIntensity")
        uTorchAtten = GLES30.glGetUniformLocation(program, "uTorchAttenuation")
        uCamPos     = GLES30.glGetUniformLocation(program, "uCameraPos")
        uTimeLt     = GLES30.glGetUniformLocation(program, "uTime")
        aPosition   = GLES30.glGetAttribLocation(program, "aPosition")
        aNormal     = GLES30.glGetAttribLocation(program, "aNormal")
    }

    fun updateMesh(positions: FloatArray) {
        pendingPositions = positions
        positionsDirty   = true
    }

    fun clearMesh() {
        pendingPositions = null
        positionsDirty   = false
        vertexCount      = 0
    }

    fun draw(view: FloatArray, proj: FloatArray, torchOn: Boolean, timeSec: Float) {
        if (positionsDirty) {
            pendingPositions?.let { uploadPositions(it) }
            positionsDirty = false
        }

        if (vertexCount == 0) return

        Matrix.setIdentityM(modelMatrix, 0)

        GLES30.glUseProgram(program)

        GLES30.glUniformMatrix4fv(uModel,      1, false, modelMatrix,  0)
        GLES30.glUniformMatrix4fv(uView,       1, false, view,         0)
        GLES30.glUniformMatrix4fv(uProjection, 1, false, proj,         0)
        GLES30.glUniformMatrix3fv(uNormalMtx,  1, false, normalMatrix, 0)

        setLightingUniforms(torchOn, timeSec)

        GLES30.glUniform3fv(uBaseColor, 1, liveColor, 0)
        GLES30.glUniform1f(uAlpha,   0.92f)
        GLES30.glUniform1f(uTime,    timeSec)
        GLES30.glUniform1i(uInferred, 0)
        GLES30.glUniform1i(uTorchOn, if (torchOn) 1 else 0)

        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (posVbo[0]  != 0) { GLES30.glDeleteBuffers(1, posVbo,  0); posVbo[0]  = 0 }
        if (normVbo[0] != 0) { GLES30.glDeleteBuffers(1, normVbo, 0); normVbo[0] = 0 }
        if (vao[0]     != 0) { GLES30.glDeleteVertexArrays(1, vao, 0); vao[0]    = 0 }
        if (program    != 0) { GLES30.glDeleteProgram(program);        program    = 0 }
        vertexCount = 0
    }

    // ─── GL helpers ───────────────────────────────────────────────────────────

    private fun uploadPositions(positions: FloatArray) {
        val vc = positions.size / 3
        vertexCount = vc
        if (vc == 0) return

        val normals = computeFlatNormals(positions)

        GLES30.glBindVertexArray(vao[0])
        uploadVbo(posVbo[0],  positions, 3, aPosition)
        uploadVbo(normVbo[0], normals,   3, aNormal)
        GLES30.glBindVertexArray(0)
    }

    private fun uploadVbo(vbo: Int, data: FloatArray, components: Int, loc: Int) {
        if (loc < 0) return
        val buf = getUploadBuf(data.size)
        buf.clear()
        buf.put(data)
        buf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(loc, components, GLES30.GL_FLOAT, false, 0, 0)
    }

    private fun getUploadBuf(floats: Int): FloatBuffer {
        if (floats > uploadBufCap) {
            uploadBuf    = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            uploadBufCap = floats
        }
        return uploadBuf!!
    }

    private fun setLightingUniforms(torchOn: Boolean, timeSec: Float) {
        val L = LightingModel
        val torchPos = floatArrayOf(0f, 0.2f, 0.5f)
        val camPos   = floatArrayOf(0f, 0f, 5f)
        GLES30.glUniform3fv(uAmbCol,     1, L.ambientColor,    0); GLES30.glUniform1f(uAmbInt,  L.ambientIntensity)
        GLES30.glUniform3fv(uKeyDir,     1, L.keyDir,           0); GLES30.glUniform3fv(uKeyCol, 1, L.keyColor,  0); GLES30.glUniform1f(uKeyInt,  L.keyIntensity)
        GLES30.glUniform3fv(uRimDir,     1, L.rimDir,           0); GLES30.glUniform3fv(uRimCol, 1, L.rimColor,  0); GLES30.glUniform1f(uRimInt,  L.rimIntensity)
        GLES30.glUniform3fv(uSSSDir,     1, L.sssDir,           0); GLES30.glUniform3fv(uSSSCol, 1, L.sssColor,  0); GLES30.glUniform1f(uSSSInt,  L.sssIntensity)
        GLES30.glUniform3fv(uFillDir,    1, L.fillDir,          0); GLES30.glUniform3fv(uFillCol,1, L.fillColor, 0); GLES30.glUniform1f(uFillInt, L.fillIntensity)
        GLES30.glUniform3fv(uTorchPos,   1, torchPos,           0)
        GLES30.glUniform3fv(uTorchCol,   1, L.torchColor,       0); GLES30.glUniform1f(uTorchInt, L.torchIntensity)
        GLES30.glUniform3fv(uTorchAtten, 1, L.torchAttenuation, 0)
        GLES30.glUniform3fv(uCamPos,     1, camPos,             0)
        GLES30.glUniform1f(uTimeLt,  timeSec)
    }

    private fun computeFlatNormals(positions: FloatArray): FloatArray {
        val normals = FloatArray(positions.size)
        var i = 0
        while (i + 9 <= positions.size) {
            val ax = positions[i];   val ay = positions[i+1]; val az = positions[i+2]
            val bx = positions[i+3]; val by = positions[i+4]; val bz = positions[i+5]
            val cx = positions[i+6]; val cy = positions[i+7]; val cz = positions[i+8]
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = kotlin.math.sqrt((nx * nx + ny * ny + nz * nz).toDouble())
                .toFloat().coerceAtLeast(1e-6f)
            nx /= len; ny /= len; nz /= len
            for (j in 0 until 3) {
                normals[i + j * 3]     = nx
                normals[i + j * 3 + 1] = ny
                normals[i + j * 3 + 2] = nz
            }
            i += 9
        }
        return normals
    }

    private fun buildProg(vert: String, frag: String): Int {
        val vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
            .also { GLES30.glShaderSource(it, vert); GLES30.glCompileShader(it) }
        val fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
            .also { GLES30.glShaderSource(it, frag); GLES30.glCompileShader(it) }
        return GLES30.glCreateProgram()
            .also {
                GLES30.glAttachShader(it, vs)
                GLES30.glAttachShader(it, fs)
                GLES30.glLinkProgram(it)
                GLES30.glDeleteShader(vs)
                GLES30.glDeleteShader(fs)
            }
    }
}
