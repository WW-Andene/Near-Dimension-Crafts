package com.arhand.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Standalone OpenGL ES 3.0 renderer for the post-scan 3D model viewer.
 *
 * Camera model: orbit + pinch-zoom (no AR passthrough, no hand tracking).
 *   - azimuth / elevation angles driven by touch drag
 *   - radius driven by pinch span delta
 *   - always looks at the world origin
 *
 * ARCH-3 — When [retargetResult] is non-null and a [loadedAsset] with a skin is set,
 * the viewer drives the asset's bone matrices from the live retarget result, making
 * it a live mirror of the tracked performance rather than a static post-scan display.
 */
class ModelViewerRenderer : GLSurfaceView.Renderer {

    /** Set from AppViewModel after scan completes. Thread-safe (volatile). */
    @Volatile var meshPositions: FloatArray = FloatArray(0)

    /**
     * ARCH-3 — Live retarget result from the tracking pipeline.
     * When non-null and [loadedAsset] has a skin, the bone palette is updated
     * from this result each frame, animating the loaded character in real time.
     */
    @Volatile var retargetResult: com.arhand.mocap.RetargetResult? = null

    /**
     * ARCH-3 — Loaded skinned asset. When non-null, [SkinnedMeshRenderer] drives it
     * from [retargetResult]. Falls back to raw mesh rendering when null.
     */
    @Volatile var loadedAsset: com.arhand.mocap.LoadedAsset? = null

    // ── Camera state (mutated from the UI thread via touch handlers) ──────────
    @Volatile var azimuthDeg:   Float = 30f
    @Volatile var elevationDeg: Float = 20f
    @Volatile var orbitRadius:  Float = 3.5f

    private var phongProgram = 0
    private val vboHandle     = IntArray(1)
    private var vboCapacity   = 0
    private var vertexCount   = 0
    private var geometryDirty = false
    private var lastMeshRef: FloatArray? = null

    // ARCH-3 — Skinned mesh renderer for the loaded asset (character animation)
    private val skinnedRenderer = SkinnedMeshRenderer()
    private var lastAssetRef: com.arhand.mocap.LoadedAsset? = null

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private var aspect     = 1f

    // ── GLSurfaceView.Renderer ────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.05f, 0.06f, 0.08f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        phongProgram = buildProg(ShaderPrograms.PHONG_VERT, ShaderPrograms.PHONG_FRAG)
        GLES30.glGenBuffers(1, vboHandle, 0)
        skinnedRenderer.init()   // ARCH-3

        Matrix.setLookAtM(viewMatrix, 0,
            0f, 0f, orbitRadius,
            0f, 0f, 0f,
            0f, 1f, 0f
        )
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, 0.1f, 50f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        buildViewMatrix()

        val asset  = loadedAsset
        val result = retargetResult

        // ARCH-3 — If a skinned asset and live retarget result are both present,
        // drive the character skeleton and render via SkinnedMeshRenderer.
        if (asset != null && asset.hasSkin && result != null) {
            // Sync asset to skinned renderer on change
            if (asset !== lastAssetRef) {
                skinnedRenderer.updateAsset(asset)
                lastAssetRef = asset
            }
            skinnedRenderer.updatePose(result)

            val timeSec = (System.nanoTime() / 1_000_000_000.0).toFloat()
            skinnedRenderer.draw(viewMatrix, projMatrix, torchOn = false, timeSec = timeSec)
            return
        }

        // Fallback: render raw scan mesh (static, no skeleton)
        val positions = meshPositions
        if (positions.isEmpty()) return

        if (positions !== lastMeshRef) { lastMeshRef = positions; geometryDirty = true }
        if (geometryDirty) { uploadGeometry(positions); geometryDirty = false }

        val model = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        GLES30.glUseProgram(phongProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(phongProgram, "uModel"),      1, false, model,      0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(phongProgram, "uView"),       1, false, viewMatrix, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(phongProgram, "uProjection"), 1, false, projMatrix, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(phongProgram, "uBaseColor"), 1, floatArrayOf(0.4f, 0.85f, 1.0f), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(phongProgram, "uAlpha"), 0.92f)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(phongProgram, "uTorchOn"),   0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(phongProgram, "uInferred"),  0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboHandle[0])
        val loc = GLES30.glGetAttribLocation(phongProgram, "aPosition")
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(loc, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glDisableVertexAttribArray(loc)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildViewMatrix() {
        val azRad  = Math.toRadians(azimuthDeg.toDouble()).toFloat()
        val elRad  = Math.toRadians(elevationDeg.toDouble()).toFloat()
        val r      = orbitRadius
        val eyeX   = r * cos(elRad) * sin(azRad)
        val eyeY   = r * sin(elRad)
        val eyeZ   = r * cos(elRad) * cos(azRad)
        Matrix.setLookAtM(viewMatrix, 0,
            eyeX, eyeY, eyeZ,
            0f, 0f, 0f,
            0f, 1f, 0f
        )
    }

    private fun uploadGeometry(verts: FloatArray) {
        val byteSize = verts.size * 4
        val buf = java.nio.ByteBuffer.allocateDirect(byteSize)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(verts); position(0) }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboHandle[0])
        if (verts.size > vboCapacity) {
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, byteSize, buf, GLES30.GL_DYNAMIC_DRAW)
            vboCapacity = verts.size
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, byteSize, buf)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        vertexCount = verts.size / 3
    }

    fun release() {
        if (vboHandle[0] != 0) { GLES30.glDeleteBuffers(1, vboHandle, 0); vboHandle[0] = 0 }
        if (phongProgram != 0) { GLES30.glDeleteProgram(phongProgram);     phongProgram = 0 }
        skinnedRenderer.release()   // ARCH-3
    }

    private fun buildProg(v: String, f: String): Int {
        val vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also   { GLES30.glShaderSource(it, v); GLES30.glCompileShader(it) }
        val fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also { GLES30.glShaderSource(it, f); GLES30.glCompileShader(it) }
        return GLES30.glCreateProgram().also { GLES30.glAttachShader(it, vs); GLES30.glAttachShader(it, fs); GLES30.glLinkProgram(it) }
    }
}
