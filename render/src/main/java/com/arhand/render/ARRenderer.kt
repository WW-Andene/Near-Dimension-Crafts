package com.arhand.render

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.arhand.mocap.LoadedAsset
import com.arhand.mocap.RetargetResult
import com.arhand.tracking.FaceLandmarks
import com.arhand.tracking.HandLandmarks
import com.arhand.tracking.PoseLandmarks
import com.arhand.util.PerfMonitor
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * F1 — Atomic RenderCommand replaces 10+ @Volatile fields.
 *
 * All per-frame tracking state is packed into a single immutable data class and
 * published via one AtomicReference.set() call. The GL thread reads it atomically
 * at the start of onDrawFrame — no partial writes, no inconsistent state mid-frame.
 */
data class RenderCommand(
    val hands:              List<HandLandmarks> = emptyList(),
    val mirrorX:            Boolean             = true,
    val renderMode:         RenderMode          = RenderMode.SKELETON,
    val torchOn:            Boolean             = false,
    val showCloud:          Boolean             = false,
    val retargetResult:     RetargetResult?     = null,
    val loadedAsset:        LoadedAsset?        = null,
    val scanCloudPoints:    FloatArray?         = null,
    val depthMeshPositions: FloatArray?         = null,
    /** G1 — Live-deformed scanned mesh positions; non-null while live-mesh mode is active. */
    val liveMeshPositions:  FloatArray?         = null,
    /** Phase 1 — Body pose landmarks for skeleton overlay visualization. */
    val bodyLandmarks:      PoseLandmarks?      = null,
    /** Phase 2 — Face mesh landmarks for face skeleton overlay visualization. */
    val faceLandmarks:      FaceLandmarks?      = null
)

/**
 * Main OpenGL ES 3.0 renderer.
 * GLSurfaceView.Renderer — runs on the GL thread, separate from tracking and UI threads.
 *
 * [onAspectChanged] is invoked from the GL thread whenever the surface dimensions change.
 * AppViewModel wires this to [AppViewModel.currentAspect].
 */
class ARRenderer(private val perfMonitor: PerfMonitor) : GLSurfaceView.Renderer {

    // F1: Single atomic render state — written once per tracking frame, read once per GL frame
    private val renderCommand = AtomicReference(RenderCommand())

    // Convenience setters so AppViewModel call-sites stay minimal

    var handsData: List<HandLandmarks>
        get() = renderCommand.get().hands
        set(v) = renderCommand.updateAndGet { it.copy(hands = v) }.let {}

    var renderMode: RenderMode
        get() = renderCommand.get().renderMode
        set(v) = renderCommand.updateAndGet { it.copy(renderMode = v) }.let {}

    var torchOn: Boolean
        get() = renderCommand.get().torchOn
        set(v) = renderCommand.updateAndGet { it.copy(torchOn = v) }.let {}

    var showCloud: Boolean
        get() = renderCommand.get().showCloud
        set(v) = renderCommand.updateAndGet { it.copy(showCloud = v) }.let {}

    var mirrorX: Boolean
        get() = renderCommand.get().mirrorX
        set(v) = renderCommand.updateAndGet { it.copy(mirrorX = v) }.let {}

    var scanCloudPoints: FloatArray?
        get() = renderCommand.get().scanCloudPoints
        set(v) = renderCommand.updateAndGet { it.copy(scanCloudPoints = v) }.let {}

    var depthMeshPositions: FloatArray?
        get() = renderCommand.get().depthMeshPositions
        set(v) = renderCommand.updateAndGet { it.copy(depthMeshPositions = v) }.let {}

    /** Phase 2 — Face mesh landmarks for skeleton visualization. */
    var faceLandmarks: FaceLandmarks?
        get() = renderCommand.get().faceLandmarks
        set(v) {
            renderCommand.updateAndGet { it.copy(faceLandmarks = v) }
            faceSkeletonRenderer.update(v)
        }

    /** Phase 1 — Body pose landmarks for skeleton visualization. */
    var bodyLandmarks: PoseLandmarks?
        get() = renderCommand.get().bodyLandmarks
        set(v) {
            renderCommand.updateAndGet { it.copy(bodyLandmarks = v) }
            bodySkeletonRenderer.update(v)
        }

