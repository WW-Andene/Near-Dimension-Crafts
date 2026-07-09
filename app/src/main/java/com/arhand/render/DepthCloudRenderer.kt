package com.arhand.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.arhand.util.PointCloudStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView.Renderer for DepthLab.
 *
 * Draws the point cloud from [PointCloudStore] each frame using perspective-correct
 * GL_POINTS with heat-map confidence coloring.
 *
 * Orbit controls are applied externally via [rotX], [rotY], [zoom] (set by touch handler
 * in the Activity). Auto-spin kicks in when [userOrbiting] is false, matching lidar.html.
 *
 * VBO is re-uploaded each frame only when [store.pointCount] has changed.
 */
class DepthCloudRenderer(private val store: PointCloudStore? = null) : GLSurfaceView.Renderer {

    @Volatile var rotX = 0.18f
    @Volatile var rotY = 0f
    @Volatile var zoom = 1f
    @Volatile var userOrbiting = false

    private var program = 0
    private val vboId = IntArray(1)
    private var vboAllocated = 0

    private var snapBuf = FloatArray(0)
    private var lastPointCount = -1

    private var viewW = 1; private var viewH = 1
    private var t0 = 0L

    // ── Cached uniform/attrib locations ─────────────────────────────────────
    // Component-mode (updateAndDraw): uses "aPos"/"aConf"/"uView"/"uProj"/"uPointSize"
    private var uViewLoc      = -1
    private var uProjLoc      = -1
    private var uPointSizeLoc = -1
    private var aPosLoc       = -1
    private var aConfLoc      = -1
    // Standalone renderer (onDrawFrame): uses "uMVP"/"uPointSize"/"aPosition"/"aConf"
    private var uMvpLoc       = -1
    private var uSzLoc        = -1
    private var aPositionLoc  = -1
    private var aConfLoc2     = -1

    // ── Pre-allocated matrix scratch for onDrawFrame ──────────────────────
    private val proj  = FloatArray(16)
    private val view  = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp   = FloatArray(16)
    private val mvp   = FloatArray(16)

    // ── Persistent upload buffer — grown as needed ──────────────────────
    private var uploadBuf: FloatBuffer? = null
    private var uploadBufCap = 0

    // ── Component-mode API (used by ARRenderer) ──────────────────────────

    fun init() {
        if (program != 0) return
        program = buildProgram(ShaderPrograms.POINTS_VERT, ShaderPrograms.POINTS_FRAG)
        GLES30.glGenBuffers(1, vboId, 0)

        // Cache locations for component-mode (updateAndDraw)
        uViewLoc      = GLES30.glGetUniformLocation(program, "uView")
        uProjLoc      = GLES30.glGetUniformLocation(program, "uProj")
        uPointSizeLoc = GLES30.glGetUniformLocation(program, "uPointSize")
        aPosLoc       = GLES30.glGetAttribLocation(program, "aPos")
        aConfLoc      = GLES30.glGetAttribLocation(program, "aConf")
        // Cache locations for standalone-mode (onDrawFrame)
        uMvpLoc       = GLES30.glGetUniformLocation(program, "uMVP")
        uSzLoc        = GLES30.glGetUniformLocation(program, "uPointSize")
        aPositionLoc  = GLES30.glGetAttribLocation(program, "aPosition")
        aConfLoc2     = GLES30.glGetAttribLocation(program, "aConf")
    }

    fun updateAndDraw(points: FloatArray, view: FloatArray, proj: FloatArray) {
        if (program == 0) init()
        val n = points.size / 4
        if (n == 0) return

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])
        val bytes = points.size * 4
        if (points.size > vboAllocated) {
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bytes, null, GLES30.GL_DYNAMIC_DRAW)
            vboAllocated = points.size
        }
        val buf = getUploadBuf(points.size)
        buf.clear(); buf.put(points, 0, n * 4); buf.position(0)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, buf)

        GLES30.glUseProgram(program)
        val stride = 4 * 4
        GLES30.glEnableVertexAttribArray(aPosLoc)
        GLES30.glVertexAttribPointer(aPosLoc,  3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(aConfLoc)
        GLES30.glVertexAttribPointer(aConfLoc, 1, GLES30.GL_FLOAT, false, stride, 12)

        GLES30.glUniformMatrix4fv(uViewLoc, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(uProjLoc, 1, false, proj, 0)
        GLES30.glUniform1f(uPointSizeLoc, 4f)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n)
        GLES30.glDisableVertexAttribArray(aPosLoc)
        GLES30.glDisableVertexAttribArray(aConfLoc)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.015f, 0.015f, 0.03f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        init()
        t0 = System.currentTimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val store = this.store ?: return
        val n = store.pointCount
        if (n < 2) return

        val needed = n * 4
        if (snapBuf.size < needed) snapBuf = FloatArray(needed)
        val written = store.snapshot(snapBuf)
        val pts = written / 4

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])
        if (pts != lastPointCount) {
            val buf = getUploadBuf(written)
            buf.clear(); buf.put(snapBuf, 0, written); buf.position(0)
            if (written > vboAllocated) {
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, written * 4, buf, GLES30.GL_DYNAMIC_DRAW)
                vboAllocated = written
            } else {
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, written * 4, buf)
            }
            lastPointCount = pts
        }

        GLES30.glUseProgram(program)

        val asp = viewW.toFloat() / viewH.toFloat()
        val elapsed = (System.currentTimeMillis() - t0) / 1000f
        val autoRy = if (userOrbiting) rotY else rotY + elapsed * 0.06f

        perspectiveM(proj, 60f, asp, 0.001f, 300f)

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, -store.centroidX, -store.centroidY, -store.centroidZ - 3f * zoom)
        Matrix.setRotateM(tmp, 0, Math.toDegrees(rotX.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.multiplyMM(view, 0, tmp, 0, model, 0)
        Matrix.setRotateM(tmp, 0, Math.toDegrees(autoRy.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(model, 0, tmp, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)

        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)
        GLES30.glUniform1f(uSzLoc, 4f)

        val stride = 4 * 4
        GLES30.glEnableVertexAttribArray(aPositionLoc)
        GLES30.glVertexAttribPointer(aPositionLoc, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(aConfLoc2)
        GLES30.glVertexAttribPointer(aConfLoc2, 1, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pts)
        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aConfLoc2)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun getUploadBuf(floats: Int): FloatBuffer {
        if (floats > uploadBufCap) {
            uploadBuf    = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            uploadBufCap = floats
        }
        return uploadBuf!!
    }

    private fun perspectiveM(m: FloatArray, fovDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = (1.0 / Math.tan(Math.toRadians(fovDeg.toDouble() / 2))).toFloat()
        val r = 1f / (near - far)
        m.fill(0f)
        m[0] = f / aspect; m[5] = f
        m[10] = (far + near) * r; m[11] = -1f
        m[14] = 2f * far * near * r
    }

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        fun compile(type: Int, src: String): Int =
            GLES30.glCreateShader(type).also { GLES30.glShaderSource(it, src); GLES30.glCompileShader(it) }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, compile(GLES30.GL_VERTEX_SHADER, vertSrc))
            GLES30.glAttachShader(it, compile(GLES30.GL_FRAGMENT_SHADER, fragSrc))
            GLES30.glLinkProgram(it)
        }
    }
}
