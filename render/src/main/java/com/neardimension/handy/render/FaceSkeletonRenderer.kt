package com.arhand.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.arhand.tracking.FL
import com.arhand.tracking.FaceLandmarks
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Renders the MediaPipe 478-point face mesh skeleton on the GL thread.
 *
 * Draws the standard MediaPipe face mesh connection sets (face oval, eyes, eyebrows,
 * lips) as thin dotted HUD lines with small pale-cyan cross iris markers, matching the
 * mocap-telemetry look of [HandRenderer]/[BodySkeletonRenderer]. Coordinates map from
 * image-space (0–1) to GL world-space using the same center-crop projection as those
 * renderers. Must be called from the GL thread only.
 */
class FaceSkeletonRenderer {

    private var lineProgram   = 0
    private var pointsProgram = 0

    private var lineModelLoc    = 0
    private var lineViewLoc     = 0
    private var lineProjLoc     = 0
    private var lineColorLoc    = 0
    private var linePosLoc      = 0
    private var lineDistLoc     = -1
    private var lineDashSizeLoc = -1

    private var ptModelLoc    = 0
    private var ptViewLoc     = 0
    private var ptProjLoc     = 0
    private var ptColorLoc    = 0
    private var ptSizeLoc     = 0
    private var ptPosLoc      = 0

    @Volatile private var pending: FaceLandmarks? = null

    private val scratchVbo = IntArray(1)