    /** G1 — forward deformed mesh positions for live rendering. */
    var liveMeshPositions: FloatArray?
        get() = renderCommand.get().liveMeshPositions
        set(v) {
            renderCommand.updateAndGet { it.copy(liveMeshPositions = v) }
            if (v != null) liveMeshRenderer.updateMesh(v)
            else liveMeshRenderer.clearMesh()
        }

    var loadedAsset: LoadedAsset?
        get() = renderCommand.get().loadedAsset
        set(value) {
            renderCommand.updateAndGet { it.copy(loadedAsset = value) }
            if (value != null) skinnedMeshRenderer.updateAsset(value)
        }

    var latestRetargetResult: RetargetResult?
        get() = renderCommand.get().retargetResult
        set(value) {
            renderCommand.updateAndGet { it.copy(retargetResult = value) }
            if (value != null) skinnedMeshRenderer.updatePose(value)
        }

    /**
     * Gap 4 — Forward body retarget result to [SkinnedMeshRenderer] to drive body joints.
     * Call each frame when body tracking is active.
     */
    fun updateBodyPose(
        bodyResult: com.arhand.mocap.BodyRetargetResult?,
        asset:      com.arhand.mocap.LoadedAsset?
    ) {
        skinnedMeshRenderer.updateBodyPose(bodyResult, asset)
    }

    /**
     * FACE-1 — Forward morph weight applications from [VrmBlendShapeParser] to
     * [SkinnedMeshRenderer]. Called each frame when face tracking is active and
     * a VRM asset with a blend shape map is loaded.
     */
    fun applyMorphWeights(applications: List<com.arhand.mocap.MorphApplication>) {
        skinnedMeshRenderer.applyMorphWeights(applications)
    }

    /**
     * B6 — Last GL texture name allocated for ARCore. Stored so that depth sessions
     * created after surface initialisation (user enables depth mid-session) can still
     * receive [setCameraTextureName] immediately on [toggleDepth].
     */
    var lastArCameraTextureId: Int = 0
        private set

    var onAspectChanged: ((Float) -> Unit)? = null

    /**
     * B6 — Called from the GL thread once a valid EGL context exists.
     * The Int argument is a newly allocated GL texture name that ARCore's camera
     * feed can bind to via [ArDepthSession.setCameraTextureName].
     * AppViewModel wires this to the active [ArDepthSession] in [toggleDepth].
     */
    var onGlSurfaceCreated: ((glTextureId: Int) -> Unit)? = null

    /**
     * B6 — Called from [onDrawFrame] once per frame when ARCore depth mode is active.
     * Allows the caller to tick [ArDepthSession.update] on the GL thread, which is the
     * only thread where [com.google.ar.core.Session.update] is safe to call.
     * Returns the latest [com.arhand.util.Vec3] point cloud (may be empty).
     */
    var onDepthFrameTick: (() -> Unit)? = null

    private val handRenderers        = arrayOf(HandRenderer(), HandRenderer())
    private val depthCloudRenderer   = DepthCloudRenderer()   // stride-4 point tuples (x,y,z,conf)
    private val depthMeshRenderer    = DepthMeshRenderer()
    private val cameraPassthrough    = CameraPassthroughRenderer()
    private val skinnedMeshRenderer  = SkinnedMeshRenderer()
    /** G1 — Renders the live-deforming scanned hand mesh. */
    private val liveMeshRenderer        = LiveMeshRenderer()
    /** Phase 1 — Renders the body pose skeleton overlay. */
    private val bodySkeletonRenderer    = BodySkeletonRenderer()
    /** Phase 2 — Renders the face mesh skeleton overlay. */
    private val faceSkeletonRenderer    = FaceSkeletonRenderer()

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private var aspect     = 1f
    private var timeMs     = 0L

    @Volatile private var camAspect = 1f

