package com.arhand.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.camera.core.CameraSelector
import androidx.lifecycle.LifecycleOwner
import com.arhand.camera.CameraController
import com.arhand.camera.CameraFrameProvider
import com.arhand.camera.BitmapGrayscaleShim
import com.arhand.depth.ArDepthAvailability
import com.arhand.depth.DepthSourceCallback
import com.arhand.depth.DepthApiCarver
import com.arhand.depth.HandSegmentationMask
import com.arhand.depth.NeuralImplicitCarver
import com.arhand.depth.ManoShapeFitter
import com.arhand.depth.fusion.FusedDepthSource
import com.arhand.export.GLBExporter
import com.arhand.export.PointCloudExporter
import com.arhand.render.ARRenderer
import com.arhand.render.RenderMode
import com.arhand.scanner.CLAHEAnalyzer
import com.arhand.scanner.PersonalModelStore
import com.arhand.scanner.Scanner
import com.arhand.scanner.PhotometricStereoCapture
import com.arhand.mocap.AssetLoader
import com.arhand.mocap.BoneRetargeter
import com.arhand.mocap.BodyRetargeter
import com.arhand.mocap.BodyRetargetResult
import com.arhand.mocap.FullBodyRetargetResult
import com.arhand.mocap.LoadedAsset
import com.arhand.mocap.MotionRecorder
import com.arhand.mocap.OscStreamer
import com.arhand.mocap.OscSchema
import com.arhand.mocap.OscMode
import com.arhand.mocap.OscReceiver
import com.arhand.mocap.QuaternionEmaFilter
import com.arhand.mocap.RetargetResult
import com.arhand.tracking.HandPipeline
import com.arhand.tracking.HandTrackerManager
import com.arhand.tracking.HandTracker
import com.arhand.tracking.HandLandmarks
import com.arhand.tracking.BodyPipeline
import com.arhand.tracking.FaceLandmarks
import com.arhand.tracking.FacePipeline
import com.arhand.tracking.PoseLandmarks
import com.arhand.tracking.FullBodyFrame
import com.arhand.tracking.CompositeGestureClassifier
import com.arhand.tracking.Gesture
import com.arhand.util.FrameThrottler
import com.arhand.util.ModelBudgetManager
import com.arhand.util.PerfMonitor
import com.arhand.util.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.arhand.scanner.HandBiometrics
import com.arhand.render.LiveMeshDeformer
import com.arhand.feature.record.TakeEntry
import com.arhand.feature.scan.NeuralReconDiagnostics

/**
 * Gap 5 — A single completed recording take.
 *
 * @param label    Human-readable name entered by the user before recording.
 * @param bvhPath  Absolute path to the exported BVH file, or null if export failed.
 * @param gltfPath Absolute path to the exported animated GLB, or null.
 * @param durationMs Recording duration in milliseconds.
 * @param frameCount Number of frames captured.
 */
/**
 * Gap 7 — Top-level workflow mode separating scan, stream, record, and settings.
 * Determines which controls are shown in the bottom control panel.
 */
data class AppUiState(
    val renderMode:    RenderMode    = RenderMode.SKELETON,
    val torchOn:       Boolean       = false,
    val showCloud:     Boolean       = false,
    val roomMapActive: Boolean       = false,
    /** Absolute path of the last PLY export from [AppViewModel.exportRoomMap], or null. */
    val roomMapExportPath: String?   = null,
    val isFrontCamera: Boolean       = true,
    val scanActive:    Boolean       = false,
    val showSplash:    Boolean       = true,
    val showOnboarding: Boolean      = false,
    val liveMeshActive: Boolean      = false,
    val workflowMode:  WorkflowMode  = WorkflowMode.IDLE,
    /** True once the user has denied the CAMERA permission request. */
    val cameraPermissionDenied: Boolean = false,
    /**
     * Monotonically-incrementing token bumped once per accepted pose capture — see
     * [com.arhand.ui.WhiteScreenOverlay] (ENGINE_ARCHITECTURE.md §6.2). A plain Boolean
     * can't signal "fire again" for two captures in a row; each new value fires exactly
     * one flash regardless of whether the previous one finished animating.
     */
    val captureFlashToken: Int = 0
)

// DataStore delegates — must be top-level per Kotlin DataStore contract.
// One singleton instance per application process regardless of ViewModel lifecycle.
private val Context.onboardingDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "handy_onboarding")

private val Context.oefDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "handy_oef")