    // CONNECTIONS.size * 2 verts * 4 floats (dash lines carry an extra distance float) = ~776 max; 1024 is safe.
    private val scratchFloatBuf: FloatBuffer = ByteBuffer
        .allocateDirect(1024 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun update(lms: FaceLandmarks?) { pending = lms }

    fun init() {
        if (lineProgram != 0) return
        lineProgram   = compileProgram(ShaderPrograms.DASH_LINE_VERT, ShaderPrograms.DASH_LINE_FRAG)
        pointsProgram = compileProgram(ShaderPrograms.POINTS_VERT,    ShaderPrograms.CYBER_POINT_FRAG)

        lineModelLoc    = GLES30.glGetUniformLocation(lineProgram, "uModel")
        lineViewLoc     = GLES30.glGetUniformLocation(lineProgram, "uView")
        lineProjLoc     = GLES30.glGetUniformLocation(lineProgram, "uProjection")
        lineColorLoc    = GLES30.glGetUniformLocation(lineProgram, "uColor")
        linePosLoc      = GLES30.glGetAttribLocation( lineProgram, "aPosition")
        lineDistLoc     = GLES30.glGetAttribLocation( lineProgram, "aDist")
        lineDashSizeLoc = GLES30.glGetUniformLocation(lineProgram, "uDashSize")

        ptModelLoc = GLES30.glGetUniformLocation(pointsProgram, "uModel")
        ptViewLoc  = GLES30.glGetUniformLocation(pointsProgram, "uView")
        ptProjLoc  = GLES30.glGetUniformLocation(pointsProgram, "uProjection")
        ptColorLoc = GLES30.glGetUniformLocation(pointsProgram, "uColor")
        ptSizeLoc  = GLES30.glGetUniformLocation(pointsProgram, "uPointSize")
        ptPosLoc   = GLES30.glGetAttribLocation( pointsProgram, "aPosition")

        GLES30.glGenBuffers(1, scratchVbo, 0)
    }

    /** Deletes all GL objects allocated in [init]. Safe to call multiple times. */
    fun release() {
        if (scratchVbo[0] != 0) { GLES30.glDeleteBuffers(1, scratchVbo, 0); scratchVbo[0] = 0 }
        if (lineProgram != 0) { GLES30.glDeleteProgram(lineProgram); lineProgram = 0 }
        if (pointsProgram != 0) { GLES30.glDeleteProgram(pointsProgram); pointsProgram = 0 }
    }

    fun draw(
        view:         FloatArray,
        proj:         FloatArray,
        mirrorX:      Boolean,
        camAspect:    Float,
        screenAspect: Float
    ) {
        val lms = pending ?: return
        if (lms.size < FL.COUNT) return

        val scaleX = maxOf(camAspect, screenAspect)
        val scaleY = maxOf(1f, screenAspect / camAspect)

        val xs = FloatArray(FL.COUNT)
        val ys = FloatArray(FL.COUNT)
        val zs = FloatArray(FL.COUNT)
        for (i in 0 until FL.COUNT) {
            val lm = lms[i]
            val sx = if (mirrorX) 1f - lm.x else lm.x
            xs[i] = (sx    - 0.5f) * 2f * scaleX
            ys[i] = -(lm.y - 0.5f) * 2f * scaleY
            zs[i] = lm.z * -0.6f
        }

        val model = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        // Draw face mesh connection lines — thin dotted pale-cyan HUD wireframe
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(lineModelLoc, 1, false, model, 0)
        GLES30.glUniformMatrix4fv(lineViewLoc,  1, false, view,  0)
        GLES30.glUniformMatrix4fv(lineProjLoc,  1, false, proj,  0)
        GLES30.glUniform4f(lineColorLoc, 0.65f, 0.82f, 0.88f, 0.5f)
        if (lineDashSizeLoc >= 0) GLES30.glUniform1f(lineDashSizeLoc, DASH_CYCLE_WORLD_UNITS)

        val maxVerts = CONNECTIONS.size * 2
        // 4 floats/vertex: x, y, z, distance-along-segment
        val lineVerts = FloatArray(maxVerts * 4)
        var li = 0
        for ((a, b) in CONNECTIONS) {
            if (a < FL.COUNT && b < FL.COUNT) {
                val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]; val dz = zs[b] - zs[a]
                val segLen = sqrt(dx * dx + dy * dy + dz * dz)
                lineVerts[li++] = xs[a]; lineVerts[li++] = ys[a]; lineVerts[li++] = zs[a]; lineVerts[li++] = 0f
                lineVerts[li++] = xs[b]; lineVerts[li++] = ys[b]; lineVerts[li++] = zs[b]; lineVerts[li++] = segLen
            }
        }
        drawDashRaw(lineVerts, li / 4, GLES30.GL_LINES)

        // Draw iris center dots (pale cyan cross markers, small)
        val validDots = IRIS_DOTS.filter { it < FL.COUNT }
        if (validDots.isNotEmpty()) {
            GLES30.glUseProgram(pointsProgram)
            GLES30.glUniformMatrix4fv(ptModelLoc, 1, false, model, 0)
            GLES30.glUniformMatrix4fv(ptViewLoc,  1, false, view,  0)
            GLES30.glUniformMatrix4fv(ptProjLoc,  1, false, proj,  0)
            GLES30.glUniform4f(ptColorLoc, 0.86f, 0.93f, 0.98f, 0.9f)
            GLES30.glUniform1f(ptSizeLoc, 7f)

            val dotVerts = FloatArray(validDots.size * 3)
            validDots.forEachIndexed { i, idx ->
                dotVerts[i * 3]     = xs[idx]
                dotVerts[i * 3 + 1] = ys[idx]
                dotVerts[i * 3 + 2] = zs[idx]
            }
            drawRaw(ptPosLoc, dotVerts, validDots.size, GLES30.GL_POINTS)
        }
    }

