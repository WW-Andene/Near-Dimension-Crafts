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
import com.arhand.depth.DepthCarver
import com.arhand.depth.HandSegmentationMask
import com.arhand.depth.NeuralImplicitCarver
import com.arhand.depth.ManoShapeFitter
import com.arhand.depth.fusion.FusedDepthSource
import com.arhand.export.GLBExporter
import com.arhand.render.ARRenderer
import com.arhand.render.RenderMode
import com.arhand.scanner.CLAHEAnalyzer
import com.arhand.scanner.PersonalModelStore
import com.arhand.scanner.Scanner
import com.arhand.scanner.PhotometricStereoCapture
import com.arhand.mocap.AssetLoader
import com.arhand.mocap.BiomechanicalConstraintFilter
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
import com.arhand.mocap.VrmBlendShapeParser
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
    val isFrontCamera: Boolean       = true,
    val scanActive:    Boolean       = false,
    val showSplash:    Boolean       = true,
    val showOnboarding: Boolean      = false,
    val liveMeshActive: Boolean      = false,
    val workflowMode:  WorkflowMode  = WorkflowMode.IDLE
)

// DataStore delegates — must be top-level per Kotlin DataStore contract.
// One singleton instance per application process regardless of ViewModel lifecycle.
private val Context.onboardingDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "handy_onboarding")

