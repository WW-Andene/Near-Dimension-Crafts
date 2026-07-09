package com.arhand.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.arhand.tracking.HandLandmarks
import com.arhand.tracking.LM
import com.arhand.util.Vec3
import com.arhand.tracking.landmarkToWorld
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.sqrt

/**
 * Renders a single hand in mesh / wireframe / skeleton modes.
 * Maintains VAOs/VBOs for all geometry parts.
 * Port of the HandRenderer class from the HTML prototype.
 */
class HandRenderer {
    companion object {
        // v11 CONNECTIONS — 23 pairs covering all finger segments + palm knuckle bar
        private val CONNECTIONS_V11 = intArrayOf(
            0,1,  1,2,  2,3,  3,4,
            0,5,  5,6,  6,7,  7,8,
            0,9,  9,10, 10,11, 11,12,
            0,13, 13,14, 14,15, 15,16,
            0,17, 17,18, 18,19, 19,20,
            5,9,  9,13, 13,17
        )
    }
    private var phongProgram  = 0
    private var basicProgram  = 0
    private var lineProgram   = 0
    // Cyberpunk skeleton look (SKELETON mode only) — diamond glow joints + pulsing lines.
    private var cyberLineProgram   = 0
    private var cyberPointsProgram = 0

    private val vaos = mutableListOf<Int>()
    private val vbos = mutableListOf<Int>()

    // Skin color — rgba(210,170,120,1) in float
    private val skinColor = floatArrayOf(0.824f, 0.667f, 0.471f)

    // Persistent VAO + VBOs for mesh drawing — allocated once in init(), reused every frame.
    // Using a single VAO for all mesh parts; geometry is re-uploaded per-part via glBufferData.
    private val meshVao = IntArray(1)
    private val meshVbo = IntArray(2)  // [0]=positions, [1]=normals
    private val meshIbo = IntArray(1)

    // Scratch VBOs reused across all draw calls in a frame — eliminates per-call glGenBuffers/glDeleteBuffers.
    private val linesScratchVbo = IntArray(1)
    private val dotsScratchVbo  = IntArray(1)