    private fun drawDashRaw(verts: FloatArray, count: Int, mode: Int) {
        if (count == 0 || linePosLoc < 0) return
        scratchFloatBuf.clear()
        scratchFloatBuf.put(verts, 0, count * 4)
        scratchFloatBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, scratchVbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 4 * 4, scratchFloatBuf, GLES30.GL_DYNAMIC_DRAW)
        val stride = 4 * 4
        GLES30.glEnableVertexAttribArray(linePosLoc)
        GLES30.glVertexAttribPointer(linePosLoc, 3, GLES30.GL_FLOAT, false, stride, 0)
        if (lineDistLoc >= 0) {
            GLES30.glEnableVertexAttribArray(lineDistLoc)
            GLES30.glVertexAttribPointer(lineDistLoc, 1, GLES30.GL_FLOAT, false, stride, 3 * 4)
        }
        GLES30.glDrawArrays(mode, 0, count)
        if (lineDistLoc >= 0) GLES30.glDisableVertexAttribArray(lineDistLoc)
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
            GLES30.glDeleteShader(vert)
            GLES30.glDeleteShader(frag)
        }
    }

    companion object {
        // Dash-cycle length in world units — face mesh coords span roughly -1..1.
        private const val DASH_CYCLE_WORLD_UNITS = 0.02f

        // Standard MediaPipe FaceMesh connection sets
        val CONNECTIONS: List<Pair<Int, Int>> = buildList {
            // Face oval
            addAll(listOf(
                10 to 338, 338 to 297, 297 to 332, 332 to 284, 284 to 251, 251 to 389,
                389 to 356, 356 to 454, 454 to 323, 323 to 361, 361 to 288, 288 to 397,
                397 to 365, 365 to 379, 379 to 378, 378 to 400, 400 to 377, 377 to 152,
                152 to 148, 148 to 176, 176 to 149, 149 to 150, 150 to 136, 136 to 172,
                172 to 58, 58 to 132, 132 to 93, 93 to 234, 234 to 127, 127 to 162,
                162 to 21, 21 to 54, 54 to 103, 103 to 67, 67 to 109, 109 to 10
            ))
            // Left eye
            addAll(listOf(
                33 to 246, 246 to 161, 161 to 160, 160 to 159, 159 to 158, 158 to 157,
                157 to 173, 173 to 133, 133 to 155, 155 to 154, 154 to 153, 153 to 145,
                145 to 144, 144 to 163, 163 to 7, 7 to 33
            ))
            // Right eye
            addAll(listOf(
                263 to 466, 466 to 388, 388 to 387, 387 to 386, 386 to 385, 385 to 384,
                384 to 398, 398 to 362, 362 to 382, 382 to 381, 381 to 380, 380 to 374,
                374 to 373, 373 to 390, 390 to 249, 249 to 263
            ))
            // Left eyebrow
            addAll(listOf(
                46 to 53, 53 to 52, 52 to 65, 65 to 55, 55 to 107, 107 to 66,
                66 to 105, 105 to 63, 63 to 70
            ))
            // Right eyebrow
            addAll(listOf(
                276 to 283, 283 to 282, 282 to 295, 295 to 285, 285 to 336, 336 to 296,
                296 to 334, 334 to 293, 293 to 300
            ))
            // Lips outer
            addAll(listOf(
                61 to 185, 185 to 40, 40 to 39, 39 to 37, 37 to 0, 0 to 267, 267 to 269,
                269 to 270, 270 to 409, 409 to 291, 291 to 375, 375 to 321, 321 to 405,
                405 to 314, 314 to 17, 17 to 84, 84 to 181, 181 to 91, 91 to 146, 146 to 61
            ))
            // Lips inner
            addAll(listOf(
                78 to 191, 191 to 80, 80 to 81, 81 to 82, 82 to 13, 13 to 312, 312 to 311,
                311 to 310, 310 to 415, 415 to 308, 308 to 324, 324 to 318, 318 to 402,
                402 to 317, 317 to 14, 14 to 87, 87 to 178, 178 to 88, 88 to 95, 95 to 78
            ))
            // Nose bridge
            addAll(listOf(
                168 to 6, 6 to 197, 197 to 195, 195 to 5, 5 to 4, 4 to 1,
                1 to 19, 19 to 94, 94 to 2
            ))
            // Nose bottom
            addAll(listOf(
                2 to 98, 98 to 97, 97 to 2, 2 to 326, 326 to 327, 327 to 2
            ))
        }

        // Iris center landmark indices (FL.LEFT_IRIS_CENTER, FL.RIGHT_IRIS_CENTER)
        private val IRIS_DOTS = intArrayOf(FL.LEFT_IRIS_CENTER, FL.RIGHT_IRIS_CENTER)
    }
}
