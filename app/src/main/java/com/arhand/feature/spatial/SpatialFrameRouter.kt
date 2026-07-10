package com.arhand.feature.spatial

import com.arhand.mocap.MotionRecorder
import com.arhand.mocap.OscStreamer
import com.arhand.mocap.FullBodyRetargetResult
import com.arhand.mocap.VrmBlendShapeParser
import com.arhand.render.ARRenderer
import com.arhand.scanner.FreeformScanner
import com.arhand.scanner.Scanner
import com.arhand.depth.DepthCarver
import com.arhand.depth.HandSegmentationMask
import com.arhand.depth.TSDFVolume
import com.arhand.util.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * SpatialFrameRouter — consumes [SpatialFrame] and distributes to all output systems.
 *
 * ## Responsibilities
 *
 * On each [SpatialFrame]:
 *   - Update the renderer (landmarks, retarget result, body pose, morph weights)
 *   - Send OSC frame (with /depth/map when SL is calibrated)
 *   - Push BVH frame to MotionRecorder
 *   - Feed the scan pipeline (posed scanner, freeform scanner, TSDF integration)
 *   - Update gesture classifier and active gesture state
 *
 * ## Why this exists
 *
 * AppViewModel previously did all of this inline in the handPipeline.collect block.
 * This class moves that responsibility out, leaving AppViewModel as a thin coordinator
 * of UI state and feature lifecycle.
 *
 * ## Thread safety
 *
 * Runs on a dedicated single-thread dispatcher, not [kotlinx.coroutines.Dispatchers.Default] —
 * that pool is shared with long, non-suspending CPU-bound work (DA2's per-frame CNN
 * inference, SlamLite's optical flow), which can occupy every worker thread for the
 * duration of a single pass. Routing every [SpatialFrame] (which drives the renderer's
 * retarget result, OSC streaming, and motion-capture recording) through that same pool
 * meant it could be starved for however long those passes take, making the retargeted
 * puppet visibly lag behind — and drift out of sync with — the raw hand overlay. All
 * outputs that touch the GL thread (renderer) use @Volatile writes or AtomicReference
 * internally as before.
 */
class SpatialFrameRouter(
    private val scope:        CoroutineScope,
    private val oscStreamer:   OscStreamer,
    private val motionRecorder: MotionRecorder,
    private val renderer:     ARRenderer,
    private val scanner:      Scanner,
    private val freeformScanner: FreeformScanner,
    private val tsdfVolume:   TSDFVolume,
    /** Supplies the current camera bitmap for freeform texture-bake sampling (see [route]). */
    private val latestBitmapProvider: () -> android.graphics.Bitmap? = { null }
) {
    // ── Callbacks from AppViewModel ───────────────────────────────────────────

    /** Called when AppViewModel needs to know the latest retarget result (for ScanResultModal). */
    var onRetargetResult: ((com.arhand.mocap.RetargetResult) -> Unit)? = null

    /** Called each frame with the assembled FullBodyRetargetResult. */
    var onFullFrame: ((FullBodyRetargetResult) -> Unit)? = null

    /** Called when a scan gesture or quality gate changes. */
    var onScanFeedback: ((Scanner.ScanStatus) -> Unit)? = null

    // ── Feature flags (set by AppViewModel on toggle) ─────────────────────────

    @Volatile var isStreaming:       Boolean = false
    @Volatile var isRecording:       Boolean = false
    @Volatile var isScanActive:      Boolean = false
    @Volatile var isFreeformActive:  Boolean = false
    @Volatile var depthModeActive:   Boolean = false
    @Volatile var bodyEnabled:       Boolean = false
    @Volatile var faceEnabled:       Boolean = false
    @Volatile var constraintEnabled: Boolean = true
    @Volatile var isFrontCamera:     Boolean = true
    @Volatile var claheContrast:     Float   = 1f

    // Loaded asset for morph weights
    var loadedAsset: com.arhand.mocap.LoadedAsset = com.arhand.mocap.AssetLoader.DEFAULT_PUPPET

    // Scan accumulation buffers (written here, read by AppViewModel for ScanPipeline)
    val capturedFrames      = mutableListOf<Pair<List<Vec3>, Float>>()
    val biometricFrames     = mutableListOf<Pair<com.arhand.tracking.HandLandmarks, Float>>()
    val capturedDepthFrames = mutableListOf<List<Vec3>>()
    val capturedBitmaps     = mutableListOf<android.graphics.Bitmap>()

    private var routerJob: Job? = null

    // Dedicated thread — see the class-level "Thread safety" note above.
    private val routerDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SpatialFrameRouter").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // ── Start / stop ──────────────────────────────────────────────────────────

    fun start(frames: SharedFlow<SpatialFrame>) {
        routerJob = scope.launch(routerDispatcher) {
            frames.collect { frame -> route(frame) }
        }
    }

    fun stop() { routerJob?.cancel() }

    // ── Core routing ──────────────────────────────────────────────────────────

    private fun route(frame: SpatialFrame) {
        val primaryLms = frame.primaryHand?.landmarks
        val aspect     = frame.aspect
        // Real camera capture aspect (bitmap width/height), distinct from the screen-space
        // aspect above — see landmarkToWorld's camAspect parameter.
        val camAspect  = latestBitmapProvider()?.let { it.width.toFloat() / it.height.toFloat() } ?: aspect

        // ── Renderer ──────────────────────────────────────────────────────────
        renderer.handsData = listOfNotNull(
            frame.primaryHand?.landmarks,
            frame.secondaryHand?.landmarks
        )
        renderer.mirrorX = frame.isFrontCamera

        frame.primaryRetarget?.let { result ->
            renderer.latestRetargetResult = result
            onRetargetResult?.invoke(result)
        }

        if (bodyEnabled) {
            renderer.updateBodyPose(frame.body, loadedAsset)
        }

        // Face morph weights
        if (faceEnabled && frame.face != null) {
            val blendMap = loadedAsset.blendShapeMap
            if (blendMap != null) {
                val apps = VrmBlendShapeParser.resolve(frame.face, blendMap)
                renderer.applyMorphWeights(apps)
            }
        }

        // ── Assemble FullBodyRetargetResult (now built here, not in ViewModel) ──
        val fullFrame = FullBodyRetargetResult(
            timestamp           = frame.timestamp,
            handPrimary         = frame.primaryRetarget,
            handSecondary       = frame.secondaryRetarget,
            body                = frame.body,
            face                = frame.face,
            frameConfidence     = frame.frameConfidence,
            metricGrounded      = frame.metricGrounded,
            cameraWorldX        = frame.cameraWorldPos?.x ?: Float.NaN,
            cameraWorldY        = frame.cameraWorldPos?.y ?: Float.NaN,
            cameraWorldZ        = frame.cameraWorldPos?.z ?: Float.NaN,
            depthConfidence     = frame.depthConfidence
        )
        onFullFrame?.invoke(fullFrame)

        // ── OSC streaming ─────────────────────────────────────────────────────
        if (isStreaming) {
            oscStreamer.sendFrame(fullFrame)

            // SL depth for /depth/map
            if (frame.slCalibrated && frame.slDepth != null) {
                oscStreamer.pendingSLResult = com.arhand.depth.StructuredLightDepthSource.SLResult(
                    gradX      = frame.slGradX ?: FloatArray(0),
                    gradY      = frame.slGradY ?: FloatArray(0),
                    depth      = frame.slDepth,
                    modulation = FloatArray(frame.slDepth.size),
                    bW         = 8,
                    bH         = 6
                )
            }

            // v27 — SLAM pose and per-frame delta
            frame.slamPose?.let { p ->
                oscStreamer.sendCameraPose(p.tx, p.ty, p.tz, p.rx, p.ry, p.rz)
            }
            frame.slamDelta?.let { d ->
                oscStreamer.sendCameraPoseDelta(d.tx, d.ty, d.rz)
            }

            // v27 — rPPG heart rate (only when warmed up)
            if (frame.rppgBPM > 0) {
                oscStreamer.sendRppg(frame.rppgAmplitude, frame.rppgBPM)
            }

            // v27 — metric depth grid
            frame.fusedMeters?.let { oscStreamer.sendDepthMetric(it) }

            // v27 — detected planes
            if (frame.planes.isNotEmpty()) {
                oscStreamer.sendPlanes(frame.planes.map { p ->
                    floatArrayOf(p.nx, p.ny, p.nz, p.d) to p.label
                })
            }

            // v27 — per-landmark occlusion probabilities for each hand
            frame.primaryHand?.let { h ->
                oscStreamer.sendHandOcclusion(0,
                    FloatArray(21) { i -> if (i < h.occluded.size && h.occluded[i]) 1f else 0f })
            }
            frame.secondaryHand?.let { h ->
                oscStreamer.sendHandOcclusion(1,
                    FloatArray(21) { i -> if (i < h.occluded.size && h.occluded[i]) 1f else 0f })
            }
        }

        // ── BVH recording ─────────────────────────────────────────────────────
        if (isRecording) {
            motionRecorder.pushFrame(fullFrame)
        }

        // ── Scan accumulation ─────────────────────────────────────────────────

        // Posted scan — same gate and quality source AppViewModel used before this was
        // consolidated here: only accumulate during CAPTURING (PREFLIGHT/COUNTDOWN frames
        // have no real quality signal and would dilute the SDF carver's top-75% filter),
        // and use the Scanner's own pose-hold quality, not the general per-frame tracking
        // confidence — the two are different signals (see ENGINE_ARCHITECTURE.md §4.3/§10.3).
        if (isScanActive && !isFreeformActive && primaryLms != null &&
            scanner.status.value.state == Scanner.ScanState.CAPTURING) {
            val worldFrames = DepthCarver.landmarksToWorld(primaryLms, aspect, frame.isFrontCamera, camAspect)
            val quality     = scanner.status.value.quality
            capturedFrames.add(Pair(worldFrames, quality))
            if (capturedFrames.size % 3 == 0) {
                biometricFrames.add(Pair(primaryLms, quality))
            }
        }

        // Freeform scan
        if (isScanActive && isFreeformActive) {
            val worldFrames = primaryLms?.let { DepthCarver.landmarksToWorld(it, aspect, frame.isFrontCamera, camAspect) }
            freeformScanner.update(
                lms           = primaryLms,
                claheContrast = claheContrast,
                bitmap        = latestBitmapProvider(),
                worldFrames   = worldFrames,
                aspect        = aspect,
                mirrorX       = frame.isFrontCamera,
                nowMs         = frame.timestamp
            )
        }
    }

    // ── Clear scan buffers (called by AppViewModel on scan start/cancel) ──────

    fun clearScanBuffers() {
        capturedFrames.clear()
        biometricFrames.clear()
        capturedDepthFrames.clear()
        capturedBitmaps.clear()
    }
}
