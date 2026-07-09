package com.arhand.render

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws a full-screen camera preview behind the hand mesh.
 *
 * Uses GL_TEXTURE_2D because frames arrive as Bitmaps from CameraFrameProvider.
 * GPU texture storage is allocated once on the first frame (texImage2D), then
 * updated with texSubImage2D on subsequent frames — no per-frame reallocation.
 *
 * Depth writes and depth test are disabled while drawing so the quad always
 * sits behind all hand geometry regardless of draw order.
 *
 * Front-camera horizontal flip is handled in the vertex shader via uMirrorX.
 */
class CameraPassthroughRenderer {

    private var programId  = 0
    private var vboId      = 0
    private var textureId  = 0

    private var uTextureLoc = 0
    private var uMirrorXLoc = 0
    private var uCropULoc   = 0
    private var uCropVLoc   = 0
    private var posLoc      = 0
    private var uvLoc       = 0

    @Volatile var latestBitmap:  Bitmap? = null
    @Volatile var mirrorX:       Boolean = true
    @Volatile var screenAspect:  Float   = 1f
    @Volatile var camAspect:     Float   = 1f

    // Track whether the texture has had its storage allocated yet
    private var textureInitialised = false
    private var texWidth  = 0
    private var texHeight = 0

    // Fullscreen quad: NDC positions + UVs (two CCW triangles)
    // UV (0,0) = top-left, (1,1) = bottom-right — matches Android Bitmap layout
    private val quadVerts = floatArrayOf(
        // x      y     u     v
        -1f,  -1f,  0f,  1f,   // bottom-left
         1f,  -1f,  1f,  1f,   // bottom-right
        -1f,   1f,  0f,  0f,   // top-left
         1f,  -1f,  1f,  1f,   // bottom-right
         1f,   1f,  1f,  0f,   // top-right
        -1f,   1f,  0f,  0f    // top-left
    )

    private lateinit var quadBuffer: FloatBuffer

    fun init() {
        quadBuffer = ByteBuffer
            .allocateDirect(quadVerts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVerts); position(0) }

        programId   = buildProgram(ShaderPrograms.CAMERA_VERT, ShaderPrograms.CAMERA_FRAG)
        uTextureLoc = GLES30.glGetUniformLocation(programId, "uTexture")
        uMirrorXLoc = GLES30.glGetUniformLocation(programId, "uMirrorX")
        uCropULoc   = GLES30.glGetUniformLocation(programId, "uCropU")
        uCropVLoc   = GLES30.glGetUniformLocation(programId, "uCropV")
        posLoc      = GLES30.glGetAttribLocation(programId, "aPosition")
        uvLoc       = GLES30.glGetAttribLocation(programId, "aUV")

        // VBO — static quad geometry, allocated once
        val buf = IntArray(1)
        GLES30.glGenBuffers(1, buf, 0)
        vboId = buf[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            quadVerts.size * 4,
            quadBuffer,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        // Texture object — storage allocated on first frame
        GLES30.glGenTextures(1, buf, 0)
        textureId = buf[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * Call once per frame from onDrawFrame, BEFORE drawing hand geometry.
     * Uploads the latest Bitmap if one is available, then draws the fullscreen quad.
     */
    fun draw() {
        val bmp = latestBitmap ?: return

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

        if (!textureInitialised || bmp.width != texWidth || bmp.height != texHeight) {
            // First upload or resolution change: allocate GPU storage
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            texWidth  = bmp.width
            texHeight = bmp.height
            textureInitialised = true
        } else {
            // Subsequent frames: update in place — no reallocation
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bmp)
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        // Draw — disable depth so quad always sits behind hand geometry
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        GLES30.glUseProgram(programId)

        // Center-crop: remove aspect mismatch between camera frame and screen.
        val ca = camAspect; val sa = screenAspect
        val cropU = if (ca > sa) (1f - sa / ca) / 2f else 0f
        val cropV = if (ca < sa) (1f - ca / sa) / 2f else 0f

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uTextureLoc, 0)
        GLES30.glUniform1i(uMirrorXLoc, if (mirrorX) 1 else 0)
        GLES30.glUniform1f(uCropULoc, cropU)
        GLES30.glUniform1f(uCropVLoc, cropV)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)

        val stride = 4 * 4  // 4 floats × 4 bytes

        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, stride, 0)

        GLES30.glEnableVertexAttribArray(uvLoc)
        GLES30.glVertexAttribPointer(uvLoc,  2, GLES30.GL_FLOAT, false, stride, 2 * 4)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(uvLoc)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        // Restore depth state for hand mesh rendering
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    fun release() {
        if (vboId     != 0) GLES30.glDeleteBuffers(1,  intArrayOf(vboId),      0)
        if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId),  0)
        if (programId != 0) GLES30.glDeleteProgram(programId)
        textureInitialised = false
    }

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES30.GL_VERTEX_SHADER,   vertSrc)
        val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        return GLES30.glCreateProgram().also { prog ->
            GLES30.glAttachShader(prog, vert)
            GLES30.glAttachShader(prog, frag)
            GLES30.glLinkProgram(prog)
            GLES30.glDeleteShader(vert)
            GLES30.glDeleteShader(frag)
        }
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, src)
            GLES30.glCompileShader(shader)
        }
}
