package com.arhand.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.arhand.tracking.PL
import com.arhand.tracking.PoseLandmarks
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the MediaPipe 33-point body pose skeleton on the GL thread.
 *
 * Connections are colour-coded by body side (left=cyan, right=coral, centre=light-gray)
 * to match the standard MediaPipe pose visualisation convention. Connections and joints
 * whose endpoint visibility falls below [VIS_THRESHOLD] are skipped, so occluded limbs
 * fade out naturally rather than floating as ghost geometry.
 *
 * Uses [ShaderPrograms.LINE_VERT]/[LINE_FRAG] for connections and
 * [ShaderPrograms.POINTS_VERT]/[POINTS_FRAG] for joint dots.
 */
class BodySkeletonRenderer {

    private var lineProgram   = 0
    private var pointsProgram = 0

    private var lineModelLoc  = 0
    private var lineViewLoc   = 0
    private var lineProjLoc   = 0
    private var lineColorLoc  = 0
    private var linePosLoc    = 0

    private var ptModelLoc    = 0
    private var ptViewLoc     = 0
    private var ptProjLoc     = 0
    private var ptColorLoc    = 0
    private var ptSizeLoc     = 0
    private var ptPosLoc      = 0

    @Volatile private var pending: PoseLandmarks? = null

    // Single persistent VBO reused across all draw calls in a frame — avoids the
    // glGenBuffers/glDeleteBuffers churn that the naive per-call approach produces.
    private val scratchVbo = IntArray(1)

    // Pre-allocated direct FloatBuffer — glBufferData consumes it synchronously so the same buffer
    // is safe to reuse for consecutive sequential draw calls within one frame.
    // Capacity: max(PL.CONNECTIONS.size * 6, PL.COUNT * 3) = max(168, 99) → 256 floats is safe.
    private val scratchFloatBuf: FloatBuffer = ByteBuffer
        .allocateDirect(256 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun update(lms: PoseLandmarks?) { pending = lms }

    fun init() {
        lineProgram   = compileProgram(ShaderPrograms.LINE_VERT,   ShaderPrograms.LINE_FRAG)
        pointsProgram = compileProgram(ShaderPrograms.POINTS_VERT, ShaderPrograms.POINTS_FRAG)

        lineModelLoc = GLES30.glGetUniformLocation(lineProgram, "uModel")
        lineViewLoc  = GLES30.glGetUniformLocation(lineProgram, "uView")
        lineProjLoc  = GLES30.glGetUniformLocation(lineProgram, "uProjection")
        lineColorLoc = GLES30.glGetUniformLocation(lineProgram, "uColor")
        linePosLoc   = GLES30.glGetAttribLocation( lineProgram, "aPosition")

        ptModelLoc = GLES30.glGetUniformLocation(pointsProgram, "uModel")
        ptViewLoc  = GLES30.glGetUniformLocation(pointsProgram, "uView")
        ptProjLoc  = GLES30.glGetUniformLocation(pointsProgram, "uProjection")
        ptColorLoc = GLES30.glGetUniformLocation(pointsProgram, "uColor")
        ptSizeLoc  = GLES30.glGetUniformLocation(pointsProgram, "uPointSize")
        ptPosLoc   = GLES30.glGetAttribLocation( pointsProgram, "aPosition")

        GLES30.glGenBuffers(1, scratchVbo, 0)
    }

    fun draw(
        view:         FloatArray,
        proj:         FloatArray,
        mirrorX:      Boolean,
        camAspect:    Float,
        screenAspect: Float
    ) {
        val lms = pending ?: return
        if (lms.size < PL.COUNT) return

        val scaleX = maxOf(camAspect, screenAspect)
        val scaleY = maxOf(1f, screenAspect / camAspect)

        val xs  = FloatArray(PL.COUNT)
        val ys  = FloatArray(PL.COUNT)
        val zs  = FloatArray(PL.COUNT)
        val vis = FloatArray(PL.COUNT)
        for (i in 0 until PL.COUNT) {
            val lm = lms[i]
            val sx = if (mirrorX) 1f - lm.x else lm.x
            xs[i]  = (sx    - 0.5f) * 2f * scaleX
            ys[i]  = -(lm.y - 0.5f) * 2f * scaleY
            zs[i]  = lm.z * -0.6f
            vis[i] = lm.visibility
        }

        val model = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        // Connection lines — three colour groups
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(lineModelLoc, 1, false, model, 0)
        GLES30.glUniformMatrix4fv(lineViewLoc,  1, false, view,  0)
        GLES30.glUniformMatrix4fv(lineProjLoc,  1, false, proj,  0)

        drawLineGroup(LEFT_CONNS,   0f,   0.85f, 1f,    0.85f, xs, ys, zs, vis)  // cyan
        drawLineGroup(CENTER_CONNS, 0.9f, 0.9f,  0.9f,  0.80f, xs, ys, zs, vis)  // light gray
        drawLineGroup(RIGHT_CONNS,  1f,   0.4f,  0.3f,  0.85f, xs, ys, zs, vis)  // coral

        // Joint dots — same colour groups
        GLES30.glUseProgram(pointsProgram)
        GLES30.glUniformMatrix4fv(ptModelLoc, 1, false, model, 0)
        GLES30.glUniformMatrix4fv(ptViewLoc,  1, false, view,  0)
        GLES30.glUniformMatrix4fv(ptProjLoc,  1, false, proj,  0)
        GLES30.glUniform1f(ptSizeLoc, 10f)

        drawDotGroup(LEFT_JOINTS,   0f,   0.85f, 1f,    0.9f, xs, ys, zs, vis)
        drawDotGroup(CENTER_JOINTS, 1f,   1f,    1f,    0.9f, xs, ys, zs, vis)
        drawDotGroup(RIGHT_JOINTS,  1f,   0.4f,  0.3f,  0.9f, xs, ys, zs, vis)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun drawLineGroup(
        conns: List<Pair<Int, Int>>,
        r: Float, g: Float, b: Float, a: Float,
        xs: FloatArray, ys: FloatArray, zs: FloatArray, vis: FloatArray
    ) {
        GLES30.glUniform4f(lineColorLoc, r, g, b, a)
        val verts = FloatArray(conns.size * 6)
        var i = 0
        for ((p, q) in conns) {
            if (p < PL.COUNT && q < PL.COUNT && minOf(vis[p], vis[q]) >= VIS_THRESHOLD) {
                verts[i++] = xs[p]; verts[i++] = ys[p]; verts[i++] = zs[p]
                verts[i++] = xs[q]; verts[i++] = ys[q]; verts[i++] = zs[q]
            }
        }
        if (i > 0) drawRaw(linePosLoc, verts, i / 3, GLES30.GL_LINES)
    }

    private fun drawDotGroup(
        joints: IntArray,
        r: Float, g: Float, b: Float, a: Float,
        xs: FloatArray, ys: FloatArray, zs: FloatArray, vis: FloatArray
    ) {
        GLES30.glUniform4f(ptColorLoc, r, g, b, a)
        val verts = FloatArray(joints.size * 3)
        var i = 0
        for (j in joints) {
            if (j < PL.COUNT && vis[j] >= VIS_THRESHOLD) {
                verts[i++] = xs[j]; verts[i++] = ys[j]; verts[i++] = zs[j]
            }
        }
        if (i > 0) drawRaw(ptPosLoc, verts, i / 3, GLES30.GL_POINTS)
    }

    private fun drawRaw(posLoc: Int, verts: FloatArray, count: Int, mode: Int) {
        if (count == 0 || posLoc < 0) return
        scratchFloatBuf.clear()
        scratchFloatBuf.put(verts, 0, count * 3)
        scratchFloatBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, scratchVbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 3 * 4, scratchFloatBuf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(mode, 0, count)
    }

    private fun compileProgram(vertSrc: String, fragSrc: String): Int {
        val vert = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also {
            GLES30.glShaderSource(it, vertSrc); GLES30.glCompileShader(it)
        }
        val frag = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also {
            GLES30.glShaderSource(it, fragSrc); GLES30.glCompileShader(it)
        }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vert)
            GLES30.glAttachShader(it, frag)
            GLES30.glLinkProgram(it)
        }
    }