    // Pre-allocated direct FloatBuffer for skeleton/wireframe line and dot vertex uploads.
    // 512 floats covers 23 connections (138) + all 21 dot vertices (63) with headroom.
    private val skeletonScratchBuf: FloatBuffer = ByteBuffer
        .allocateDirect(512 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // Pre-allocated array for v11 connection line vertices (23 connections × 2 pts × 3 floats).
    private val connScratchArr = FloatArray(23 * 6)

    // Velocity-lerped color state (cyan → orange). Updated each drawSkeleton() call.
    private var velocity: Float = 0f
    private var prevPts:  List<Vec3>? = null

    // Cached uniform/attrib locations — populated once in init() after shader compilation.
    // glGetUniformLocation/glGetAttribLocation are synchronous driver string-hash calls.
    // Calling them every frame (previously ~150 calls/frame/hand at 30fps) produced
    // measurable CPU stalls. Caching reduces this to zero per-frame overhead.
    private val phongLoc  = HashMap<String, Int>(32)
    private val basicLoc  = HashMap<String, Int>(8)
    private val lineLoc   = HashMap<String, Int>(8)
    private val cyberLineLoc   = HashMap<String, Int>(8)
    private val cyberPointsLoc = HashMap<String, Int>(8)

    private fun cacheLocations() {
        // Phong program uniforms + attribs
        for (name in listOf(
            "uModel", "uView", "uProjection", "uNormalMatrix",
            "uCameraPos", "uTime", "uAlpha", "uBaseColor", "uInferred",
            "uAmbientColor", "uAmbientIntensity",
            "uKeyDir", "uKeyColor", "uKeyIntensity",
            "uRimDir", "uRimColor", "uRimIntensity",
            "uSSSDir", "uSSSColor", "uSSSIntensity",
            "uFillDir", "uFillColor", "uFillIntensity",
            "uTorchPos", "uTorchColor", "uTorchIntensity", "uTorchAttenuation", "uTorchOn"
        )) phongLoc[name] = GLES30.glGetUniformLocation(phongProgram, name)
        phongLoc["aPosition"] = GLES30.glGetAttribLocation(phongProgram, "aPosition")
        phongLoc["aNormal"]   = GLES30.glGetAttribLocation(phongProgram, "aNormal")

        // Basic program
        for (name in listOf("uModel", "uView", "uProjection", "uColor"))
            basicLoc[name] = GLES30.glGetUniformLocation(basicProgram, name)
        basicLoc["aPosition"] = GLES30.glGetAttribLocation(basicProgram, "aPosition")

        // Line program
        for (name in listOf("uModel", "uView", "uProjection", "uColor"))
            lineLoc[name] = GLES30.glGetUniformLocation(lineProgram, name)
        lineLoc["aPosition"] = GLES30.glGetAttribLocation(lineProgram, "aPosition")

        // Cyberpunk line program (adds uTime for the pulse)
        for (name in listOf("uModel", "uView", "uProjection", "uColor", "uTime"))
            cyberLineLoc[name] = GLES30.glGetUniformLocation(cyberLineProgram, name)
        cyberLineLoc["aPosition"] = GLES30.glGetAttribLocation(cyberLineProgram, "aPosition")

        // Cyberpunk points program
        for (name in listOf("uModel", "uView", "uProjection", "uColor", "uPointSize"))
            cyberPointsLoc[name] = GLES30.glGetUniformLocation(cyberPointsProgram, name)
        cyberPointsLoc["aPosition"] = GLES30.glGetAttribLocation(cyberPointsProgram, "aPosition")
    }

    fun init() {
        phongProgram  = compileProgram(ShaderPrograms.PHONG_VERT,  ShaderPrograms.PHONG_FRAG)
        basicProgram  = compileProgram(ShaderPrograms.BASIC_VERT,  ShaderPrograms.BASIC_FRAG)
        lineProgram   = compileProgram(ShaderPrograms.LINE_VERT,   ShaderPrograms.LINE_FRAG)
        cyberLineProgram   = compileProgram(ShaderPrograms.LINE_VERT,   ShaderPrograms.CYBER_LINE_FRAG)
        cyberPointsProgram = compileProgram(ShaderPrograms.POINTS_VERT, ShaderPrograms.CYBER_POINT_FRAG)

        GLES30.glGenVertexArrays(1, meshVao, 0)
        GLES30.glGenBuffers(2, meshVbo, 0)
        GLES30.glGenBuffers(1, meshIbo, 0)
        GLES30.glGenBuffers(1, linesScratchVbo, 0)
        GLES30.glGenBuffers(1, dotsScratchVbo, 0)

        cacheLocations()
    }

    private var currentLms: HandLandmarks? = null
    private var currentAspect    = 1f
    private var currentCamAspect = 1f
    private var currentMirrorX   = true
    /** Slot index passed from ARRenderer: 0 = primary/right hand, 1 = secondary/left hand. */
    private var currentSlot      = 0

    fun update(lms: HandLandmarks, aspect: Float, mirrorX: Boolean, camAspect: Float = 1f, slot: Int = 0) {
        currentLms       = lms
        currentAspect    = aspect
        currentMirrorX   = mirrorX
        currentCamAspect = camAspect
        currentSlot      = slot
    }

    fun clear() { currentLms = null }

    fun draw(view: FloatArray, proj: FloatArray, mode: RenderMode, torchOn: Boolean, timeSec: Float) {
        val lms = currentLms ?: return
        if (lms.size < 21) return

        // Map each landmark to GL world-space using image-space (x,y) so the skeleton
        // is anchored to where the hand appears in the camera image, not to the
        // hand-centered metric world coordinate frame (which always sits near screen centre).
        val scaleX = maxOf(currentCamAspect, currentAspect)
        val scaleY = maxOf(1f, currentAspect / currentCamAspect)
        val pts = lms.map { lm ->
            val sx = if (currentMirrorX) 1f - lm.x else lm.x
            val wx = (sx   - 0.5f) * 2f * scaleX
            val wy = -(lm.y - 0.5f) * 2f * scaleY
            val wz = if (lm.worldZ != 0f) -lm.worldZ else lm.z * -0.6f
            Vec3(wx, wy, wz)
        }

        when (mode) {
            RenderMode.MESH,
            RenderMode.LIVE_MESH -> drawMesh(pts, lms, view, proj, torchOn, timeSec)
            RenderMode.WIREFRAME -> drawWireframe(pts, view, proj)
            RenderMode.SKELETON,
            RenderMode.ASSET_3D,
            RenderMode.ASSET_2D  -> drawSkeleton(pts, lms, view, proj, timeSec)
        }
    }

    private fun drawMesh(pts: List<Vec3>, lms: HandLandmarks, view: FloatArray, proj: FloatArray, torchOn: Boolean, timeSec: Float) {
        GLES30.glUseProgram(phongProgram)

        val model = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        val normalMatrix = FloatArray(9) // mat3 from model

        // Compute 3x3 normal matrix from 4x4 model matrix (identity here = same)
        normalMatrix[0] = model[0]; normalMatrix[1] = model[1]; normalMatrix[2] = model[2]
        normalMatrix[3] = model[4]; normalMatrix[4] = model[5]; normalMatrix[5] = model[6]
        normalMatrix[6] = model[8]; normalMatrix[7] = model[9]; normalMatrix[8] = model[10]

        setMatrixUniforms(phongProgram, model, view, proj)
        GLES30.glUniformMatrix3fv(
            GLES30.glGetUniformLocation(phongProgram, "uNormalMatrix"), 1, false, normalMatrix, 0
        )
        setLightingUniforms(phongProgram, torchOn, timeSec, pts)

        GLES30.glUniform1f(phongLoc["uAlpha"]!!, 0.97f)
        GLES30.glUniform1f(phongLoc["uTime"]!!, timeSec)

        // Finger signature colours — slot 0 = warm, slot 1 = cool (RGB only, no alpha)
        val fingerSigColors = if (currentSlot == 0) arrayOf(
            floatArrayOf(1.00f, 0.82f, 0.00f), // thumb  — gold
            floatArrayOf(0.00f, 0.85f, 1.00f), // index  — cyan
            floatArrayOf(0.20f, 1.00f, 0.45f), // middle — green
            floatArrayOf(0.85f, 0.20f, 1.00f), // ring   — violet
            floatArrayOf(1.00f, 0.45f, 0.10f)  // pinky  — orange
        ) else arrayOf(
            floatArrayOf(0.85f, 0.85f, 0.85f), // thumb  — silver
            floatArrayOf(0.40f, 0.70f, 1.00f), // index  — sky blue
            floatArrayOf(0.00f, 0.85f, 0.75f), // middle — teal
            floatArrayOf(1.00f, 0.40f, 0.65f), // ring   — pink
            floatArrayOf(1.00f, 0.75f, 0.00f)  // pinky  — amber
        )

        // Draw each finger tube with a 25% colour tint blended into the skin base
        val unitR = computeUnitR(pts)
        val blendedColor = FloatArray(3)
        for (fi in HandMeshBuilder.FINGER_CHAINS.indices) {
            val chain = HandMeshBuilder.FINGER_CHAINS[fi]
            val prof  = HandMeshBuilder.FINGER_PROFILES[fi]
            val fingerPts = chain.map { pts[it] }
            val radii = floatArrayOf(prof.base * unitR, prof.mid * unitR, prof.mid * unitR * 0.85f, prof.tip * unitR)
            val bulged = HandMeshBuilder.addKnuckleBulge(radii, prof.knuckleMult)

            val mesh = HandMeshBuilder.buildTube(fingerPts, bulged.toList())
            val hasInferred = chain.any { lms[it].inferred }
            GLES30.glUniform1i(phongLoc["uInferred"]!!, if (hasInferred) 1 else 0)
            if (fi < fingerSigColors.size) {
                val fc = fingerSigColors[fi]
                blendedColor[0] = skinColor[0] * 0.75f + fc[0] * 0.25f
                blendedColor[1] = skinColor[1] * 0.75f + fc[1] * 0.25f
                blendedColor[2] = skinColor[2] * 0.75f + fc[2] * 0.25f
                GLES30.glUniform3fv(phongLoc["uBaseColor"]!!, 1, blendedColor, 0)
            }
            drawMeshData(phongProgram, mesh)
        }

        // Palm and webbings use plain skin colour
        GLES30.glUniform3fv(phongLoc["uBaseColor"]!!, 1, skinColor, 0)
        val palmMesh = HandMeshBuilder.buildPalmGeo(pts, unitR)
        GLES30.glUniform1i(phongLoc["uInferred"]!!, 0)
        drawMeshData(phongProgram, palmMesh)

        // Webbings
        for (wi in 0 until 3) {
            try {
                val webMesh = HandMeshBuilder.buildWebbingGeo(pts, wi)
                drawMeshData(phongProgram, webMesh)
            } catch (_: Exception) {}
        }

        // Nails
        GLES30.glUseProgram(basicProgram)
        val nailColor = floatArrayOf(0.9f, 0.82f, 0.78f, 0.85f)
        GLES30.glUniform4fv(basicLoc["uColor"]!!, 1, nailColor, 0)
        setMatrixUniforms(basicProgram, model, view, proj)
        for (fi in HandMeshBuilder.FINGER_CHAINS.indices) {
            val chain = HandMeshBuilder.FINGER_CHAINS[fi]
            val tipIdx = chain.last()
            val nailMesh = HandMeshBuilder.buildNailGeo(computeUnitR(pts) * HandMeshBuilder.FINGER_PROFILES[fi].tip)
            // Translate nail to tip position
            val tipPos = pts[tipIdx]
            val tipModel = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            Matrix.translateM(tipModel, 0, tipPos.x, tipPos.y, tipPos.z)
            setMatrixUniforms(basicProgram, tipModel, view, proj)
            drawMeshData(basicProgram, nailMesh)
        }
    }

    private fun drawWireframe(pts: List<Vec3>, view: FloatArray, proj: FloatArray) {
        GLES30.glUseProgram(lineProgram)
        val model = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        setMatrixUniforms(lineProgram, model, view, proj)
        val colorLoc = lineLoc["uColor"] ?: return

        // Same slot-indexed palette as skeleton mode — warm (slot 0) or cool (slot 1).
        val fingerColors = if (currentSlot == 0) arrayOf(
            floatArrayOf(1.00f, 0.82f, 0.00f, 0.75f), // thumb  — gold
            floatArrayOf(0.00f, 0.85f, 1.00f, 0.75f), // index  — cyan
            floatArrayOf(0.20f, 1.00f, 0.45f, 0.75f), // middle — green
            floatArrayOf(0.85f, 0.20f, 1.00f, 0.75f), // ring   — violet
            floatArrayOf(1.00f, 0.45f, 0.10f, 0.75f)  // pinky  — orange
        ) else arrayOf(
            floatArrayOf(0.85f, 0.85f, 0.85f, 0.75f), // thumb  — silver
            floatArrayOf(0.40f, 0.70f, 1.00f, 0.75f), // index  — sky blue
            floatArrayOf(0.00f, 0.85f, 0.75f, 0.75f), // middle — teal
            floatArrayOf(1.00f, 0.40f, 0.65f, 0.75f), // ring   — pink
            floatArrayOf(1.00f, 0.75f, 0.00f, 0.75f)  // pinky  — amber
        )

        for (fi in HandMeshBuilder.FINGER_CHAINS.indices) {
            if (fi < fingerColors.size) GLES30.glUniform4fv(colorLoc, 1, fingerColors[fi], 0)
            val chain = HandMeshBuilder.FINGER_CHAINS[fi]
            val fingerPts = chain.map { pts[it] }
            drawLineStrip(fingerPts)
        }
    }

    private fun drawSkeleton(pts: List<Vec3>, lms: HandLandmarks, view: FloatArray, proj: FloatArray, timeSec: Float) {
        val model = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        // Cyberpunk HUD look: neon magenta at rest → electric cyan on fast motion,
        // diamond glow joints (CYBER_POINT_FRAG), pulsing energy lines (CYBER_LINE_FRAG).
        val prev = prevPts
        if (prev != null && prev.size == pts.size) {
            var sumDist = 0f
            for (i in pts.indices) {
                val dx = pts[i].x - prev[i].x; val dy = pts[i].y - prev[i].y
                sumDist += sqrt(dx * dx + dy * dy)
            }
            velocity = (sumDist / pts.size * 20f).coerceIn(0f, 1f)
        }
        prevPts = pts
        val t = velocity
        // rest: hot magenta (1.00, 0.12, 0.85) → motion: electric cyan (0.10, 0.95, 1.00)
        val cr = 1.00f - 0.90f * t
        val cg = 0.12f + 0.83f * t
        val cb = 0.85f + 0.15f * t

        // ── All 23 connections — pulsing neon lines ───────────────────────────
        GLES30.glUseProgram(cyberLineProgram)
        setMatrixUniforms(cyberLineProgram, model, view, proj)
        val colorLoc = cyberLineLoc["uColor"] ?: return
        val lineTimeLoc = cyberLineLoc["uTime"] ?: -1
        GLES30.glUniform4f(colorLoc, cr, cg, cb, 0.55f)
        if (lineTimeLoc >= 0) GLES30.glUniform1f(lineTimeLoc, timeSec)

        var vi = 0
        var ci = 0
        while (ci < CONNECTIONS_V11.size) {
            val a = CONNECTIONS_V11[ci]; val b = CONNECTIONS_V11[ci + 1]; ci += 2
            if (a < pts.size && b < pts.size) {
                connScratchArr[vi++] = pts[a].x; connScratchArr[vi++] = pts[a].y; connScratchArr[vi++] = pts[a].z
                connScratchArr[vi++] = pts[b].x; connScratchArr[vi++] = pts[b].y; connScratchArr[vi++] = pts[b].z
            }
        }
        if (vi > 0) {
            skeletonScratchBuf.clear()
            skeletonScratchBuf.put(connScratchArr, 0, vi)
            skeletonScratchBuf.flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, linesScratchVbo[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vi * 4, skeletonScratchBuf, GLES30.GL_DYNAMIC_DRAW)
            skeletonScratchBuf.limit(skeletonScratchBuf.capacity())
            val posLoc = cyberLineLoc["aPosition"] ?: -1
            if (posLoc >= 0) {
                GLES30.glEnableVertexAttribArray(posLoc)
                GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)
            }
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, vi / 3)
        }

