package com.arhand.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the marching cubes depth mesh.
 * Supports solid (Phong semi-transparent) and wireframe modes.
 *
 * VBO lifecycle: allocated once in init(), updated with glBufferSubData on geometry change,
 * deleted only in release(). No per-frame alloc/dealloc.
 */
class DepthMeshRenderer {
    private var phongProgram = 0
    private var lineProgram  = 0

    private val vboHandle     = IntArray(1)
    private val normVboHandle = IntArray(1)
    private var vboCapacityFloats = 0

    private var meshPositions: FloatArray = FloatArray(0)
    private var meshNormals:   FloatArray = FloatArray(0)
    private var geometryDirty  = false

    // ── Cached uniform/attrib locations ──────────────────────────────────────
    // Line (wireframe) program
    private var lModel     = -1; private var lView      = -1; private var lProj      = -1
    private var lColor     = -1; private var lAPos      = -1
    // Phong program
    private var pModel     = -1; private var pView      = -1; private var pProj      = -1
    private var pNormMtx   = -1; private var pBaseColor = -1; private var pAlpha     = -1
    private var pTorchOn   = -1; private var pInferred  = -1; private var pCamPos    = -1
    private var pAPos      = -1; private var pANorm     = -1

    // Pre-allocated per-draw scratch
    private val model    = FloatArray(16)
    private val identNorm = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
    private val meshColor = floatArrayOf(0.5f, 0.8f, 1.0f)
    private val camPos    = floatArrayOf(0f, 0f, 5f)
    private val lineColor = floatArrayOf(0f, 1f, 0.898f, 0.4f)

    fun init() {
        phongProgram = buildProg(ShaderPrograms.PHONG_VERT, ShaderPrograms.PHONG_FRAG)
        lineProgram  = buildProg(ShaderPrograms.LINE_VERT,  ShaderPrograms.LINE_FRAG)

        GLES30.glGenBuffers(1, vboHandle, 0)
        GLES30.glGenBuffers(1, normVboHandle, 0)

        // Cache all uniform/attrib locations once
        lModel    = GLES30.glGetUniformLocation(lineProgram,  "uModel")
        lView     = GLES30.glGetUniformLocation(lineProgram,  "uView")
        lProj     = GLES30.glGetUniformLocation(lineProgram,  "uProjection")
        lColor    = GLES30.glGetUniformLocation(lineProgram,  "uColor")
        lAPos     = GLES30.glGetAttribLocation(lineProgram,   "aPosition")

        pModel    = GLES30.glGetUniformLocation(phongProgram, "uModel")
        pView     = GLES30.glGetUniformLocation(phongProgram, "uView")
        pProj     = GLES30.glGetUniformLocation(phongProgram, "uProjection")
        pNormMtx  = GLES30.glGetUniformLocation(phongProgram, "uNormalMatrix")
        pBaseColor= GLES30.glGetUniformLocation(phongProgram, "uBaseColor")
        pAlpha    = GLES30.glGetUniformLocation(phongProgram, "uAlpha")
        pTorchOn  = GLES30.glGetUniformLocation(phongProgram, "uTorchOn")
        pInferred = GLES30.glGetUniformLocation(phongProgram, "uInferred")
        pCamPos   = GLES30.glGetUniformLocation(phongProgram, "uCameraPos")
        pAPos     = GLES30.glGetAttribLocation(phongProgram,  "aPosition")
        pANorm    = GLES30.glGetAttribLocation(phongProgram,  "aNormal")
    }

    fun update(positions: FloatArray) {
        meshPositions = positions
        meshNormals   = computeFlatNormals(positions)
        geometryDirty = true
    }