private val Context.oefDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "handy_oef")

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
    val bodyRetargeter = BodyRetargeter()
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
        perfMonitor    = perfMonitor
    )

    private val tsdfVolume = com.arhand.depth.TSDFVolume()
    // ── SpatialFrameRouter — distributes frames to all consumers ─────────────
    val router = com.arhand.feature.spatial.SpatialFrameRouter(
        scope           = viewModelScope,
        oscStreamer     = oscStreamer,
        motionRecorder  = motionRecorder,
        renderer        = renderer,
        scanner         = scanner,
        freeformScanner = freeformScanner,
        tsdfVolume      = tsdfVolume
    )

    // ── Scan accumulation (delegated to router) ───────────────────────────────
    private val capturedFrames      get() = router.capturedFrames
    private val capturedDepthFrames get() = router.capturedDepthFrames
    private val capturedScanBitmaps get() = router.capturedBitmaps
    private val biometricFrames     get() = router.biometricFrames

    /** R1 — Latest camera bitmap. Written each frame by producer. */
    private val latestBitmap: Bitmap? get() = producer.latestBitmap

    private var torchOnAtScanStart: Boolean = true

    val depthMeshPositions: MutableStateFlow<FloatArray> = MutableStateFlow(FloatArray(0))

    /**
     * Hot-path StateFlow: currently recognised gesture on the primary hand.
     * Updated at inference rate (up to 30fps) — kept out of [AppUiState] so
     * gesture changes do not trigger full-tree Compose recomposition.
     * Screens that need gestures collect this directly.
     */
    val activeGesture: MutableStateFlow<Gesture?> = MutableStateFlow(null)

    /**
     * Hot-path StateFlow: ARCore depth cloud confidence (0–1).
     * Updated each depth frame — kept out of [AppUiState] for the same reason.
     */
    val depthConfidence: MutableStateFlow<Float> = MutableStateFlow(0f)

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

    /**
     * Rest-pose joint positions captured at the moment the scan completes.
     * Flat float array: JOINT_COUNT × 3 floats (world-space xyz per joint).
     * Null until the first scan completes.
     */
    private var restJointPositions: FloatArray? = null

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

    /** Retargeter instance — rebuilt whenever [loadedAsset] changes. */
    private var boneRetargeter: BoneRetargeter = BoneRetargeter(AssetLoader.DEFAULT_PUPPET.bindPose)

    /** Gap 2 — Secondary hand (slot 1 = left hand) retargeter and smoother. */
    private var boneRetargeterSecondary: BoneRetargeter = BoneRetargeter(AssetLoader.DEFAULT_PUPPET.bindPose)
    private val quaternionEmaFilterSecondary = QuaternionEmaFilter()

    /** Most recent retarget result — updated every tracking frame. Null until first frame. */
    val latestRetargetResult: MutableStateFlow<RetargetResult?> = MutableStateFlow(null)

    /** IMP-1 — Speed-adaptive quaternion EMA filter applied after retargeting. */
    private val quaternionEmaFilter = QuaternionEmaFilter()

    /**
     * HAND-6 — Shared [NeuralImplicitCarver] instance for incremental training during scan.
     *
     * Created at scan start; trained incrementally after each pose via [poseCaptureDone].
     * When [processScan] runs, it calls [NeuralImplicitCarver.trainIncremental] with the
     * remaining poses on top of the already-trained weights — total compute budget is the
     * same as full training, but partial scans produce progressively better meshes.
     */
    private var incrementalCarver: com.arhand.depth.NeuralImplicitCarver? = null

    /** Background job running incremental training. Cancelled if scan is cancelled. */
    private var incrementalTrainJob: kotlinx.coroutines.Job? = null

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
     * Consumed by OSC, MotionRecorder, and future GltfAnimationExporter.
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

        // Camera frames → renderer background texture
        viewModelScope.launch {
            producer.frameProvider.frames.collect { bitmap ->
                renderer.submitCameraFrame(bitmap)
            }
        }

        // Producer → SpatialFrame → router → all consumers
        router.onRetargetResult = { result ->
            latestRetargetResult.value = result
        }
        router.onFullFrame = { full ->
            latestFullBodyRetargetResult.value = full
        }
        router.start(producer.frames)

        // Mirror spatial layer depth confidence to UI
        viewModelScope.launch {
            producer.spatialLayer.state.collect { s ->
                depthConfidence.value = s.depthConfidence
            }
        }

        // Wire tracking results → producer.assembleFrame() → SpatialFrame → router
        // All retargeting, SL z-correction, body alignment, morph weights, OSC, BVH
        // now happen inside producer/router. AppViewModel is a coordinator only.
        viewModelScope.launch {
            handPipeline.processed.collect { hands ->
                producer.assembleFrame(
                    hands             = hands,
                    aspect            = currentAspect.value,
                    constraintEnabled = trackingManager.state.value.constraintEnabled
                )
                renderer.handsData = hands.map { it.landmarks }
                renderer.mirrorX = uiState.value.isFrontCamera

                // Retarget the primary hand onto the loaded asset each frame
                val primary = hands.firstOrNull()
                primary?.let { hand ->
                    // BODY-3 — Provide body wrist hint when body tracking is active
                    val bodyResult   = latestBodyRetargetResult.value
                    val isMirror     = uiState.value.isFrontCamera
                    val bodyWristPos = if (trackingManager.state.value.bodyEnabled && bodyResult != null)
                        if (hand.slotIndex == 1) bodyResult.wristLeft else bodyResult.wristRight
                    else null
                    val bodyWristVis = bodyResult?.confidence ?: 0f

                    val result = boneRetargeter.retarget(
                        lms           = hand.landmarks,
                        aspect        = currentAspect.value,
                        mirrorX       = isMirror,
                        bodyWristHint = bodyWristPos,
                        bodyWristVis  = bodyWristVis
                    )
                    if (result != null) {

                        val smoothedResult = quaternionEmaFilter.apply(result)

                        // HAND-1 — Apply biomechanical joint constraints when enabled.
                        // Projects rotations into anatomically feasible ROM ranges.
                        // Off by default; enabled via Settings toggle.
                        val constrainedResult = if (trackingManager.state.value.constraintEnabled) {
                            BiomechanicalConstraintFilter.apply(smoothedResult)
                        } else {
                            smoothedResult
                        }

                        // Gap 6 — Unified world coordinates.
                        // When body tracking is active, anchor the hand wrist position to the
                        // body-space wrist landmark so hand and body skeleton share one coordinate
                        // system. Without this, the hand floats in camera-normalised space
                        // independently of the body's metric world space.
                        val worldAlignedResult = if (trackingManager.state.value.bodyEnabled &&
                            bodyResult != null && bodyResult.confidence > 0.5f) {
                            val bodyWrist = if (hand.slotIndex == 1)
                                bodyResult.wristLeft else bodyResult.wristRight
                            if (bodyWrist != null) {
                                // Replace wrist position with body-space metric position
                                val alignedTransform = constrainedResult.wristTransform.copy(
                                    position = bodyWrist
                                )
                                constrainedResult.copy(wristTransform = alignedTransform)
                            } else constrainedResult
                        } else constrainedResult

                        latestRetargetResult.value    = worldAlignedResult
                        renderer.latestRetargetResult = worldAlignedResult

                        // Gap 4 — Drive loaded character body skeleton from body retarget result.
                        if (trackingManager.state.value.bodyEnabled) {
                            renderer.updateBodyPose(latestBodyRetargetResult.value, loadedAsset)
                        }

                        // ARCH-4 — Update per-pipeline quality metrics for HUD display
                        val bodyConf = latestBodyRetargetResult.value?.confidence ?: 0f
                        val faceConf = if (trackingManager.state.value.faceEnabled) 1f else 0f
                        perfMonitor.updateQuality(
                            hand = worldAlignedResult.wristTransform.let { 1f },  // hand always tracked when result is non-null
                            body = bodyConf,
                            face = faceConf
                        )

                        // ARCH-1 — Assemble unified full-body frame and distribute to all consumers.
                        val faceExpr   = facePipeline.expressions.value
                            .takeIf { trackingManager.state.value.faceEnabled }
                    // Gap 2 — Retarget secondary hand (slot 1 = left hand)
                    val secondary = hands.firstOrNull { it.slotIndex == 1 }
                    val secondaryResult: RetargetResult? = secondary?.let { secHand ->
                        val secBodyWrist = if (trackingManager.state.value.bodyEnabled && bodyResult != null)
                            bodyResult.wristLeft else null
                        val secRaw = boneRetargeterSecondary.retarget(
                            lms           = secHand.landmarks,
                            aspect        = currentAspect.value,
                            mirrorX       = uiState.value.isFrontCamera,
                            bodyWristHint = secBodyWrist,
                            bodyWristVis  = bodyResult?.confidence ?: 0f
                        )
                        secRaw?.let { r ->
                            val smoothed = quaternionEmaFilterSecondary.apply(r)
                            if (trackingManager.state.value.constraintEnabled)
                                BiomechanicalConstraintFilter.apply(smoothed)
                            else smoothed
                        }
                    }

                        val fullFrame = FullBodyRetargetResult(
                            timestamp       = System.currentTimeMillis(),
                            handPrimary     = worldAlignedResult,
                            handSecondary   = secondaryResult,
                            body            = bodyResult.takeIf { trackingManager.state.value.bodyEnabled },
                            face            = faceExpr,
                            frameConfidence = worldAlignedResult.wristTransform.let { 1f },
                            // Spatial layer — always-on depth sensing fields
                            metricGrounded  = spatialLayer.state.value.isGrounded,
                            cameraWorldX    = spatialLayer.state.value.cameraWorldX,
                            cameraWorldY    = spatialLayer.state.value.cameraWorldY,
                            cameraWorldZ    = spatialLayer.state.value.cameraWorldZ,
                            depthConfidence = spatialLayer.state.value.depthConfidence
                        )
                        latestFullBodyRetargetResult.value = fullFrame

                        // FACE-1 — Apply VRM blend shapes driven by face expressions.
                        // Resolves FaceExpressions → morph target weights and forwards to renderer.
                        // applyMorphWeights() is currently a stub; weights will be applied to GPU
                        // morph buffers once the shader extension is added.
                        if (trackingManager.state.value.faceEnabled && faceExpr != null) {
                            val blendMap = loadedAsset.blendShapeMap
                            if (blendMap != null) {
                                val morphApps = VrmBlendShapeParser.resolve(faceExpr, blendMap)
                                renderer.applyMorphWeights(morphApps)
                            }
                        }

                        // Distribute via unified paths — existing individual paths kept for
                        // backward compatibility until OSC-1 / ARCH-2 complete the migration.
                        motionRecorder.pushFrame(fullFrame)
                        oscStreamer.sendFrame(fullFrame)

                        if (oscStreamer.sendVelocity) {
                            oscStreamer.sendVelocityFrame(quaternionEmaFilter.lastAngularVelocity)
                        }

                        if (uiState.value.liveMeshActive && liveMeshDeformer.hasMesh()) {
                            val deformed = liveMeshDeformer.deform(worldAlignedResult)
                            renderer.liveMeshPositions = deformed
                        }
                    }
                } ?: run {

                    // prev[] entries don't lerp from the old pose when the hand reappears.
                    // HandPipeline already resets its own OEF/OcclusionEngine/segConstraint
                    // on the same event; this keeps AppViewModel's layer in sync.
                    quaternionEmaFilter.reset()
                }

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
                        bodyLandmarks   = bodyPipeline.processed.value.takeIf { trackingManager.state.value.bodyEnabled }
                    )

                    // Capture frames for depth carving — B1: store quality score per frame
                    // Only accumulate during CAPTURING; PREFLIGHT/COUNTDOWN frames have quality=0f
                    // and would dilute the SDF carver's top-75% quality filter.
                    if (uiState.value.scanActive &&
                        scanner.status.value.state == Scanner.ScanState.CAPTURING) {
                        primary?.landmarks?.let { lms ->
                            if (lms.size == 21) {
                                val quality = scanner.status.value.quality
                                capturedFrames.add(Pair(DepthCarver.landmarksToWorld(lms, aspect, mirrorX = uiState.value.isFrontCamera), quality))
                                // Accumulate for biometrics — sample every 3rd frame to reduce
                                // redundancy while still getting coverage across the pose hold
                                if (capturedFrames.size % 3 == 0) {
                                    biometricFrames.add(Pair(lms, quality))
                                }

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
                                            val hull = HandSegmentationMask.buildHull(
                                                lms, aspect, mirrorX = uiState.value.isFrontCamera
                                            )
                                            val masked = HandSegmentationMask.filterPointCloud(cloud, hull)
                                            capturedDepthFrames.add(masked)
                                            // HAND-8 — Integrate into TSDF volume for surface-aware reconstruction
                                            val depthConf = (store.pointCount / 10000f).coerceIn(0.3f, 1f)
                                            tsdfVolume.integrate(masked, depthConf)
                                            depthConfidence.value = (store.pointCount / 10000f).coerceIn(0f, 1f)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // LIMIT-2 — Feed freeform scanner when it is the active scan mode
                    if (scanState.value.freeformActive) {
                        val worldFrames = primary?.landmarks?.let { lms ->
                            if (lms.size == 21)
                                com.arhand.depth.DepthCarver.landmarksToWorld(
                                    lms, aspect, mirrorX = uiState.value.isFrontCamera
                                )
                            else null
                        }
                        freeformScanner.update(
                            lms           = primary?.landmarks,
                            claheContrast = clahe.lastContrastScore,
                            bitmap        = latestBitmap,
                            worldFrames   = worldFrames,
                            aspect        = aspect,
                            mirrorX       = uiState.value.isFrontCamera,
                            nowMs         = System.currentTimeMillis()
                        )

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
                                            val hull = HandSegmentationMask.buildHull(
                                                lms, aspect, mirrorX = uiState.value.isFrontCamera
                                            )
                                            val masked = HandSegmentationMask.filterPointCloud(cloud, hull)
                                            capturedDepthFrames.add(masked)
                                            val depthConf = (store.pointCount / 10000f).coerceIn(0.3f, 1f)
                                            tsdfVolume.integrate(masked, depthConf)
                                            depthConfidence.value = (store.pointCount / 10000f).coerceIn(0f, 1f)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Watch scanner state → trigger processing when done
        viewModelScope.launch {
            scanner.status.collect { status ->
                when (status.state) {
                    Scanner.ScanState.PROCESSING -> processScan()
                    else -> {}
                }
            }
        }

        // LIMIT-2 — Watch freeform scanner state → trigger processing when complete
        viewModelScope.launch {
            freeformScanner.status.collect { fs ->
                scanState.value = scanState.value.copy(freeformStatus = fs)
                when (fs.state) {
                    com.arhand.scanner.FreeformScanner.State.COMPLETE -> processFreeformScan()
                    com.arhand.scanner.FreeformScanner.State.FAILED   -> {
                        uiState.update { it.copy(scanActive = false) }
                        scanState.value = scanState.value.copy(freeformActive = false)
                    }
                    else -> {}
                }
            }
        }

        viewModelScope.launch {
            val history = modelStore.loadHistory()
            scanState.value = scanState.value.copy(
                hasStoredModel   = modelStore.hasModel(),
                biometricHistory = history
            )
        }

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

    fun initCamera(owner: LifecycleOwner) {
        val cc = CameraController(getApplication(), producer.frameProvider)
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
            val restJoints = restJointPositions
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
     * HAND-1 — Toggle [BiomechanicalConstraintFilter] on/off.
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
        // SpatialLayer SfM/Photo need the rear camera — pause TSDF accumulation but
        // spatialLayer itself continues (ARCore also requires rear camera so it will
        // drop to SfM-only on front camera, which is the correct degradation).
        if (scanState.value.depthMode) {
            scanState.value = scanState.value.copy(depthMode = false)
            depthConfidence.value = 0f
            capturedDepthFrames.clear()
        }
        // SL calibration is camera-specific — reset on switch
        producer.resetSLCalibration()
        cameraController.switchCamera()
        val nowFront = cameraController.isFrontFacing()
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
     * Toggle TSDF accumulation for scan reconstruction.
     *
     * [SpatialLayer] now runs continuously — this toggle no longer starts/stops depth
     * sensing. It controls whether the scan frame loop accumulates depth frames into the
     * TSDF volume (for mesh export), and whether the depth cloud is shown in the renderer.
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
        } else {
            scanState.value = scanState.value.copy(depthMode = false)
            capturedDepthFrames.clear()
        }
    }

    fun startScan() {
        capturedFrames.clear()
        biometricFrames.clear()
        capturedDepthFrames.clear()
        capturedScanBitmaps.clear()   // R1
        tsdfVolume.reset()

        torchOnAtScanStart = uiState.value.torchOn
        photoStereoCapture.reset()
        if (scanState.value.photoStereoEnabled) photoStereoCapture.start()
        photoStereoFrameCount.value = 0
        photoStereoComplete.value   = false
        uiState.update { it.copy(scanActive = true) }

        // HAND-6 — Reset the incremental carver and start collecting pose-done signals.
        // Training begins after pose index 2 (the 3rd completed pose) — enough data
        // for a coarse network, and early enough to be useful for partial scans.
        if (scanState.value.neuralReconEnabled) {
            val carver = com.arhand.depth.NeuralImplicitCarver()
            incrementalCarver = carver
            incrementalTrainJob?.cancel()
            incrementalTrainJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                scanner.poseCaptureDone.collect { poseIdx ->
                    // R1 — Snapshot the current camera frame for texture baking.
                    // Bitmap.copy() is thread-safe on read; the copy is immutable.
                    latestBitmap?.let { bmp ->
                        val copy = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
                        synchronized(capturedScanBitmaps) { capturedScanBitmaps.add(copy) }
                    }

                    if (poseIdx < 2) return@collect   // wait for at least 3 poses
                    // Snapshot the frames captured so far for this incremental pass
                    val frameSnapshot = synchronized(capturedFrames) { capturedFrames.toList() }
                    val normalMap     = photoStereoNormalMap.value
                    carver.trainIncremental(frameSnapshot, normalMap = normalMap)
                }
            }
        }

        scanner.start()
    }

    fun cancelScan() {
        incrementalTrainJob?.cancel()
        incrementalTrainJob = null
        incrementalCarver   = null
        scanner.reset()
        capturedFrames.clear()
        biometricFrames.clear()
        capturedDepthFrames.clear()
        capturedScanBitmaps.clear()   // R1
        photoStereoCapture.stop()

        if (scanState.value.photoStereoEnabled && !uiState.value.isFrontCamera) {
            cameraController.setTorch(torchOnAtScanStart)
        }
        uiState.update { it.copy(scanActive = false) }
    }

    // ─── LIMIT-2: Freeform (continuous) scan ─────────────────────────────────

    /**
     * Start a continuous freeform scan.
     *
     * The user rotates their hand freely over 8–20 seconds instead of holding
     * specific poses. [FreeformScanner] gates frames on quality, motion velocity,
     * and viewpoint novelty, then auto-completes when coverage and frame count
     * targets are met. [processFreeformScan] is triggered automatically on completion.
     *
     * Shares the same depth integration path (TSDF + ARCore) as the posed scan.
     */
    fun startFreeformScan() {
        capturedFrames.clear()
        biometricFrames.clear()
        capturedDepthFrames.clear()
        capturedScanBitmaps.clear()
        tsdfVolume.reset()

        torchOnAtScanStart = uiState.value.torchOn
        photoStereoCapture.reset()
        if (scanState.value.photoStereoEnabled) photoStereoCapture.start()
        photoStereoFrameCount.value = 0
        photoStereoComplete.value   = false

        // Start incremental neural recon — trains every 30 accepted freeform frames
        if (scanState.value.neuralReconEnabled) {
            val carver = com.arhand.depth.NeuralImplicitCarver()
            incrementalCarver = carver
            incrementalTrainJob?.cancel()
            incrementalTrainJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                var lastTrainedCount = 0
                freeformScanner.status.collect { fs ->
                    if (fs.state != com.arhand.scanner.FreeformScanner.State.ACTIVE) return@collect
                    val count = fs.frameCount
                    if (count - lastTrainedCount >= 30 && count > 0) {
                        lastTrainedCount = count
                        val snapshot = synchronized(freeformScanner.capturedFrames) {
                            freeformScanner.capturedFrames.toList()
                        }
                        carver.trainIncremental(snapshot, normalMap = photoStereoNormalMap.value)
                    }
                }
            }
        }

        freeformScanner.start()
        scanState.value = scanState.value.copy(freeformActive = true)
        router.isFreeformActive = true
        router.isScanActive     = true
        uiState.update { it.copy(scanActive = true) }
    }

    /** User taps "Finish" during freeform scan — validates coverage then triggers processing. */
    fun finishFreeformScan() {
        freeformScanner.finish()
        // Completion / failure handled by the freeformScanner.status watcher in init
    }

    fun cancelFreeformScan() {
        incrementalTrainJob?.cancel()
        incrementalTrainJob = null
        incrementalCarver   = null
        freeformScanner.reset()
        capturedFrames.clear()
        biometricFrames.clear()
        capturedDepthFrames.clear()
        capturedScanBitmaps.clear()
        photoStereoCapture.stop()

        if (scanState.value.photoStereoEnabled && !uiState.value.isFrontCamera) {
            cameraController.setTorch(torchOnAtScanStart)
        }
        uiState.update { it.copy(scanActive = false) }
        scanState.value = scanState.value.copy(freeformActive = false, freeformStatus = null)
        router.isFreeformActive = false
        router.isScanActive     = false
    }

    /**
     * Process a completed freeform scan.
     *
     * Builds [ScanInput] from [FreeformScanner]'s accumulated frames and delegates
     * to [ScanPipeline.process] — identical pipeline to the posed scan. The TSDF
     * volume and depth frames populated during the freeform capture feed the same
     * ARCore-grounded surface reconstruction path.
     */
    private fun processFreeformScan() {
        // Finalise photometric stereo if it ran
        if (scanState.value.photoStereoEnabled && photoStereoCapture.frameCount > 0) {
            viewModelScope.launch(Dispatchers.IO) {
                val normalMap = photoStereoCapture.computeNormalMap()
                if (!normalMap.isEmpty) photoStereoNormalMap.value = normalMap
                photoStereoCapture.stop()
                cameraController.setTorch(torchOnAtScanStart)
            }
        }

        val input = com.arhand.feature.scan.ScanInput(
            cloudPoints        = freeformScanner.capturedFrames.flatMap { it.first },
            capturedFrames     = freeformScanner.capturedFrames.toList(),
            biometricFrames    = freeformScanner.biometricFrames.toList(),
            capturedDepth      = capturedDepthFrames.toList(),
            capturedBitmaps    = synchronized(freeformScanner.capturedBitmaps) {
                                     freeformScanner.capturedBitmaps.toList() },
            normalMap          = photoStereoNormalMap.value,
            tsdfVolume         = tsdfVolume,
            incrementalCarver  = incrementalCarver,
            neuralReconEnabled = scanState.value.neuralReconEnabled,
            smplEnabled        = com.arhand.BuildConfig.FEATURE_SMPL_BODY,
            aspect             = currentAspect.value,
            isFrontCamera      = uiState.value.isFrontCamera,
            modelStore         = modelStore
        )

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = com.arhand.feature.scan.ScanPipeline()
                    .process(input, getApplication())

                renderer.depthMeshPositions = result.meshPositions
                depthMeshPositions.value    = result.meshPositions

                result.restJointPositions?.let { rjp ->
                    restJointPositions = rjp
                    liveMeshDeformer.buildSkinWeights(result.meshPositions, rjp)
                }

                scanState.value = scanState.value.copy(
                    hasStoredModel         = result.glbFile != null,
                    exportedGlbPath        = result.glbFile?.absolutePath,
                    handBiometrics         = result.biometrics,
                    biometricHistory       = result.biometricHistory,
                    jointRomData           = null,
                    neuralReconDiagnostics = result.neuralDiagnostics,
                    freeformActive         = false
                )

                result.calibratedOefCutoff?.let { cutoff ->
                    result.calibratedOefBeta?.let { beta ->
                        handPipeline.setOefParams(cutoff, beta)
                        getApplication<Application>().oefDataStore.edit { prefs ->
                            prefs[KEY_OEF_CUTOFF] = cutoff
                            prefs[KEY_OEF_BETA]   = beta
                        }
                    }
                }

                incrementalTrainJob?.cancel()
                incrementalTrainJob = null
                incrementalCarver   = null

                result.glbFile?.let { f ->
                    val scoresJson = "[]"   // freeform has no pose scores
                    modelStore.saveModel(f, input.cloudPoints.size, scoresJson, result.biometrics)
                }

                freeformScanner.reset()
                uiState.update { it.copy(scanActive = false) }
                router.isFreeformActive = false
                router.isScanActive     = false

            } catch (e: Exception) {
                android.util.Log.e("AppViewModel", "processFreeformScan failed", e)
                freeformScanner.reset()
                photoStereoCapture.stop()
                if (scanState.value.photoStereoEnabled && !uiState.value.isFrontCamera) {
                    cameraController.setTorch(torchOnAtScanStart)
                }
                scanState.value = scanState.value.copy(freeformActive = false)
                uiState.update { it.copy(scanActive = false) }
                router.isFreeformActive = false
                router.isScanActive     = false
            }
        }
    }
    /**
     * Processes a completed scan using [ScanPipeline].
     *
     * Snapshots all mutable buffers synchronously on the calling thread, then
     * delegates the full computation to [ScanPipeline.process] on [Dispatchers.Default].
     * The resulting [ScanResult] is distributed to the renderer, scanState, and
     * LiveMeshDeformer — no processing logic lives here.
     */
    private fun processScan() {
        val input = com.arhand.feature.scan.ScanInput(
            cloudPoints        = scanner.cloudPoints.toList(),
            capturedFrames     = capturedFrames.toList(),
            biometricFrames    = biometricFrames.toList(),
            capturedDepth      = capturedDepthFrames.toList(),
            capturedBitmaps    = synchronized(capturedScanBitmaps) { capturedScanBitmaps.toList() },
            normalMap          = photoStereoNormalMap.value,
            tsdfVolume         = tsdfVolume,
            incrementalCarver  = incrementalCarver,
            neuralReconEnabled = scanState.value.neuralReconEnabled,
            smplEnabled        = com.arhand.BuildConfig.FEATURE_SMPL_BODY,
            aspect             = currentAspect.value,
            isFrontCamera      = uiState.value.isFrontCamera,
            modelStore         = modelStore
        )

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = com.arhand.feature.scan.ScanPipeline()
                    .process(input, getApplication())

                // Distribute ScanResult to all consumers
                renderer.depthMeshPositions  = result.meshPositions
                depthMeshPositions.value     = result.meshPositions

                result.restJointPositions?.let { rjp ->
                    restJointPositions = rjp
                    liveMeshDeformer.buildSkinWeights(result.meshPositions, rjp)
                }

                scanState.value = scanState.value.copy(
                    hasStoredModel         = result.glbFile != null,
                    exportedGlbPath        = result.glbFile?.absolutePath,
                    handBiometrics         = result.biometrics,
                    biometricHistory       = result.biometricHistory,
                    jointRomData           = scanner.romData,
                    neuralReconDiagnostics = result.neuralDiagnostics
                )

                result.calibratedOefCutoff?.let { cutoff ->
                    result.calibratedOefBeta?.let { beta ->
                        handPipeline.setOefParams(cutoff, beta)
                        getApplication<Application>().oefDataStore.edit { prefs ->
                            prefs[KEY_OEF_CUTOFF] = cutoff
                            prefs[KEY_OEF_BETA]   = beta
                        }
                    }
                }

                // Clean up incremental carver state — pipeline has consumed it
                incrementalTrainJob?.cancel()
                incrementalTrainJob = null
                incrementalCarver   = null

                // Finalise photometric stereo capture if it ran
                if (scanState.value.photoStereoEnabled && photoStereoCapture.frameCount > 0) {
                    val normalMap = photoStereoCapture.computeNormalMap()
                    if (!normalMap.isEmpty) photoStereoNormalMap.value = normalMap
                    photoStereoCapture.stop()
                    cameraController.setTorch(torchOnAtScanStart)
                }

                result.glbFile?.let { f ->
                    val scoresJson = scanner.status.value.poseScores
                        .joinToString(",", "[", "]") { "%.3f".format(it) }
                    modelStore.saveModel(f, input.cloudPoints.size, scoresJson, result.biometrics)
                }

                scanner.markDone()
                uiState.update { it.copy(scanActive = false) }

            } catch (e: Exception) {
                scanner.markFailed(e.message ?: "Processing error")
                photoStereoCapture.stop()
                if (scanState.value.photoStereoEnabled && !uiState.value.isFrontCamera) {
                    cameraController.setTorch(torchOnAtScanStart)
                }
                uiState.update { it.copy(scanActive = false) }
            }
        }
    }

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

    fun shareGlb() {
        val path = scanState.value.exportedGlbPath ?: return
        // Share intent launched from UI layer
    }

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
     * The REC button in [ControlPanel] calls this.
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
        val scannedOffsets: Map<Int, com.arhand.util.Vec3>? = restJointPositions?.let { rjp ->
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
        viewModelScope.launch(Dispatchers.IO) {
            recordingManager.exportAndAppend(outputDir, scannedOffsets, loadedAsset)
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

    private fun ensureFullBodyCollector() {
        if (fullBodyCollectorStarted) return
        fullBodyCollectorStarted = true
        viewModelScope.launch {
            handPipeline.processed.collect { hands ->
                if (trackingManager.state.value.bodyEnabled || trackingManager.state.value.faceEnabled) {
                    val leftSlot  = hands.firstOrNull { it.slotIndex == 0 }?.landmarks
                    val rightSlot = hands.firstOrNull { it.slotIndex == 1 }?.landmarks
                    latestFullBodyFrame.value = FullBodyFrame(
                        pose        = if (trackingManager.state.value.bodyEnabled) bodyPipeline.processed.value else null,
                        face        = if (trackingManager.state.value.faceEnabled) facePipeline.processed.value else null,
                        leftHand    = leftSlot,
                        rightHand   = rightSlot,
                        // FACE-3 — carry derived expressions so CompositeGestureClassifier
                        // can use the classify(FullBodyFrame) overload correctly
                        expressions = facePipeline.expressions.value
                            .takeIf { trackingManager.state.value.faceEnabled }
                    )

                    // BODY-1 — Retarget body landmarks to joint rotations each frame.
                    // Only runs when body tracking is enabled and landmarks are available.
                    if (trackingManager.state.value.bodyEnabled) {
                        val poseLms = bodyPipeline.processed.value
                        if (poseLms != null) {
                            val bodyResult = bodyRetargeter.retarget(poseLms)
                            latestBodyRetargetResult.value = bodyResult
                        }
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
