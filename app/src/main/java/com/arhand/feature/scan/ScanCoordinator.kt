package com.arhand.feature.scan

import android.app.Application
import android.graphics.Bitmap
import com.arhand.camera.CameraController
import com.arhand.depth.NeuralImplicitCarver
import com.arhand.depth.SpatialLayer
import com.arhand.depth.TSDFVolume
import com.arhand.feature.spatial.SpatialFrameRouter
import com.arhand.render.ARRenderer
import com.arhand.render.LiveMeshDeformer
import com.arhand.scanner.FreeformScanner
import com.arhand.scanner.PersonalModelStore
import com.arhand.scanner.PhotometricNormalMap
import com.arhand.scanner.PhotometricStereoCapture
import com.arhand.scanner.Scanner
import com.arhand.tracking.HandPipeline
import com.arhand.ui.AppUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * REDESIGN_PLAN.md Phase 8, item 5 — owns posed- and freeform-scan lifecycle orchestration
 * (start/cancel/process, the [ScanLifecycle] transition, incremental neural-recon training,
 * photometric-stereo bookkeeping) extracted out of `AppViewModel`, which previously held this
 * directly alongside everything else it does. `AppViewModel` keeps a thin forwarding method per
 * public function here, so this is a decomposition of *where the logic and state live*, not a
 * change to any external caller (`MainActivity` et al. still call `vm.startScan()` etc.
 * unchanged).
 *
 * Collaborators are held by reference, not copied or re-owned — `uiState`/`scanState`/etc. are
 * the exact same `MutableStateFlow` instances `AppViewModel` (and, for `uiState`/`scanState`,
 * the UI layer) already read/write; this class is one more place that shares them, the same
 * pattern `SpatialFrameRouter` and `SpatialFrameProducer` already use for `scanner`,
 * `freeformScanner`, and `tsdfVolume`.
 *
 * Explicitly out of scope for this extraction (left in `AppViewModel`): the per-hand-frame
 * fused-depth/TSDF integration block (tightly coupled to that collector's `primary`/`lms`/
 * `aspect` locals, not a standalone callable unit) and `toggleDepth()`/`recalibrateOef()`
 * (genuinely separate features that happen to touch `scanState`/`router` too, not part of the
 * start/cancel/process lifecycle this class owns).
 */