    fun draw(view: FloatArray, proj: FloatArray, mode: RenderMode) {
        if (meshPositions.isEmpty()) return
        // Only relevant to the two modes that actually mean "show me the scanned
        // mesh" — renderer.depthMeshPositions is set once after a scan completes
        // and never cleared (the mesh is kept around intentionally, for viewing/
        // export), so without this gate the reconstructed mesh keeps rendering
        // on top of the live camera feed in every other mode too, including
        // SKELETON — a solid, wrongly-scaled blob was showing up as a giant
        // circle over ordinary hand tracking.
        if (mode != RenderMode.MESH && mode != RenderMode.WIREFRAME) return

        if (geometryDirty) {
            uploadGeometry(meshPositions)
            geometryDirty = false
        }

        Matrix.setIdentityM(model, 0)

        if (mode == RenderMode.WIREFRAME) {
            GLES30.glUseProgram(lineProgram)
            GLES30.glUniformMatrix4fv(lModel, 1, false, model, 0)
            GLES30.glUniformMatrix4fv(lView,  1, false, view,  0)
            GLES30.glUniformMatrix4fv(lProj,  1, false, proj,  0)
            GLES30.glUniform4fv(lColor, 1, lineColor, 0)
            drawBound(lAPos, meshPositions.size, GLES30.GL_TRIANGLES)
        } else if (mode == RenderMode.MESH || mode == RenderMode.LIVE_MESH) {
            GLES30.glUseProgram(phongProgram)
            GLES30.glUniformMatrix4fv(pModel,   1, false, model,    0)
            GLES30.glUniformMatrix4fv(pView,    1, false, view,     0)
            GLES30.glUniformMatrix4fv(pProj,    1, false, proj,     0)
            GLES30.glUniformMatrix3fv(pNormMtx, 1, false, identNorm,0)
            GLES30.glUniform3fv(pBaseColor, 1, meshColor, 0)
            GLES30.glUniform1f(pAlpha,   0.4f)
            GLES30.glUniform1i(pTorchOn, 0)
            GLES30.glUniform1i(pInferred,0)
            GLES30.glUniform3fv(pCamPos, 1, camPos, 0)
            GLES30.glDepthMask(false)
            drawBoundPhong(meshPositions.size)
            GLES30.glDepthMask(true)
        }
        // SKELETON, ASSET_3D, ASSET_2D: depth mesh is invisible — hand skeleton mode shows clean overlay
    }

    private fun uploadGeometry(verts: FloatArray) {
        val buf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(verts); position(0) }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboHandle[0])
        if (verts.size > vboCapacityFloats) {
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
            vboCapacityFloats = verts.size
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, verts.size * 4, buf)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        val norms = meshNormals
        if (norms.isNotEmpty()) {
            val normBuf = ByteBuffer.allocateDirect(norms.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(norms); position(0) }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, normVboHandle[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, norms.size * 4, normBuf, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
    }

    private fun drawBound(posLoc: Int, vertFloats: Int, glMode: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboHandle[0])
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(glMode, 0, vertFloats / 3)
        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawBoundPhong(vertFloats: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboHandle[0])
        GLES30.glEnableVertexAttribArray(pAPos)
        GLES30.glVertexAttribPointer(pAPos, 3, GLES30.GL_FLOAT, false, 0, 0)

        if (pANorm >= 0 && meshNormals.isNotEmpty()) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, normVboHandle[0])
            GLES30.glEnableVertexAttribArray(pANorm)
            GLES30.glVertexAttribPointer(pANorm, 3, GLES30.GL_FLOAT, false, 0, 0)
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertFloats / 3)

        GLES30.glDisableVertexAttribArray(pAPos)
        if (pANorm >= 0) GLES30.glDisableVertexAttribArray(pANorm)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        if (vboHandle[0] != 0) {
            GLES30.glDeleteBuffers(1, vboHandle, 0)
            vboHandle[0] = 0
            vboCapacityFloats = 0
        }
        if (normVboHandle[0] != 0) {
            GLES30.glDeleteBuffers(1, normVboHandle, 0)
            normVboHandle[0] = 0
        }
        if (phongProgram != 0) { GLES30.glDeleteProgram(phongProgram); phongProgram = 0 }
        if (lineProgram  != 0) { GLES30.glDeleteProgram(lineProgram);  lineProgram  = 0 }
    }

    private fun computeFlatNormals(positions: FloatArray): FloatArray {
        val normals = FloatArray(positions.size)
        var i = 0
        while (i + 9 <= positions.size) {
            val ax = positions[i];   val ay = positions[i+1]; val az = positions[i+2]
            val bx = positions[i+3]; val by = positions[i+4]; val bz = positions[i+5]
            val cx = positions[i+6]; val cy = positions[i+7]; val cz = positions[i+8]
            val ux = bx-ax; val uy = by-ay; val uz = bz-az
            val vx = cx-ax; val vy = cy-ay; val vz = cz-az
            val nx = uy*vz - uz*vy
            val ny = uz*vx - ux*vz
            val nz = ux*vy - uy*vx
            val len = Math.sqrt((nx*nx + ny*ny + nz*nz).toDouble()).toFloat().coerceAtLeast(1e-6f)
            for (j in 0 until 3) { normals[i+j*3] = nx/len; normals[i+j*3+1] = ny/len; normals[i+j*3+2] = nz/len }
            i += 9
        }
        return normals
    }

    private fun buildProg(v: String, f: String): Int {
        val vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also { GLES30.glShaderSource(it, v); GLES30.glCompileShader(it) }
        val fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also { GLES30.glShaderSource(it, f); GLES30.glCompileShader(it) }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vs); GLES30.glAttachShader(it, fs); GLES30.glLinkProgram(it)
            GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        }
    }
}