        // ── Joint dots — 3 size passes, diamond glow markers ──────────────────
        GLES30.glUseProgram(cyberPointsProgram)
        setMatrixUniforms(cyberPointsProgram, model, view, proj)
        val ptColorLoc = cyberPointsLoc["uColor"] ?: return
        val ptSizeLoc  = cyberPointsLoc["uPointSize"] ?: -1
        val ptPosLoc   = cyberPointsLoc["aPosition"]  ?: -1
        GLES30.glUniform4f(ptColorLoc, cr, cg, cb, 0.9f)

        // Wrist — reactor-core marker, largest
        drawDotsV11(pts, intArrayOf(0), ptPosLoc, ptSizeLoc, 26f)
        // Fingertips — medium
        drawDotsV11(pts, intArrayOf(4, 8, 12, 16, 20), ptPosLoc, ptSizeLoc, 18f)
        // All other joints — small
        drawDotsV11(pts, intArrayOf(1,2,3, 5,6,7, 9,10,11, 13,14,15, 17,18,19), ptPosLoc, ptSizeLoc, 12f)
    }

    private fun drawDotsV11(pts: List<Vec3>, indices: IntArray, posLoc: Int, sizeLoc: Int, size: Float) {
        if (posLoc < 0) return
        GLES30.glUniform1f(sizeLoc, size)
        skeletonScratchBuf.clear()
        var n = 0
        for (idx in indices) {
            if (idx < pts.size) {
                skeletonScratchBuf.put(pts[idx].x)
                skeletonScratchBuf.put(pts[idx].y)
                skeletonScratchBuf.put(pts[idx].z)
                n++
            }
        }
        if (n == 0) return
        skeletonScratchBuf.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dotsScratchVbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 3 * 4, skeletonScratchBuf, GLES30.GL_DYNAMIC_DRAW)
        skeletonScratchBuf.limit(skeletonScratchBuf.capacity())
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n)
    }

    // ─── GL helpers ───────────────────────────────────────────────────────

    private fun computeUnitR(pts: List<Vec3>): Float {
        if (pts.size < 10) return 0.03f
        val w = pts[0]; val mk = pts[9]
        val d = (w - mk).length()
        return d * 0.18f
    }

    private fun setMatrixUniforms(prog: Int, model: FloatArray, view: FloatArray, proj: FloatArray) {
        // Each compiled program gets its own uniform-location numbering — reusing
        // one program's cached locations for a different program only "works" if
        // the GLSL compiler happens to assign matching indices, which isn't
        // guaranteed. Every program used here needs its own map.
        val locs = when (prog) {
            phongProgram        -> phongLoc
            basicProgram        -> basicLoc
            lineProgram         -> lineLoc
            cyberLineProgram    -> cyberLineLoc
            cyberPointsProgram  -> cyberPointsLoc
            else                -> error("setMatrixUniforms: unknown program $prog")
        }
        GLES30.glUniformMatrix4fv(locs["uModel"]!!,      1, false, model, 0)
        GLES30.glUniformMatrix4fv(locs["uView"]!!,       1, false, view,  0)
        GLES30.glUniformMatrix4fv(locs["uProjection"]!!, 1, false, proj,  0)
    }

    private fun setLightingUniforms(prog: Int, torchOn: Boolean, timeSec: Float, pts: List<Vec3>) {
        val L = LightingModel
        fun setV3(name: String, v: FloatArray) =
            GLES30.glUniform3fv(phongLoc[name]!!, 1, v, 0)
        fun setF(name: String, v: Float) =
            GLES30.glUniform1f(phongLoc[name]!!, v)
        fun setI(name: String, v: Int) =
            GLES30.glUniform1i(phongLoc[name]!!, v)

        setV3("uAmbientColor",    L.ambientColor);   setF("uAmbientIntensity", L.ambientIntensity)
        setV3("uKeyDir",          L.keyDir);          setV3("uKeyColor", L.keyColor);   setF("uKeyIntensity",  L.keyIntensity)
        setV3("uRimDir",          L.rimDir);          setV3("uRimColor", L.rimColor);   setF("uRimIntensity",  L.rimIntensity)
        setV3("uSSSDir",          L.sssDir);          setV3("uSSSColor", L.sssColor);   setF("uSSSIntensity",  L.sssIntensity)
        setV3("uFillDir",         L.fillDir);         setV3("uFillColor", L.fillColor); setF("uFillIntensity", L.fillIntensity)

        // Torch position — index fingertip (landmark 8)
        if (pts.size > 8) {
            setV3("uTorchPos", floatArrayOf(pts[8].x, pts[8].y, pts[8].z))
        }
        setV3("uTorchColor", L.torchColor)
        setF("uTorchIntensity", L.torchIntensity)
        GLES30.glUniform3fv(phongLoc["uTorchAttenuation"]!!, 1, L.torchAttenuation, 0)
        setI("uTorchOn", if (torchOn) 1 else 0)

        setV3("uCameraPos", floatArrayOf(0f, 0f, 5f))
        setF("uTime", timeSec)
    }

    private fun drawMeshData(prog: Int, mesh: HandMeshBuilder.MeshData) {
        if (mesh.positions.isEmpty()) return

        val locs = if (prog == phongProgram) phongLoc else basicLoc

        GLES30.glBindVertexArray(meshVao[0])

        // Positions
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo[0])
        val posBuf = toFloatBuffer(mesh.positions)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.positions.size * 4, posBuf, GLES30.GL_DYNAMIC_DRAW)
        val posLoc = locs["aPosition"] ?: -1
        if (posLoc >= 0) {
            GLES30.glEnableVertexAttribArray(posLoc)
            GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)
        }

        // Normals
        if (mesh.normals.isNotEmpty()) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo[1])
            val normBuf = toFloatBuffer(mesh.normals)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.normals.size * 4, normBuf, GLES30.GL_DYNAMIC_DRAW)
            val normLoc = locs["aNormal"] ?: -1
            if (normLoc >= 0) {
                GLES30.glEnableVertexAttribArray(normLoc)
                GLES30.glVertexAttribPointer(normLoc, 3, GLES30.GL_FLOAT, false, 0, 0)
            }
        }

        // Indices
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, meshIbo[0])
        val idxBuf = toIntBuffer(mesh.indices)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, idxBuf, GLES30.GL_DYNAMIC_DRAW)

        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indices.size, GLES30.GL_UNSIGNED_INT, 0)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun drawLineStrip(pts: List<Vec3>) {
        val verts = FloatArray(pts.size * 3)
        pts.forEachIndexed { i, p -> verts[i*3] = p.x; verts[i*3+1] = p.y; verts[i*3+2] = p.z }
        drawLines(verts, GLES30.GL_LINE_STRIP)
    }

    private fun drawLines(verts: FloatArray, mode: Int = GLES30.GL_LINES) {
        skeletonScratchBuf.clear()
        skeletonScratchBuf.put(verts)
        skeletonScratchBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, linesScratchVbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, skeletonScratchBuf, GLES30.GL_DYNAMIC_DRAW)
        val loc = lineLoc["aPosition"] ?: -1
        if (loc >= 0) {
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribPointer(loc, 3, GLES30.GL_FLOAT, false, 0, 0)
        }
        GLES30.glDrawArrays(mode, 0, verts.size / 3)
    }

    private fun toFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

    private fun toIntBuffer(data: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(data); position(0) }

    private fun compileProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vert)
            GLES30.glAttachShader(it, frag)
            GLES30.glLinkProgram(it)
            GLES30.glDeleteShader(vert)
            GLES30.glDeleteShader(frag)
        }
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, src)
            GLES30.glCompileShader(it)
        }
}