    companion object {
        // Landmark visibility below this threshold → connection/joint is skipped.
        private const val VIS_THRESHOLD = 0.3f

        // Body-side landmark sets (MediaPipe convention: left/right refer to the person's body side)
        private val LEFT_LMKS = setOf(
            PL.LEFT_EYE_INNER, PL.LEFT_EYE, PL.LEFT_EYE_OUTER, PL.LEFT_EAR, PL.MOUTH_LEFT,
            PL.LEFT_SHOULDER, PL.LEFT_ELBOW, PL.LEFT_WRIST,
            PL.LEFT_PINKY, PL.LEFT_INDEX, PL.LEFT_THUMB,
            PL.LEFT_HIP, PL.LEFT_KNEE, PL.LEFT_ANKLE, PL.LEFT_HEEL, PL.LEFT_FOOT_INDEX
        )
        private val RIGHT_LMKS = setOf(
            PL.RIGHT_EYE_INNER, PL.RIGHT_EYE, PL.RIGHT_EYE_OUTER, PL.RIGHT_EAR, PL.MOUTH_RIGHT,
            PL.RIGHT_SHOULDER, PL.RIGHT_ELBOW, PL.RIGHT_WRIST,
            PL.RIGHT_PINKY, PL.RIGHT_INDEX, PL.RIGHT_THUMB,
            PL.RIGHT_HIP, PL.RIGHT_KNEE, PL.RIGHT_ANKLE, PL.RIGHT_HEEL, PL.RIGHT_FOOT_INDEX
        )

        // Connections partitioned by body side — computed once at class load time
        val LEFT_CONNS   = PL.CONNECTIONS.filter { (a, b) -> a in LEFT_LMKS  && b in LEFT_LMKS  }
        val CENTER_CONNS = PL.CONNECTIONS.filter { (a, b) ->
            !(a in LEFT_LMKS && b in LEFT_LMKS) && !(a in RIGHT_LMKS && b in RIGHT_LMKS)
        }
        val RIGHT_CONNS  = PL.CONNECTIONS.filter { (a, b) -> a in RIGHT_LMKS && b in RIGHT_LMKS }

        // Key joint sets for dot rendering (major joints only — avoids clutter on minor hand/foot tips)
        val LEFT_JOINTS  = intArrayOf(
            PL.LEFT_SHOULDER, PL.LEFT_ELBOW, PL.LEFT_WRIST,
            PL.LEFT_HIP, PL.LEFT_KNEE, PL.LEFT_ANKLE
        )
        val RIGHT_JOINTS = intArrayOf(
            PL.RIGHT_SHOULDER, PL.RIGHT_ELBOW, PL.RIGHT_WRIST,
            PL.RIGHT_HIP, PL.RIGHT_KNEE, PL.RIGHT_ANKLE
        )
        val CENTER_JOINTS = intArrayOf(PL.NOSE)
    }
}