/**
 * Application-level coordinator — owns the sensing pipeline, scan lifecycle, and UI state.
 *
 * Constructs and wires together [spatialLayer] (Core: ARCore/SfM/Photometric/SLAM/rPPG/DA2),
 * `producer` ([com.arhand.feature.spatial.SpatialFrameProducer], the camera loop and MediaPipe
 * tracking pipelines), `router` ([com.arhand.feature.spatial.SpatialFrameRouter], which
 * distributes assembled frames to the renderer/OSC/BVH/scanner), `scanCoordinator`
 * ([com.arhand.feature.scan.ScanCoordinator], posed/freeform scan lifecycle), and `renderer`
 * ([com.arhand.render.ARRenderer]) — then exposes [uiState] plus feature-toggle/action methods
 * (`toggleTorch`, `switchCamera`, `startScan`, etc.) as the single surface Compose UI reads from
 * and calls into.
 *
 * See `ENGINE_ARCHITECTURE.md` §2 for the module/layer model this class sits at the top of, and
 * `REDESIGN_PLAN.md` Phase 8 item 5 for why scan-lifecycle orchestration specifically was
 * extracted into `scanCoordinator` rather than staying inline here.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    // DataStore instances declared at file top level (see below class)

    private val KEY_ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")

    // ── PERF-2: OEF calibration DataStore keys ───────────────────────────────
    private val KEY_OEF_CUTOFF = androidx.datastore.preferences.core.floatPreferencesKey("oef_cutoff")
    private val KEY_OEF_BETA   = androidx.datastore.preferences.core.floatPreferencesKey("oef_beta")

    /** Persist dismissal and hide the onboarding screen. */
    fun dismissOnboarding() {
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().onboardingDataStore.edit { prefs ->
                prefs[KEY_ONBOARDING_SEEN] = true
            }
        }
        uiState.value = uiState.value.copy(showOnboarding = false)
    }

    val uiState = MutableStateFlow(AppUiState())

    // ── Sensing pipelines (owned by producer) ─────────────────────────────────
    val perfMonitor    = PerfMonitor()
    val handPipeline   = HandPipeline()
    val scanner        = Scanner()
    val freeformScanner = com.arhand.scanner.FreeformScanner()
    val clahe          = CLAHEAnalyzer()
    val frameThrottler = com.arhand.util.FrameThrottler()
    val bodyPipeline   = BodyPipeline()
    val facePipeline   = FacePipeline()
    val bodyRetargeter = BodyRetargeter   // stateless object — see its class doc
    val spatialLayer   = com.arhand.depth.SpatialLayer(getApplication())
    val slDepthSource  = com.arhand.depth.StructuredLightDepthSource()

    val motionRecorder = MotionRecorder()
    val oscStreamer = OscStreamer()

    // ── Feature managers ──────────────────────────────────────────────────────
    val trackingManager  = com.arhand.feature.tracking.TrackingManager()
    val oscManager       = com.arhand.feature.stream.OscManager(getApplication(), oscStreamer, viewModelScope)
    val recordingManager = com.arhand.feature.record.RecordingManager(getApplication(), motionRecorder, viewModelScope)
    val assetManager     = com.arhand.feature.asset.AssetManager(getApplication(), viewModelScope)
    val scanState        = MutableStateFlow(com.arhand.feature.scan.ScanState())
    val oscHealth        = oscStreamer.health
    val oscDiscovery     = com.arhand.feature.stream.OscDiscovery(getApplication())

    val modelStore       = PersonalModelStore(app)
    val photoStereoCapture = PhotometricStereoCapture()
    val renderer         = ARRenderer(perfMonitor)
    val currentAspect: MutableStateFlow<Float> = MutableStateFlow(9f / 16f)

    lateinit var cameraController: CameraController

    // ── SpatialFrameProducer — owns camera loop and sensing ───────────────────
    val producer = com.arhand.feature.spatial.SpatialFrameProducer(
        app            = getApplication(),
        scope          = viewModelScope,
        spatialLayer   = spatialLayer,
        slSource       = slDepthSource,
        handPipeline   = handPipeline,
        bodyPipeline   = bodyPipeline,
        facePipeline   = facePipeline,
        bodyRetargeter = bodyRetargeter,
        frameThrottler = frameThrottler,
        clahe          = clahe,
        modelBudget    = ModelBudgetManager(),
        depthBudget    = com.arhand.util.DepthChannelBudget(),
        perfMonitor    = perfMonitor
    )

    private val tsdfVolume = com.arhand.depth.TSDFVolume()
    // ── SpatialFrameRouter — distributes frames to all consumers ─────────────
    val router = com.arhand.feature.spatial.SpatialFrameRouter(
        scope               = viewModelScope,
        oscStreamer         = oscStreamer,
        motionRecorder      = motionRecorder,
        renderer            = renderer,
        scanner             = scanner,
        freeformScanner     = freeformScanner,
        tsdfVolume          = tsdfVolume,
        latestBitmapProvider = { producer.latestBitmap }
    )

    // ── Scan accumulation (delegated to router) ───────────────────────────────
    // capturedFrames/capturedScanBitmaps moved fully to scanCoordinator with the scan
    // start/cancel/process functions that were their only callers (REDESIGN_PLAN Phase 8
    // item 5); capturedDepthFrames/biometricFrames still have other callers here.
    private val capturedDepthFrames get() = router.capturedDepthFrames
    private val biometricFrames     get() = router.biometricFrames

    /** R1 — Latest camera bitmap. Written each frame by producer. */
    private val latestBitmap: Bitmap? get() = producer.latestBitmap

    val depthMeshPositions: MutableStateFlow<FloatArray> = MutableStateFlow(FloatArray(0))

    /**
     * Hot-path StateFlow: currently recognised gesture on the primary hand.
     * Updated at inference rate (up to 30fps) — kept out of [AppUiState] so
     * gesture changes do not trigger full-tree Compose recomposition.
     * Screens that need gestures collect this directly.
     */
    val activeGesture: MutableStateFlow<Gesture?> = MutableStateFlow(null)

    /**
     * Hot-path StateFlow: photometric stereo pair count during scan.
     * Updated each camera frame during active stereo capture.
     */
    val photoStereoFrameCount: MutableStateFlow<Int> = MutableStateFlow(0)

    /**
     * Hot-path StateFlow: true once [PhotometricStereoCapture.TARGET_PAIRS] reached.
     */
    val photoStereoComplete: MutableStateFlow<Boolean> = MutableStateFlow(false)

    // ── G1 — Live mesh deformation ────────────────────────────────────────
    /**
     * CPU-side LBS deformer. Holds the skinning weights computed from the scanned mesh
     * + the rest-pose joint positions captured at scan time.
     */
    private val liveMeshDeformer = LiveMeshDeformer()

    // restJointPositions now lives on scanCoordinator (REDESIGN_PLAN Phase 8 item 5) —
    // read via scanCoordinator.restJointPositions.

    // ── Mocap state ────────────────────────────────────────────────────────
    /** Currently loaded asset. Starts as the default puppet (no mesh, symmetric bind pose). */
    /** ARCH-3 — Exposed for [ModelViewerScreen] live character animation. */
    /**
     * Mirror of [assetManager.loadedAsset] kept for synchronous GL-thread access.
     * Updated via collect reactor in init — do not write directly.
     * External readers should prefer [assetManager.loadedAsset] StateFlow.
     */
    internal var loadedAsset: LoadedAsset = AssetLoader.DEFAULT_PUPPET
        private set

    /**
     * Retargeter for the OSC-velocity/live-mesh-preview path only — the canonical
     * per-frame retarget (primary + secondary hand, body alignment, morph weights,
     * OSC frame streaming, motion-capture recording) happens once inside
     * [producer]/[router]; this instance just feeds the two things that path
     * doesn't cover.
     */
    private var boneRetargeter: BoneRetargeter = BoneRetargeter(AssetLoader.DEFAULT_PUPPET.bindPose)

    /** Most recent retarget result — updated every tracking frame. Null until first frame. */
    val latestRetargetResult: MutableStateFlow<RetargetResult?> = MutableStateFlow(null)

    /** IMP-1 — Speed-adaptive quaternion EMA filter applied after retargeting. */
    private val quaternionEmaFilter = QuaternionEmaFilter()

    // incrementalCarver/incrementalTrainJob now live on scanCoordinator (REDESIGN_PLAN
    // Phase 8 item 5) — used exclusively by scan start/cancel/process, which moved with them.

    /** G3 — OSC receiver — listens for incoming hand pose data from a remote Handy device. */
    val oscReceiver = OscReceiver()

    /**
     * BODY-1 — Most recent body retarget result. Null until body tracking is enabled
     * and the first frame is processed. Published each hand-pipeline frame when body
     * tracking is active, consumed by OSC streamer and SkinnedMeshRenderer (future sprints).
     */
    val latestBodyRetargetResult: MutableStateFlow<BodyRetargetResult?> = MutableStateFlow(null)

    /**
     * ARCH-1 — Most recent unified full-body frame. Assembles hand + body + face into
     * one structure per frame. Null until the first hand frame is processed.
     * Consumed by OSC, MotionRecorder, and future GLBAnimationExporter.
     */
    val latestFullBodyRetargetResult: MutableStateFlow<FullBodyRetargetResult?> = MutableStateFlow(null)

    /** Latest unified full-body frame combining pose + face + both hands. */
    val latestFullBodyFrame: MutableStateFlow<FullBodyFrame?> = MutableStateFlow(null)

    init {

        // Fused depth is always available — SfM works on any device with a camera.
        // depthApiAvailable now reflects whether ARCore HW depth is present (for
        // UI labelling only — the button is always shown).
        viewModelScope.launch(Dispatchers.IO) {
            val arcoreDepthPresent = ArDepthAvailability.isDepthApiSupported(getApplication())
            withContext(Dispatchers.Main) {
                scanState.value = scanState.value.copy(depthApiAvailable = true, arcoreDepthHw = arcoreDepthPresent)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val prefs = getApplication<Application>().onboardingDataStore.data.first()
            val seen  = prefs[KEY_ONBOARDING_SEEN] ?: false
            if (!seen) {
                withContext(Dispatchers.Main) {
                    uiState.value = uiState.value.copy(showOnboarding = true)
                }
            }
        }

        // ASSET-1 — Restore last loaded asset URI on cold start.
        // AssetManager restores last-used asset in its own init block.
        // OscManager restores saved host/port/schema in its own init block.
        // BundledAssetGenerator is called from AssetManager.init.

        // PERF-2 — Restore OEF calibration on cold start
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = getApplication<Application>().oefDataStore.data.first()
            val cutoff = prefs[KEY_OEF_CUTOFF]
            val beta   = prefs[KEY_OEF_BETA]
            if (cutoff != null && beta != null) handPipeline.setOefParams(cutoff, beta)
        }
        renderer.onAspectChanged = { aspect ->
            currentAspect.value = aspect
            producer.setAspect(aspect)
        }

        // Asset changes → update producer's retargeter bind pose + renderer
        viewModelScope.launch {
            assetManager.loadedAsset.collect { asset ->
                loadedAsset = asset
                producer.onAssetLoaded(asset)
                router.loadedAsset  = asset
                renderer.loadedAsset = if (asset == AssetLoader.DEFAULT_PUPPET) null else asset
            }
        }

        // GL hooks → producer
        renderer.onGlSurfaceCreated = { textureId -> producer.setCameraTextureName(textureId) }
        renderer.onDepthFrameTick   = { producer.onGlFrame() }

        // Camera frames → renderer background texture. Via a callback rather than a second
        // collector on producer's frameProvider.frames (ENGINE_ARCHITECTURE.md §5.3) —
        // frameProvider is now private to SpatialFrameProducer.
        producer.onCameraFrame = { bitmap -> renderer.submitCameraFrame(bitmap) }

        // Producer → SpatialFrame → router → all consumers
        router.onRetargetResult = { result ->
            latestRetargetResult.value = result
        }
        router.onFullFrame = { full ->
            latestFullBodyRetargetResult.value = full
        }
        router.start(producer.frames)

        // Keep producer.scanActive in sync with uiState.scanActive from one place,
        // instead of threading a second flag through every scan start/cancel/complete
        // call site (startScan/cancelScan/processScan/startFreeformScan/...).
        viewModelScope.launch {
            uiState.collect { producer.scanActive = it.scanActive }
        }

        // Wire tracking results → producer.assembleFrame() → SpatialFrame → router.
        // All retargeting, SL z-correction, body alignment, morph weights, OSC, BVH,
        // and motion-capture recording happen once inside producer/router (wired
        // above via router.onRetargetResult/onFullFrame). This block used to
        // recompute the entire retarget a second time here with its own separate
        // BoneRetargeter/QuaternionEmaFilter instances — doubling CPU cost every
        // frame, double-recording motion-capture frames, double-sending OSC frames,
        // and racing router's result on the same latestRetargetResult/renderer
        // fields (a likely source of skeleton jitter, not just wasted CPU). Only
        // the OSC angular-velocity stream and the live-mesh preview aren't covered
        // by the router path, so only the minimal computation they need stays here.
        viewModelScope.launch {
            handPipeline.processed.collect { hands ->
                producer.assembleFrame(
                    hands             = hands,
                    aspect            = currentAspect.value,
                    constraintEnabled = trackingManager.state.value.constraintEnabled
                )
                // renderer.handsData/mirrorX were written here directly *and* by
                // SpatialFrameRouter.route() from the same producer.assembleFrame() output —
                // two writers racing on the same fields (ENGINE_ARCHITECTURE.md §4.4). The
                // router's post-assembly values are canonical; this direct write is removed.

                val primary = hands.firstOrNull()
                primary?.let { hand ->
                    // BODY-3 — Provide body wrist hint when body tracking is active
                    val bodyResult   = latestBodyRetargetResult.value
                    val bodyWristPos = if (trackingManager.state.value.bodyEnabled && bodyResult != null)
                        if (hand.slotIndex == 1) bodyResult.wristLeft else bodyResult.wristRight
                    else null

                    val result = boneRetargeter.retarget(
                        lms           = hand.landmarks,
                        aspect        = currentAspect.value,
                        mirrorX       = uiState.value.isFrontCamera,
                        bodyWristHint = bodyWristPos,
                        bodyWristVis  = bodyResult?.confidence ?: 0f,
                        // Camera capture aspect differs from the screen's — without this,
                        // landmarkToWorld's crop compensation silently disables itself
                        // (defaults camAspect = aspect) and the retargeted mesh/puppet
                        // drifts from where the hand actually is in the cropped preview.
                        camAspect     = latestBitmap?.let { it.width.toFloat() / it.height.toFloat() }
                            ?: currentAspect.value
                    )
                    if (result != null) {
                        val smoothedResult = quaternionEmaFilter.apply(result)

                        if (oscStreamer.sendVelocity) {
                            oscStreamer.sendVelocityFrame(quaternionEmaFilter.lastAngularVelocity)
                        }

                        if (uiState.value.liveMeshActive && liveMeshDeformer.hasMesh()) {
                            val deformed = liveMeshDeformer.deform(smoothedResult)
                            renderer.liveMeshPositions = deformed
                        }
                    }
                } ?: run {

                    // prev[] entries don't lerp from the old pose when the hand reappears.
                    // HandPipeline already resets its own OEF/OcclusionEngine/segConstraint
                    // on the same event; this keeps AppViewModel's layer in sync.
                    quaternionEmaFilter.reset()
                }

                // ARCH-4 — Update per-pipeline quality metrics for HUD display
                perfMonitor.updateQuality(
                    hand = if (primary != null) 1f else 0f,
                    body = latestBodyRetargetResult.value?.confidence ?: 0f,
                    face = if (trackingManager.state.value.faceEnabled) 1f else 0f
                )

                // Feed scanner if active — use real device aspect
                if (uiState.value.scanActive) {
                    val aspect  = currentAspect.value
                    scanner.update(
                        lms             = primary?.landmarks,
                        claheContrast   = clahe.lastContrastScore,
                        nowMs           = System.currentTimeMillis(),
                        aspect          = aspect,
                        mirrorX         = uiState.value.isFrontCamera,
                        faceExpressions = facePipeline.expressions.value.takeIf { trackingManager.state.value.faceEnabled },
                        // BODY-5 — pass body landmarks for stability gating when active
                        bodyLandmarks   = bodyPipeline.processed.value.takeIf { trackingManager.state.value.bodyEnabled },
                        // Same crop-compensation fix as boneRetargeter.retarget() above —
                        // without this, capturePosePoints()'s interpolated cloud points
                        // (which have no MediaPipe world coords of their own) default
                        // camAspect = aspect and come out systematically distorted.
                        camAspect       = latestBitmap?.let { it.width.toFloat() / it.height.toFloat() }
                            ?: aspect
                    )

                    // capturedFrames/biometricFrames for the posed scan are now written
                    // exclusively by SpatialFrameRouter.route() (same CAPTURING-state gate,
                    // same scanner.status.value.quality source) — see REDESIGN_PLAN.md Phase 2.
                    // This block still owns fused-depth/TSDF integration, which was not part
                    // of that migration.
                    if (uiState.value.scanActive &&
                        scanner.status.value.state == Scanner.ScanState.CAPTURING) {
                        primary?.landmarks?.let { lms ->
                            if (lms.size == 21) {
                                // Fused depth — snapshot from all active sources (ARCore + SfM + Photo)
                                if (scanState.value.depthMode) {
                                    val fused = spatialLayer.fusedDepth
                                    if (fused != null) {
                                        val store = fused.store
                                        val needed = store.snapshot()
                                        if (needed >= 4) {
                                            val buf = FloatArray(needed)
                                            store.snapshot(buf)
                                            val cloud = ArrayList<com.arhand.util.Vec3>(needed / 4)
                                            var bi = 0
                                            while (bi + 3 < needed) {
                                                cloud.add(com.arhand.util.Vec3(buf[bi], buf[bi+1], buf[bi+2]))
                                                bi += 4
                                            }
                                            // ENGINE_ARCHITECTURE.md §4.11 — hull built from real
                                            // per-landmark world position (same ARCore frame as
                                            // `cloud`), not MediaPipe's hand-centred world landmarks.
                                            // Falls back to unfiltered when metric depth isn't
                                            // available yet (not tracking / DA2 not XR-calibrated) —
                                            // better than filtering against a wrong coordinate frame.
                                            val hull = HandSegmentationMask.buildHullMetric(lms) { nx, ny ->
                                                spatialLayer.unprojectLandmarkToWorld(nx, ny)
                                            }
                                            val masked = if (hull != null)
                                                HandSegmentationMask.filterPointCloud(cloud, hull) else cloud
                                            capturedDepthFrames.add(masked)
                                            // HAND-8 — Integrate into TSDF volume for surface-aware reconstruction
                                            val depthConf = (store.pointCount / 10000f).coerceIn(0.3f, 1f)
                                            val camPos = spatialLayer.state.value.let {
                                                com.arhand.util.Vec3(it.cameraWorldX, it.cameraWorldY, it.cameraWorldZ)
                                            }
                                            tsdfVolume.integrate(masked, depthConf, camPos)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Freeform capture (freeformScanner.update) is now called exclusively by
                    // SpatialFrameRouter.route() — this used to also call it inline here,
                    // double-invoking it on every frame from two different threads
                    // (ENGINE_ARCHITECTURE.md §4.3). This block still owns freeform's
                    // depth/TSDF integration, which was not part of that migration.
                    if (scanState.value.freeformActive) {
                        // Depth integration for freeform — same ARCore/SfM path as posed scan
                        if (scanState.value.depthMode) {
                            primary?.landmarks?.let { lms ->
                                if (lms.size == 21) {
                                    val fused = spatialLayer.fusedDepth
                                    if (fused != null) {
                                        val store = fused.store
                                        val needed = store.snapshot()
                                        if (needed >= 4) {
                                            val buf = FloatArray(needed)
                                            store.snapshot(buf)
                                            val cloud = ArrayList<com.arhand.util.Vec3>(needed / 4)
                                            var bi = 0
                                            while (bi + 3 < needed) {
                                                cloud.add(com.arhand.util.Vec3(buf[bi], buf[bi+1], buf[bi+2]))
                                                bi += 4
                                            }
                                            // ENGINE_ARCHITECTURE.md §4.11 — hull built from real
                                            // per-landmark world position (same ARCore frame as
                                            // `cloud`), not MediaPipe's hand-centred world landmarks.
                                            // Falls back to unfiltered when metric depth isn't
                                            // available yet (not tracking / DA2 not XR-calibrated) —
                                            // better than filtering against a wrong coordinate frame.
                                            val hull = HandSegmentationMask.buildHullMetric(lms) { nx, ny ->
                                                spatialLayer.unprojectLandmarkToWorld(nx, ny)
                                            }
                                            val masked = if (hull != null)
                                                HandSegmentationMask.filterPointCloud(cloud, hull) else cloud
                                            capturedDepthFrames.add(masked)
                                            val depthConf = (store.pointCount / 10000f).coerceIn(0.3f, 1f)
                                            val camPos = spatialLayer.state.value.let {
                                                com.arhand.util.Vec3(it.cameraWorldX, it.cameraWorldY, it.cameraWorldZ)
                                            }
                                            tsdfVolume.integrate(masked, depthConf, camPos)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ENGINE_ARCHITECTURE.md §10.2 — renderer.showCloud was a fully working
                    // toggle/render path with nothing feeding renderer.scanCloudPoints. Sample
                    // the same live point-cloud store scan-capture already reads, independent
                    // of scan state — this is a live preview, not scan-scoped.
                    if (uiState.value.showCloud) {
                        val store = spatialLayer.fusedDepth.store
                        val needed = store.snapshot()
                        if (needed >= 4) {
                            val buf = FloatArray(needed)
                            store.snapshot(buf)
                            renderer.scanCloudPoints = buf
                        }
                    }
                }
            }
        }

        // Scan/freeform-scan status watchers + initial scanState history load now live on
        // scanCoordinator (REDESIGN_PLAN.md Phase 8, item 5) — started from a separate init
        // block below, after scanCoordinator's own declaration (Kotlin runs property
        // initializers/init blocks in textual order, so it can't be started from here).

        // Phase 1 — Body skeleton: enable body tracking and forward landmarks to GL renderer.
        enableBodyTracking(true)
        viewModelScope.launch {
            bodyPipeline.processed.collect { poseLms ->
                renderer.bodyLandmarks = poseLms
            }
        }

        // Phase 2 — Face skeleton: forward face landmarks to GL renderer when face tracking is on.
        viewModelScope.launch {
            facePipeline.processed.collect { faceLms ->
                renderer.faceLandmarks = faceLms
            }
        }
    }

    /**
     * Called when the user denies (or has permanently denied) the CAMERA permission
     * request. Without this, [uiState.showSplash] never clears — since it's only
     * set false inside [initCamera], which only runs from the granted-permission
     * callback — leaving the app stuck on the splash screen forever with no
     * explanation or retry path.
     */
    fun onCameraPermissionDenied() {
        uiState.update { it.copy(cameraPermissionDenied = true) }
    }

    fun initCamera(owner: LifecycleOwner) {
        uiState.update { it.copy(cameraPermissionDenied = false) }
        val cc = producer.createCameraController(getApplication())
        cameraController = cc
        producer.isFrontCamera = uiState.value.isFrontCamera
        producer.init()

        //   1. Update AppUiState.activeGesture for HUD display.
        //   2. Dispatch built-in shortcuts on gesture change (edge-triggered).
        viewModelScope.launch {
            var lastGesture: Gesture? = null
            handPipeline.gestures.collect { gestureList ->
                val primary = gestureList.getOrNull(0)
                // Update state whenever gesture changes (including null)
                if (primary != lastGesture) {
                    activeGesture.value = primary
                    // Fire shortcut on newly recognized gesture (not on null)
                    if (primary != null) dispatchGestureShortcut(primary)
                    lastGesture = primary
                }
            }
        }

        val facing = if (uiState.value.isFrontCamera) CameraSelector.LENS_FACING_FRONT
                     else CameraSelector.LENS_FACING_BACK
        cc.start(owner, facing)
        cc.setTorch(uiState.value.torchOn)
        uiState.value = uiState.value.copy(showSplash = false)

        // ENGINE_ARCHITECTURE.md §10.1 — starts the FullBodyFrame merge collector, which
        // also fixes latestBodyRetargetResult having no writer at all (see that method's doc).
        ensureFullBodyCollector()
    }

    fun toggleRenderMode() {
        val next = when (renderer.renderMode) {
            RenderMode.SKELETON  -> RenderMode.WIREFRAME
            RenderMode.WIREFRAME -> RenderMode.MESH
            RenderMode.MESH      -> RenderMode.SKELETON
            RenderMode.ASSET_3D  -> RenderMode.SKELETON
            RenderMode.ASSET_2D  -> RenderMode.SKELETON
            RenderMode.LIVE_MESH -> RenderMode.SKELETON
        }
        renderer.renderMode = next
        uiState.update { it.copy(renderMode = next) }
    }

    /**
     * G1 — Toggle the live-deforming scanned hand mesh on/off.
     *
     * Requires a completed scan ([hasStoredModel] == true). On first activation,
     * [LiveMeshDeformer.buildSkinWeights] is called with the current scanned mesh and
     * rest-pose joint positions, then the tracking loop begins forwarding deformed
     * positions to [ARRenderer.liveMeshPositions] every frame.
     */
    fun toggleLiveMesh() {
        if (!(assetManager.state.value.name != null)) return
        val nowActive = !uiState.value.liveMeshActive

        if (nowActive) {
            // Ensure deformer is bound to the current scanned mesh
            val meshPos = depthMeshPositions.value
            val restJoints = scanCoordinator.restJointPositions
            if (meshPos.isNotEmpty() && restJoints != null && !liveMeshDeformer.hasMesh()) {
                liveMeshDeformer.buildSkinWeights(meshPos, restJoints)
            }
        } else {
            renderer.liveMeshPositions = null
        }

        uiState.update { it.copy(liveMeshActive = nowActive) }
    }

    /**
     * G2 — Toggle neural implicit reconstruction on/off.
     *
     * When enabled, the next completed scan will:
     * 1. Run [ManoShapeFitter] to compute per-segment radius corrections.
     * 2. Train [NeuralImplicitCarver] on the scan's landmark frames (~2 s on Snapdragon 8 Gen 2).
     * 3. Extract the mesh from the learned occupancy field (replaces [DepthCarver]).
     *
     * ARCore Depth API path ([depthMode]) still takes precedence over neural reconstruction
     * when both are enabled.
     *
     * The toggle is safe to call before, during, or after a scan — it only affects the
     * next [processScan] invocation.
     */
    fun toggleNeuralRecon() {
        scanState.value = scanState.value.copy(neuralReconEnabled = !scanState.value.neuralReconEnabled)
    }

    /**
     * HAND-1 — Toggle the biomechanical constraint filter on/off.
     *
     * When enabled, joint rotations are projected into anatomically feasible ROM ranges
     * after [QuaternionEmaFilter]. Eliminates hyperextension and other impossible poses.
     * Off by default — enable via Settings or the debug HUD.
     */
    fun toggleConstraint() {
        trackingManager.toggleConstraint()
    }

    /**
     * G5 — Toggle photometric stereo flash capture on/off.
     *
     * When enabled, the scan will alternate torch ON/OFF every frame during
     * CAPTURING, submit frames to [PhotometricStereoCapture], and produce a
     * [com.arhand.scanner.PhotometricNormalMap] stored in [photoStereoNormalMap]
     * once [PhotometricStereoCapture.TARGET_PAIRS] pairs are accumulated.
     *
     * Note: requires rear camera (torch unavailable on front camera). When the
     * front camera is active the toggle is silently ignored.
     */
    fun togglePhotoStereo() {
        if (uiState.value.isFrontCamera) return
        scanState.value = scanState.value.copy(photoStereoEnabled = !scanState.value.photoStereoEnabled)
    }

    /** G5 — Most recent photometric normal map. Null until the first stereo-enabled scan. */
    val photoStereoNormalMap: MutableStateFlow<com.arhand.scanner.PhotometricNormalMap?> =
        MutableStateFlow(null)

    // ── ScanCoordinator — owns posed/freeform scan lifecycle orchestration ────
    // (REDESIGN_PLAN.md Phase 8, item 5). Declared after every collaborator it takes by
    // reference, since a class property's initializer runs at the point of its own
    // declaration — placing this earlier would pass still-uninitialized properties in.
    val scanCoordinator = com.arhand.feature.scan.ScanCoordinator(
        scope                = viewModelScope,
        app                  = getApplication(),
        uiState              = uiState,
        scanState            = scanState,
        scanner              = scanner,
        freeformScanner      = freeformScanner,
        router               = router,
        spatialLayer         = spatialLayer,
        tsdfVolume           = tsdfVolume,
        modelStore           = modelStore,
        photoStereoCapture   = photoStereoCapture,
        photoStereoNormalMap = photoStereoNormalMap,
        photoStereoFrameCount = photoStereoFrameCount,
        photoStereoComplete  = photoStereoComplete,
        renderer             = renderer,
        depthMeshPositions   = depthMeshPositions,
        currentAspect        = currentAspect,
        liveMeshDeformer     = liveMeshDeformer,
        handPipeline         = handPipeline,
        getCameraController  = { cameraController },
        latestBitmap         = { latestBitmap },
        persistOefCalibration = { cutoff, beta ->
            getApplication<Application>().oefDataStore.edit { prefs ->
                prefs[KEY_OEF_CUTOFF] = cutoff
                prefs[KEY_OEF_BETA]   = beta
            }
        }
    )

    init {
        // Must run after scanCoordinator's own declaration above — see the comment at the
        // old call site (REDESIGN_PLAN.md Phase 8, item 5).
        scanCoordinator.start()
    }

    fun toggleTorch() {
        val next = !uiState.value.torchOn
        renderer.torchOn = next
        // Also drive the physical torch on the rear camera
        if (::cameraController.isInitialized) {
            cameraController.setTorch(next)
        }
        uiState.update { it.copy(torchOn = next) }
    }

    fun toggleCloud() {
        uiState.update { it.copy(showCloud = !it.showCloud) }
        renderer.showCloud = uiState.value.showCloud
    }

    fun switchCamera() {
        // Guard against calling before initCamera() has run, same as toggleTorch()
        // above — currently unreachable via UI (the flip button is gated behind
        // !showSplash) but any new caller invoked pre-init would otherwise throw.
        if (!::cameraController.isInitialized) return

        // SpatialLayer SfM/Photo need the rear camera — pause TSDF accumulation but
        // spatialLayer itself continues (ARCore also requires rear camera so it will
        // drop to SfM-only on front camera, which is the correct degradation).
        if (scanState.value.depthMode) {
            scanState.value = scanState.value.copy(depthMode = false)
            capturedDepthFrames.clear()
        }
        // SL calibration is camera-specific — reset on switch
        producer.resetSLCalibration()

        // ARCore's Session always holds the rear camera via its own independent Camera2
        // handle (there's no ARCore Shared-Camera integration here) — it must release that
        // handle before CameraController can bind to the same physical camera, or the bind
        // hangs waiting for a device ARCore is still holding open. This is why switching TO
        // the rear camera previously froze the screen: CameraX's bindToLifecycle() was
        // contending with ARCore's own open session for the same hardware camera
        // (ENGINE_ARCHITECTURE.md §4.9).
        spatialLayer.pauseArcoreCameraHold()
        cameraController.switchCamera()
        val nowFront = cameraController.isFrontFacing()
        // Only reacquire once CameraX has moved off the rear camera again — while
        // CameraX itself is on rear, ARCore can't share that same physical device.
        if (nowFront) spatialLayer.resumeArcoreCameraHold()
        producer.isFrontCamera = nowFront
        router.isFrontCamera   = nowFront
        // Front camera has no torch — turn it off physically and sync UI state.
        if (nowFront && uiState.value.torchOn) {
            cameraController.setTorch(false)
            renderer.torchOn = false
            uiState.update { it.copy(isFrontCamera = true, torchOn = false) }
        } else {
            uiState.update { it.copy(isFrontCamera = nowFront) }
        }
    }

    /**
     * Toggle TSDF accumulation for scan reconstruction, and the reconstruction-only
     * depth sub-sources (photometric stereo, dual-camera stereo, RS-stereo, PSP) that
     * feed it — see [SpatialLayer.setReconstructionActive]. ARCore/SfM metric grounding
     * stays always-on regardless.
     *
     * Front camera is still blocked because TSDF accumulation from SfM/ARCore needs
     * the rear camera's metric point cloud.
     */
    fun toggleDepth() {
        if (uiState.value.isFrontCamera) return  // TSDF accumulation requires rear camera
        val next = !scanState.value.depthMode
        if (next) {
            // Clear stale depth frames from any prior session
            capturedDepthFrames.clear()
            scanState.value = scanState.value.copy(depthMode = true)
            spatialLayer.setReconstructionActive(true)
        } else {
            scanState.value = scanState.value.copy(depthMode = false)
            capturedDepthFrames.clear()
            spatialLayer.setReconstructionActive(false)
        }
    }

    /**
     * Toggle accumulating a persistent, spatially-deduplicated room-scale point map
     * (see [com.arhand.depth.SpatialLayer.voxelGrid]'s class doc) — distinct from
     * [toggleDepth]'s scan-reconstruction sources and from the live scan buffer.
     * Off by default; toggling off does not clear already-accumulated points, so
     * re-enabling continues the same map — call [clearRoomMap] to start over.
     */
    fun toggleRoomMap() {
        val next = !uiState.value.roomMapActive
        spatialLayer.setRoomMapActive(next)
        uiState.update { it.copy(roomMapActive = next) }
    }

    /** Discard all accumulated room-map points without affecting [toggleRoomMap]'s on/off state. */
    fun clearRoomMap() {
        spatialLayer.clearRoomMap()
        uiState.update { it.copy(roomMapExportPath = null) }
    }

    /**
     * Export the current room-map points as ASCII PLY. No-op if empty. Result path is
     * published to [AppUiState.roomMapExportPath] once the (IO-dispatcher) write completes.
     */
    fun exportRoomMap() {
        val points = spatialLayer.roomMapPoints()
        if (points.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val file = PointCloudExporter.exportPly(getApplication(), points)
            uiState.update { it.copy(roomMapExportPath = file.absolutePath) }
        }
    }

    // ── Scan lifecycle — thin forwarding to scanCoordinator ───────────────────
    // (REDESIGN_PLAN.md Phase 8, item 5). Logic/state live on scanCoordinator now;
    // these exist only so MainActivity's existing vm.startScan() etc. call sites don't change.
    fun startScan()          = scanCoordinator.startScan()
    fun cancelScan()         = scanCoordinator.cancelScan()
    fun startFreeformScan()  = scanCoordinator.startFreeformScan()
    fun finishFreeformScan() = scanCoordinator.finishFreeformScan()
    fun cancelFreeformScan() = scanCoordinator.cancelFreeformScan()

        /**
     * Load a user GLB asset from [uri] on the IO dispatcher, then rebuild [boneRetargeter].
     * Pass null to revert to the default puppet.
     *
     * Called from the UI layer after the file picker returns a result:
     * ```kotlin
     * val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
     *     viewModel.loadAsset(uri, uri?.lastPathSegment)
     * }
     * launcher.launch("model/gltf-binary")
     * ```
     */
    fun loadAsset(uri: Uri?, displayName: String?) {
        if (uri == null) { removeAsset(); return }
        assetManager.loadFromUri(uri, displayName ?: "asset.glb")
    }

    fun loadBundledAsset(filename: String, displayName: String) =
        assetManager.loadBundled(filename, displayName)

    /** ASSET-1 — Remove the currently loaded user asset and revert to the default puppet. */
    fun removeAsset() = assetManager.remove()

    /**
     * GAP-6 — Re-derive OneEuroFilter parameters from the most recently captured
     * biometric frames and persist them to DataStore.
     *
     * Called when the user taps "RECALIBRATE FILTER" in Settings. Uses the same
     * [calibrateOef] logic that runs automatically after each scan, so the result
     * is identical to what the scan would have calibrated — but available on-demand
     * for sessions that began before a scan was run.
     *
     * No-op if fewer than 8 biometric frames are available (not enough data).
     */
    fun recalibrateOef() {
        val frames = biometricFrames.toList()
        if (frames.size < 8) {
            android.util.Log.d("AppViewModel", "recalibrateOef: only ${frames.size} frames, need ≥8")
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val (cutoff, beta) = com.arhand.feature.scan.calibrateOef(frames) ?: return@launch
                handPipeline.setOefParams(cutoff, beta)
                getApplication<android.app.Application>().oefDataStore.edit { prefs ->
                    prefs[KEY_OEF_CUTOFF] = cutoff
                    prefs[KEY_OEF_BETA]   = beta
                }
                android.util.Log.d("AppViewModel", "OEF recalibrated: cutoff=$cutoff beta=$beta")
            }.onFailure { android.util.Log.e("AppViewModel", "OEF recalibration failed", it) }
        }
    }

    /**
     * GAP-5 — Trigger the credit-card calibration flow.
     *
     * The credit card (ISO 7810 ID-1: 85.6 × 54mm) is held beside the hand.
     * The user taps two corners in the camera frame; the UI layer computes the
     * card width in world units and calls [saveCalibration] with the derived scale.
     *
     * This function signals the UI to enter calibration mode. The actual scale
     * computation happens in [HandBiometrics.calibrateFromCard] after the user
     * marks the two corners. For now this posts a flag to the scan state so the
     * UI can show the calibration overlay; the UI calls [saveCalibration] with
     * the result.
     */
    fun launchCalibrationCardFlow() {
        // Signal the UI to enter calibration mode (UI shows the card tap overlay)
        scanState.value = scanState.value.copy(calibrationCardActive = true)
    }

    fun dismissCalibrationCard() {
        scanState.value = scanState.value.copy(calibrationCardActive = false)
    }

    // ─── D2: Calibration card scale ───────────────────────────────────────────
    /**
     * Persist a calibrated world-to-mm scale factor derived from a credit-card measurement.
     * Pass null to revert to the built-in [HandBiometrics.WORLD_TO_MM] default.
     *
     * Usage: after the user completes the calibration card flow, the UI layer passes the
     * measured card width in world units to [HandBiometrics.calibrateFromCard], then calls
     * this function with the result:
     * ```kotlin
     * val scale = HandBiometrics.calibrateFromCard(measuredWidthWorld)
     * if (scale != null) viewModel.saveCalibration(scale)
     * ```
     */
    fun saveCalibration(scaleWorldToMm: Float?) {
        viewModelScope.launch(Dispatchers.IO) {
            modelStore.saveCalibration(scaleWorldToMm)
        }
    }

    /**
     * Load the currently stored calibration scale from DataStore.
     * Returns [HandBiometrics.WORLD_TO_MM] (800f) if no calibration has been performed.
     * The result is typically used to convert biometric world-unit values to mm in the UI.
     */
    suspend fun loadCalibratedScale(): Float = modelStore.loadCalibratedScale()

    // ─── Motion recording ─────────────────────────────────────────────────────

    /**
     * Start a new BVH recording session. Clears any previously buffered frames.
     * The record toggle in [RecordPanel] calls this.
     */
    fun startRecording() {
        recordingManager.markRecordingStarted()
        recordingManager.start()
        router.isRecording = true
    }

    /** Gap 7 — Switch the active workflow mode. */
    fun setWorkflowMode(mode: WorkflowMode) {
        uiState.update { it.copy(workflowMode = mode) }
    }

    /** Gap 5 — Update the take label while not recording. */
    fun setTakeLabel(label: String) = recordingManager.setLabel(label)

    /**
     * Stop recording and export the buffered frames as both a BVH file and an
     * animated GLB file (glTF animation track embedded in the asset).
     *
     * Both exports run on [Dispatchers.IO]. [AppUiState.exportedBvhPath] and
     * [AppUiState.exportedGltfPath] are updated when each file is written.
     * Files are written to the app's external "mocap" directory.
     */
    fun stopRecordingAndExport() {
        router.isRecording = false
        val scannedOffsets: Map<Int, com.arhand.util.Vec3>? = scanCoordinator.restJointPositions?.let { rjp ->
            buildMap {
                for (jointIdx in 0 until BoneRetargeter.JOINT_COUNT) {
                    val base = jointIdx * 3
                    if (base + 2 < rjp.size)
                        put(jointIdx, com.arhand.util.Vec3(rjp[base], rjp[base + 1], rjp[base + 2]))
                }
            }
        }
        val outputDir = getApplication<android.app.Application>()
            .getExternalFilesDir("mocap") ?: return
        // Snapshot on the main thread before launching onto Dispatchers.IO — loadedAsset is a
        // plain (non-volatile) var written by a main-thread collector, so reading it directly
        // from the IO-dispatcher coroutine below was a cross-thread visibility gap
        // (ENGINE_ARCHITECTURE.md §5.6). Passing a snapshot removes the cross-thread read
        // entirely rather than just guaranteeing visibility.
        val assetSnapshot = loadedAsset
        viewModelScope.launch(Dispatchers.IO) {
            recordingManager.exportAndAppend(outputDir, scannedOffsets, assetSnapshot)
        }
    }

    /**
     * PERF-2 — Derive optimal [OneEuroFilter] parameters from scan biometric frames.
     *
     * Uses the static-hold frames (minimum motion) to estimate the landmark noise floor,
     * then computes the optimal cutoff frequency and beta coefficient.
     *
     * @return Pair(optimalCutoff, optimalBeta) or null if insufficient data.
     */

    /** Guards against launching the FullBodyFrame merge collector more than once. */
    private var fullBodyCollectorStarted = false

    /**
     * ENGINE_ARCHITECTURE.md §10.1 — was built but never started, and (before this fix)
     * duplicated `SpatialFrameProducer`'s own body retargeting via a second
     * `bodyRetargeter.retarget()` call with independent state. Now reads
     * `producer.latestBodyResult`/`producer.latestBodyLandmarks` — the producer's own
     * already-computed output — instead of recomputing, so there's exactly one retargeting
     * pass regardless of how many collectors want the result. Started from [initCamera] below.
     * `latestFullBodyFrame` still has no consumer of its own (`CompositeGestureClassifier`'s
     * `classify(FullBodyFrame, slot)` overload is never called from the gesture dispatcher,
     * which only uses the simpler `classify(gesture, fe)`); wiring gesture dispatch to use
     * full-body context is a product decision on what that should actually change about
     * gesture behavior, not a technical fix — left for a deliberate follow-up. This collector
     * does, however, fix a real currently-broken side effect: `latestBodyRetargetResult` had
     * no writer at all while this collector was dormant, so `perfMonitor.updateQuality`'s body
     * confidence (read from it) was always 0 regardless of actual tracking quality.
     */
    private fun ensureFullBodyCollector() {
        if (fullBodyCollectorStarted) return
        fullBodyCollectorStarted = true
        viewModelScope.launch {
            handPipeline.processed.collect { hands ->
                if (trackingManager.state.value.bodyEnabled || trackingManager.state.value.faceEnabled) {
                    val leftSlot  = hands.firstOrNull { it.slotIndex == 0 }?.landmarks
                    val rightSlot = hands.firstOrNull { it.slotIndex == 1 }?.landmarks
                    latestFullBodyFrame.value = FullBodyFrame(
                        pose        = if (trackingManager.state.value.bodyEnabled) producer.latestBodyLandmarks.value else null,
                        face        = if (trackingManager.state.value.faceEnabled) facePipeline.processed.value else null,
                        leftHand    = leftSlot,
                        rightHand   = rightSlot,
                        // FACE-3 — carry derived expressions so CompositeGestureClassifier
                        // can use the classify(FullBodyFrame) overload correctly
                        expressions = facePipeline.expressions.value
                            .takeIf { trackingManager.state.value.faceEnabled }
                    )

                    if (trackingManager.state.value.bodyEnabled) {
                        latestBodyRetargetResult.value = producer.latestBodyResult.value
                    }
                }
            }
        }
    }

    /**
     * Enable or disable body pose tracking.
     *
     * On first enable, initialises [BodyPipeline] using [pose_landmarker_lite.task]
     * from assets. Subsequent toggles reuse the same landmarker instance.
     * Body landmarks are merged into [latestFullBodyFrame] every hand-pipeline frame.
     */
    fun enableBodyTracking(enabled: Boolean) {
        trackingManager.setBodyTracking(enabled)
        producer.enableBody(enabled)
        router.bodyEnabled = enabled
    }

    fun enableFaceTracking(enabled: Boolean) {
        trackingManager.setFaceTracking(enabled)
        producer.enableFace(enabled)
        router.faceEnabled = enabled
    }

    // ─── OSC streaming ────────────────────────────────────────────────────────

    /**
     * Start broadcasting live joint rotations via OSC UDP.
     *
     * @param host   Target IP or hostname. Defaults to LAN broadcast.
     * @param port   Target UDP port. Defaults to the schema-recommended port.
     * @param schema OSC address schema. Defaults to the current [AppUiState.oscSchema].
     */
    fun startOscStreaming(
        host:   String    = oscManager.state.value.host,
        port:   Int       = oscManager.state.value.port,
        schema: OscSchema = oscManager.state.value.schema
    ) = oscManager.start(host, port, schema)

    fun stopOscStreaming() = oscManager.stop()

    fun setOscSchema(schema: OscSchema) = oscManager.setSchema(schema)

    /**
     * Built-in gesture → app action mapping.
     *
     * Edge-triggered — called once when the gesture transitions from null/other
     * to this gesture. Never called repeatedly while the gesture is held.
     *
     * Default mapping (mirrors G4 spec):
     *   FIST      → start scan  (if no scan active and no model stored yet)
     *   PEACE     → switch camera
     *   ROCK      → toggle torch
     *   CALL      → toggle OSC stream (send mode)
     *
     * The remaining gestures (OPEN_PALM, THUMBS_UP, POINT_UP, OK) are reserved
     * for user-configurable macro triggers in a future UX pass.
     */
    private fun dispatchGestureShortcut(gesture: Gesture) {

        // When face tracking is off, fe is null and modifier is always NONE —
        // existing behaviour is fully preserved.
        val fe = facePipeline.expressions.value.takeIf { trackingManager.state.value.faceEnabled }
        val composite = CompositeGestureClassifier.classify(gesture, fe)

        when (composite.base) {
            Gesture.FIST -> {
                // Only trigger scan start if no scan is running and no model stored yet
                if (!uiState.value.scanActive && !(assetManager.state.value.name != null)) startScan()
            }
            Gesture.PEACE -> switchCamera()
            Gesture.ROCK  -> toggleTorch()
            Gesture.CALL  -> {
                if (oscManager.state.value.mode == OscMode.SEND) {
                    if (oscManager.state.value.isStreaming) stopOscStreaming()
                    else startOscStreaming()
                }
            }
            // Reserved: OPEN_PALM, THUMBS_UP, POINT_UP, OK — no-op for now.
            // composite.modifier (BLINK / JAW / BROW) available for future extended bindings.
            else -> {}
        }
    }

    // ─── G3: OSC mode switching & receiver control ─────────────────────────────

    /**
     * Switch between SEND and RECEIVE modes.
     *
     * - SEND → RECEIVE: stops the streamer (if active), starts the receiver.
     * - RECEIVE → SEND: stops the receiver, streamer can be started independently.
     *
     * The camera pipeline keeps running in both modes; in RECEIVE mode the renderer
     * will prefer the remote RetargetResult over the locally-computed one.
     */
    fun setOscMode(mode: OscMode) {
        when (mode) {
            OscMode.SEND -> {
                stopOscReceiving()
                oscManager.setMode(OscMode.SEND)
            }
            OscMode.RECEIVE -> {
                stopOscStreaming()
                startOscReceiving()
            }
        }
    }

    /** G3 — Job for the OSC receiver collector. Cancelled and replaced on each startOscReceiving() call. */
    private var oscReceiverJob: kotlinx.coroutines.Job? = null

    /**
     * Start the OSC receiver on [port] and wire its output to the renderer.
     *
     * @param port UDP port to listen on. Defaults to [OscReceiver.DEFAULT_PORT] (9000).
     */
    fun startOscReceiving(port: Int = OscReceiver.DEFAULT_PORT) {
        if (oscReceiver.isReceiving) oscReceiver.stop()

        oscReceiver.start(port)
        if (!oscReceiver.isReceiving) return  // port bind failed

        oscManager.setMode(OscMode.RECEIVE)
        oscManager.setReceivePort(port)

        oscReceiverJob?.cancel()
        oscReceiverJob = viewModelScope.launch {
            oscReceiver.frames.collect { result ->
                renderer.latestRetargetResult = result
            }
        }
    }

    fun stopOscReceiving() {
        oscReceiver.stop()
        oscReceiverJob?.cancel()
        oscReceiverJob = null
        oscManager.setMode(OscMode.SEND)
    }

    override fun onCleared() {
        router.stop()
        producer.close()
        oscManager.stop()
        oscReceiver.stop()
        oscReceiverJob?.cancel()
        spatialLayer.close()
        freeformScanner.reset()
        oscDiscovery.stop()
        super.onCleared()
    }
}