class ScanCoordinator(
    private val scope:                CoroutineScope,
    private val app:                  Application,
    private val uiState:               MutableStateFlow<AppUiState>,
    val scanState:                    MutableStateFlow<ScanState>,
    private val scanner:               Scanner,
    private val freeformScanner:       FreeformScanner,
    private val router:                SpatialFrameRouter,
    private val spatialLayer:          SpatialLayer,
    private val tsdfVolume:            TSDFVolume,
    private val modelStore:            PersonalModelStore,
    private val photoStereoCapture:    PhotometricStereoCapture,
    private val photoStereoNormalMap:  MutableStateFlow<PhotometricNormalMap?>,
    private val photoStereoFrameCount: MutableStateFlow<Int>,
    private val photoStereoComplete:   MutableStateFlow<Boolean>,
    private val renderer:              ARRenderer,
    private val depthMeshPositions:    MutableStateFlow<FloatArray>,
    private val currentAspect:         MutableStateFlow<Float>,
    private val liveMeshDeformer:      LiveMeshDeformer,
    private val handPipeline:          HandPipeline,
    private val getCameraController:   () -> CameraController,
    private val latestBitmap:          () -> Bitmap?,
    /** Persist calibrated OneEuroFilter params — kept as a callback so this class doesn't
     *  need access to AppViewModel's file-private DataStore delegate. */
    private val persistOefCalibration: suspend (cutoff: Float, beta: Float) -> Unit
) {
    /**
     * Rest-pose joint positions captured at the moment the scan completes. Read by
     * `AppViewModel.toggleLiveMesh()` and `stopRecordingAndExport()`.
     * @Volatile: written from this class's Dispatchers.Default scan-processing coroutines,
     * read from main-thread-bound call sites (ENGINE_ARCHITECTURE.md §4.6).
     */
    @Volatile var restJointPositions: FloatArray? = null
        private set

    private var torchOnAtScanStart: Boolean = true
    private var incrementalCarver: NeuralImplicitCarver? = null
    private var incrementalTrainJob: Job? = null

    /** Call once from AppViewModel's init to start the scan/freeform-scan status watchers. */
    fun start() {
        scope.launch {
            scanner.status.collect { status ->
                when (status.state) {
                    Scanner.ScanState.PROCESSING -> processScan()
                    else -> {}
                }
            }
        }

        // LIMIT-2 — Watch freeform scanner state → trigger processing when complete
        scope.launch {
            freeformScanner.status.collect { fs ->
                scanState.value = scanState.value.copy(freeformStatus = fs)
                when (fs.state) {
                    FreeformScanner.State.COMPLETE -> processFreeformScan()
                    // Previously reset only uiState.scanActive + scanState.freeformActive —
                    // router.isScanActive/isFreeformActive stayed true, so the router kept
                    // feeding a failed scan on every frame until the user separately cancelled.
                    FreeformScanner.State.FAILED   -> setScanLifecycle(ScanLifecycle.Idle)
                    else -> {}
                }
            }
        }

        scope.launch {
            val history = modelStore.loadHistory()
            scanState.value = scanState.value.copy(
                hasStoredModel   = modelStore.hasModel(),
                biometricHistory = history
            )
        }
    }

    /**
     * Single point of truth for "which scan mode is running right now" — sets
     * [ScanState.freeformActive], [AppUiState.scanActive], [router]'s
     * `isScanActive`/`isFreeformActive` together from one [ScanLifecycle] value, so they can't
     * drift apart the way the four hand-synchronized booleans they replace used to. Callers
     * still own the side effects around a transition (torch, TSDF, buffers, depthMode) — this
     * only sets the four flags.
     */
    fun setScanLifecycle(state: ScanLifecycle) {
        val active = state != ScanLifecycle.Idle
        uiState.update { it.copy(scanActive = active) }
        scanState.value = scanState.value.copy(freeformActive = state == ScanLifecycle.Freeform)
        router.isScanActive     = active
        router.isFreeformActive = state == ScanLifecycle.Freeform
    }

    fun startScan() {
        router.capturedFrames.clear()
        router.biometricFrames.clear()
        router.capturedDepthFrames.clear()
        router.capturedBitmaps.clear()   // R1
        tsdfVolume.reset()

        // Depth reconstruction (photometric/stereo/RS-stereo/PSP) only runs during an
        // active scan — see SpatialLayer.setReconstructionActive. Rear-camera-only,
        // same constraint toggleDepth() has always had (TSDF needs the rear metric cloud).
        if (!uiState.value.isFrontCamera) {
            scanState.value = scanState.value.copy(depthMode = true)
            spatialLayer.setReconstructionActive(true)
        }

        torchOnAtScanStart = uiState.value.torchOn
        photoStereoCapture.reset()
        if (scanState.value.photoStereoEnabled) photoStereoCapture.start()
        photoStereoFrameCount.value = 0
        photoStereoComplete.value   = false

        // HAND-6 — Reset the incremental carver and start collecting pose-done signals.
        // Training begins after pose index 2 (the 3rd completed pose) — enough data
        // for a coarse network, and early enough to be useful for partial scans.
        if (scanState.value.neuralReconEnabled) {
            val carver = NeuralImplicitCarver()
            incrementalCarver = carver
            incrementalTrainJob?.cancel()
            incrementalTrainJob = scope.launch(Dispatchers.Default) {
                scanner.poseCaptureDone.collect { poseIdx ->
                    // R1 — Snapshot the current camera frame for texture baking.
                    // Bitmap.copy() is thread-safe on read; the copy is immutable.
                    latestBitmap()?.let { bmp ->
                        val copy = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
                        synchronized(router.capturedBitmaps) { router.capturedBitmaps.add(copy) }
                    }

                    if (poseIdx < 2) return@collect   // wait for at least 3 poses
                    // Snapshot the frames captured so far for this incremental pass
                    val frameSnapshot = synchronized(router.capturedFrames) { router.capturedFrames.toList() }
                    val normalMap     = photoStereoNormalMap.value
                    carver.trainIncremental(frameSnapshot, normalMap = normalMap)
                }
            }
        }

        setScanLifecycle(ScanLifecycle.Posed)
        scanner.start()
    }

    fun cancelScan() {
        incrementalTrainJob?.cancel()
        incrementalTrainJob = null
        incrementalCarver   = null
        scanner.reset()
        router.capturedFrames.clear()
        router.biometricFrames.clear()
        router.capturedDepthFrames.clear()
        router.capturedBitmaps.clear()   // R1
        photoStereoCapture.stop()

        if (scanState.value.photoStereoEnabled && !uiState.value.isFrontCamera) {
            getCameraController().setTorch(torchOnAtScanStart)
        }
        scanState.value = scanState.value.copy(depthMode = false)
        spatialLayer.setReconstructionActive(false)
        setScanLifecycle(ScanLifecycle.Idle)
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
        router.capturedFrames.clear()
        router.biometricFrames.clear()
        router.capturedDepthFrames.clear()
        router.capturedBitmaps.clear()
        tsdfVolume.reset()

        if (!uiState.value.isFrontCamera) {
            scanState.value = scanState.value.copy(depthMode = true)
            spatialLayer.setReconstructionActive(true)
        }

        torchOnAtScanStart = uiState.value.torchOn
        photoStereoCapture.reset()
        if (scanState.value.photoStereoEnabled) photoStereoCapture.start()
        photoStereoFrameCount.value = 0
        photoStereoComplete.value   = false

        // Start incremental neural recon — trains every 30 accepted freeform frames
        if (scanState.value.neuralReconEnabled) {
            val carver = NeuralImplicitCarver()
            incrementalCarver = carver
            incrementalTrainJob?.cancel()
            incrementalTrainJob = scope.launch(Dispatchers.Default) {
                var lastTrainedCount = 0
                freeformScanner.status.collect { fs ->
                    if (fs.state != FreeformScanner.State.ACTIVE) return@collect
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
        setScanLifecycle(ScanLifecycle.Freeform)
    }

    /** User taps "Finish" during freeform scan — validates coverage then triggers processing. */
    fun finishFreeformScan() {
        freeformScanner.finish()
        // Completion / failure handled by the freeformScanner.status watcher in start()
    }

    fun cancelFreeformScan() {
        incrementalTrainJob?.cancel()
        incrementalTrainJob = null
        incrementalCarver   = null
        freeformScanner.reset()
        router.capturedFrames.clear()
        router.biometricFrames.clear()
        router.capturedDepthFrames.clear()
        router.capturedBitmaps.clear()
        photoStereoCapture.stop()

        if (scanState.value.photoStereoEnabled && !uiState.value.isFrontCamera) {
            getCameraController().setTorch(torchOnAtScanStart)
        }
        scanState.value = scanState.value.copy(freeformStatus = null, depthMode = false)
        spatialLayer.setReconstructionActive(false)
        setScanLifecycle(ScanLifecycle.Idle)
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
            scope.launch(Dispatchers.IO) {
                val normalMap = photoStereoCapture.computeNormalMap()
                if (!normalMap.isEmpty) photoStereoNormalMap.value = normalMap
                photoStereoCapture.stop()
                getCameraController().setTorch(torchOnAtScanStart)
            }
        }

        val input = ScanInput(
            cloudPoints        = freeformScanner.capturedFrames.flatMap { it.first },
            capturedFrames     = freeformScanner.capturedFrames.toList(),
            biometricFrames    = freeformScanner.biometricFrames.toList(),
            capturedDepth      = router.capturedDepthFrames.toList(),
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

        scope.launch(Dispatchers.Default) {
            try {
                val result = ScanPipeline().process(input, app)

                renderer.depthMeshPositions = result.meshPositions
                depthMeshPositions.value    = result.meshPositions

                result.restJointPositions?.let { rjp ->
                    restJointPositions = rjp
                    liveMeshDeformer.buildSkinWeights(result.meshPositions, rjp)
                }

                scanState.value = scanState.value.copy(
                    hasStoredModel         = result.glbFile != null,
                    completedScanId        = if (result.glbFile != null)
                                                 scanState.value.completedScanId + 1
                                             else scanState.value.completedScanId,
                    exportedGlbPath        = result.glbFile?.absolutePath,
                    handBiometrics         = result.biometrics,
                    biometricHistory       = result.biometricHistory,
                    jointRomData           = null,
                    neuralReconDiagnostics = result.neuralDiagnostics,
                    depthMode              = false
                )
                spatialLayer.setReconstructionActive(false)

                result.calibratedOefCutoff?.let { cutoff ->
                    result.calibratedOefBeta?.let { beta ->
                        handPipeline.setOefParams(cutoff, beta)
                        persistOefCalibration(cutoff, beta)
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
                setScanLifecycle(ScanLifecycle.Idle)

            } catch (e: Exception) {
                android.util.Log.e("ScanCoordinator", "processFreeformScan failed", e)
                freeformScanner.reset()
                photoStereoCapture.stop()
                if (scanState.value.photoStereoEnabled && !uiState.value.isFrontCamera) {
                    getCameraController().setTorch(torchOnAtScanStart)
                }
                scanState.value = scanState.value.copy(depthMode = false)
                spatialLayer.setReconstructionActive(false)
                setScanLifecycle(ScanLifecycle.Idle)
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
        val input = ScanInput(
            cloudPoints        = scanner.cloudPoints.toList(),
            capturedFrames     = router.capturedFrames.toList(),
            biometricFrames    = router.biometricFrames.toList(),
            capturedDepth      = router.capturedDepthFrames.toList(),
            capturedBitmaps    = synchronized(router.capturedBitmaps) { router.capturedBitmaps.toList() },
            normalMap          = photoStereoNormalMap.value,
            tsdfVolume         = tsdfVolume,
            incrementalCarver  = incrementalCarver,
            neuralReconEnabled = scanState.value.neuralReconEnabled,
            smplEnabled        = com.arhand.BuildConfig.FEATURE_SMPL_BODY,
            aspect             = currentAspect.value,
            isFrontCamera      = uiState.value.isFrontCamera,
            modelStore         = modelStore
        )

        scope.launch(Dispatchers.Default) {
            try {
                val result = ScanPipeline().process(input, app)

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
                        persistOefCalibration(cutoff, beta)
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
                    getCameraController().setTorch(torchOnAtScanStart)
                }

                result.glbFile?.let { f ->
                    val scoresJson = scanner.status.value.poseScores
                        .joinToString(",", "[", "]") { "%.3f".format(it) }
                    modelStore.saveModel(f, input.cloudPoints.size, scoresJson, result.biometrics)
                }

                scanner.markDone()
                scanState.value = scanState.value.copy(depthMode = false)
                spatialLayer.setReconstructionActive(false)
                setScanLifecycle(ScanLifecycle.Idle)

            } catch (e: Exception) {
                scanner.markFailed(e.message ?: "Processing error")
                photoStereoCapture.stop()
                if (scanState.value.photoStereoEnabled && !uiState.value.isFrontCamera) {
                    getCameraController().setTorch(torchOnAtScanStart)
                }
                scanState.value = scanState.value.copy(depthMode = false)
                spatialLayer.setReconstructionActive(false)
                setScanLifecycle(ScanLifecycle.Idle)
            }
        }
    }
}