    fun submitCameraFrame(bitmap: Bitmap) {
        camAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        cameraPassthrough.camAspect = camAspect
        cameraPassthrough.latestBitmap = bitmap
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // release() before init() on every (re)call: onSurfaceCreated fires again after any
        // EGL context recreation (backgrounding without preserveEGLContextOnPause, rotation,
        // or device-specific GLSurfaceView quirks). release() is always safe to call on an
        // unused or already-released renderer (each guards its own deletes on handle != 0),
        // so this is correct whether the previous context is genuinely dead (the common case —
        // its GL objects were already freed by the driver, this just resets the stale Kotlin
        // handles to 0) or still alive (the release actually frees it, avoiding a leak). This
        // is what makes init()'s own re-entrancy guards (`if (x != 0) return`) safe to rely on
        // here instead of a hazard that could skip re-initialization after context loss.
        cameraPassthrough.release()
        cameraPassthrough.init()
        handRenderers.forEach { it.release(); it.init() }
        depthMeshRenderer.release()
        depthMeshRenderer.init()
        skinnedMeshRenderer.release()
        skinnedMeshRenderer.init()
        liveMeshRenderer.release()
        liveMeshRenderer.init()
        bodySkeletonRenderer.release()
        bodySkeletonRenderer.init()
        faceSkeletonRenderer.release()
        faceSkeletonRenderer.init()
        depthCloudRenderer.release()
        depthCloudRenderer.init()

        // ViewModel so ArDepthSession.setCameraTextureName() can be called.
        // This must happen on the GL thread after a valid EGL context exists.
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        lastArCameraTextureId = texIds[0]
        onGlSurfaceCreated?.invoke(texIds[0])

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 5f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.toFloat()
        Matrix.orthoM(projMatrix, 0, -aspect, aspect, -1f, 1f, -10f, 10f)
        cameraPassthrough.screenAspect = aspect
        onAspectChanged?.invoke(aspect)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // Session.update() must be called here; it is a no-op when depth mode is off.
        onDepthFrameTick?.invoke()

        // F1: Snapshot the command once — consistent across the entire frame
        val cmd = renderCommand.get()

        cameraPassthrough.mirrorX = cmd.mirrorX
        cameraPassthrough.draw()

        timeMs = System.currentTimeMillis()
        val timeSec = (timeMs % 100000L) / 1000f
        val assetLoaded = cmd.loadedAsset != null

        for (i in cmd.hands.indices) {
            val lms = cmd.hands[i]
            handRenderers[i].update(lms, aspect, cmd.mirrorX, camAspect, slot = i)
            if (assetLoaded && i == 0) {
                if (skinnedMeshRenderer.showGhostOverlay) {
                    handRenderers[i].draw(viewMatrix, projMatrix, RenderMode.SKELETON, cmd.torchOn, timeSec)
                }
                skinnedMeshRenderer.draw(viewMatrix, projMatrix, cmd.torchOn, timeSec)
            } else {
                handRenderers[i].draw(viewMatrix, projMatrix, cmd.renderMode, cmd.torchOn, timeSec)
            }
        }
        for (i in cmd.hands.size until handRenderers.size) {
            handRenderers[i].clear()
        }

        if (cmd.showCloud) {
            cmd.scanCloudPoints?.let {

                depthCloudRenderer.updateAndDraw(it, viewMatrix, projMatrix)
            }
        }

        cmd.depthMeshPositions?.let {
            depthMeshRenderer.update(it)
            depthMeshRenderer.draw(viewMatrix, projMatrix, cmd.renderMode)
        }

        if (cmd.liveMeshPositions != null) {
            liveMeshRenderer.draw(viewMatrix, projMatrix, cmd.torchOn, timeSec)
        }

        if (cmd.bodyLandmarks != null) {
            bodySkeletonRenderer.draw(viewMatrix, projMatrix, cmd.mirrorX, camAspect, aspect)
        }

        if (cmd.faceLandmarks != null) {
            faceSkeletonRenderer.draw(viewMatrix, projMatrix, cmd.mirrorX, camAspect, aspect)
        }

        perfMonitor.onFrame()
    }
}

enum class RenderMode {
    MESH,
    WIREFRAME,
    SKELETON,
    ASSET_3D,
    ASSET_2D,
    /** G1 — Scanned mesh deformed live by the retarget pipeline (the "mirror effect"). */
    LIVE_MESH
}
