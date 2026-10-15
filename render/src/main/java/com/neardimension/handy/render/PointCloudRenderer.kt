package com.arhand.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the scan point cloud as GL_POINTS with circular discard.
 * Port of the cloud mesh in the HTML prototype.
 */
class PointCloudRenderer {
    private var program = 0
    private var pointData: FloatArray = FloatArray(0)

    // VBO allocated once and resized only when it needs to grow — the rest of this
    // module avoids per-frame glGenBuffers/glDeleteBuffers, and update() may be
    // called every frame with a similarly-sized cloud.
    private val vbo = IntArray(1)
    private var vboCapacityFloats = 0

    fun init() {
        if (program != 0) return
        program = compileProgram(ShaderPrograms.POINTS_VERT, ShaderPrograms.POINTS_FRAG)
        GLES30.glGenBuffers(1, vbo, 0)
    }

    fun update(points: FloatArray) {
        pointData = points
    }

    fun draw(view: FloatArray, proj: FloatArray) {
        if (pointData.isEmpty()) return
        GLES30.glUseProgram(program)

        val model = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uModel"),      1, false, model, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uView"),       1, false, view,  0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uProjection"), 1, false, proj,  0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uPointSize"), 180f)
        val cloudColor = floatArrayOf(0f, 1f, 0.898f, 0.6f)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(program, "uColor"), 1, cloudColor, 0)

        val buf = ByteBuffer.allocateDirect(pointData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(pointData); position(0) }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        if (pointData.size > vboCapacityFloats) {
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, pointData.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
            vboCapacityFloats = pointData.size
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, pointData.size * 4, buf)
        }
        val loc = GLES30.glGetAttribLocation(program, "aPosition")
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(loc, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointData.size / 3)
    }

    fun release() {
        if (vbo[0] != 0) { GLES30.glDeleteBuffers(1, vbo, 0); vbo[0] = 0 }
        if (program != 0) { GLES30.glDeleteProgram(program); program = 0 }
        vboCapacityFloats = 0
    }

    private fun compileProgram(vertSrc: String, fragSrc: String): Int {
        val vert = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also {
            GLES30.glShaderSource(it, vertSrc); GLES30.glCompileShader(it) }
        val frag = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also {
            GLES30.glShaderSource(it, fragSrc); GLES30.glCompileShader(it) }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vert); GLES30.glAttachShader(it, frag)
            GLES30.glLinkProgram(it)
            GLES30.glDeleteShader(vert); GLES30.glDeleteShader(frag)
        }
    }
}
