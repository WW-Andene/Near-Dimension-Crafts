package com.neardimension.handy.ui

import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neardimension.handy.scanner.Scanner
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.initCamera(this@MainActivity) else vm.onCameraPermissionDenied()
    }

    /**
     * Re-triggers the CAMERA permission flow from the splash screen's retry button.
     * If the system will no longer show the rationale dialog (permanently denied,
     * "don't ask again"), re-launching the request just re-denies silently — send
     * the user to the app's system settings page instead so they have a real way
     * to recover.
     */
    private fun requestCameraPermission() {
        val permanentlyDenied = !shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)
        val previouslyDenied  = vm.uiState.value.cameraPermissionDenied
        if (previouslyDenied && permanentlyDenied) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            })
        } else {
            cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    // ASSET-1 — File picker launcher. Accepts any GLB (model/gltf-binary) or
    // generic octet-stream (some file managers report .glb as application/octet-stream).
    private val assetPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // Resolve a human-readable display name from ContentResolver
            val displayName = try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
                }
            } catch (_: Exception) { null }
            vm.loadAsset(uri, displayName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.lifecycle.compose.LocalLifecycleOwner provides this@MainActivity
            ) {
                HandyApp(
                    vm                        = vm,
                    onShareGlb                = ::shareGlb,
                    onPickAsset               = { assetPickerLauncher.launch("*/*") },
                    onRequestCameraPermission = ::requestCameraPermission
                )
            }
        }
        cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    private fun shareGlb() {
        val path = vm.scanState.value.exportedGlbPath ?: return
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "model/gltf-binary"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Hand Model"))
    }
}

@Composable
fun HandyApp(
    vm: AppViewModel,
    onShareGlb: () -> Unit,
    onPickAsset: () -> Unit,
    onRequestCameraPermission: () -> Unit = {}
) {
    val uiState            by vm.uiState.collectAsStateWithLifecycle()
    val trackingState      by vm.trackingManager.state.collectAsStateWithLifecycle()
    val streamState        by vm.oscManager.state.collectAsStateWithLifecycle()
    val recordingState     by vm.recordingManager.state.collectAsStateWithLifecycle()
    val assetState         by vm.assetManager.state.collectAsStateWithLifecycle()
    val scanDomainState    by vm.scanState.collectAsStateWithLifecycle()
    val perfState          by vm.perfMonitor.state.collectAsStateWithLifecycle()
    val scanStatus         by vm.scanner.status.collectAsStateWithLifecycle()
    val depthMeshPositions by vm.depthMeshPositions.collectAsStateWithLifecycle()
    val processedHands     by vm.handPipeline.processed.collectAsStateWithLifecycle()

    // ENGINE_ARCHITECTURE.md §9 — derive HudOverlay's hand-summary params via derivedStateOf
    // instead of recomputing them inline in HandyApp's body on every processedHands emission
    // (hand-inference rate). Compose already skips HudOverlay's own recomposition when these
    // resolve to equal values frame-to-frame; derivedStateOf makes that explicit/guaranteed
    // rather than relying on positional-parameter equality checks.
    val landmarkCount by remember { derivedStateOf { processedHands.sumOf { it.landmarks.size } } }
    val activeSlotsStr by remember {
        derivedStateOf { processedHands.map { it.slotIndex }.sorted().joinToString(",").ifEmpty { "--" } }
    }
    val handSide by remember {
        derivedStateOf { processedHands.firstOrNull()?.side ?: com.neardimension.handy.tracking.HandSide.UNKNOWN }
    }

    // Hot-path flows — independent collection prevents full-tree recomposition
    val activeGesture         by vm.activeGesture.collectAsStateWithLifecycle()
    val photoStereoFrameCount by vm.photoStereoFrameCount.collectAsStateWithLifecycle()
    val photoStereoComplete   by vm.photoStereoComplete.collectAsStateWithLifecycle()

    // LIMIT-2 — Freeform scan status
    val freeformStatus by vm.freeformScanner.status.collectAsStateWithLifecycle()

    // Spatial layer state — always-on depth sensing
    val spatialState by vm.spatialLayer.state.collectAsStateWithLifecycle()
    val oscHealthState by vm.oscHealth.state.collectAsStateWithLifecycle()

    var showResult        by remember { mutableStateOf(false) }
    var showAssetPicker   by remember { mutableStateOf(false) }
    var showTakesLibrary  by remember { mutableStateOf(false) }   // GAP-4
    var showCalibrateSheet by remember { mutableStateOf(false) }

    LaunchedEffect(scanStatus.state) {
        if (scanStatus.state == Scanner.ScanState.DONE) showResult = true
    }
    // Show result modal only on an actual successful freeform completion — keyed off
    // completedScanId (bumped once per successful scan) rather than the ambient
    // hasStoredModel flag, so a later cancelled/failed freeform scan can't re-trigger
    // the modal from a stale "model exists" state (see ENGINE_ARCHITECTURE.md §4.2).
    LaunchedEffect(scanDomainState.completedScanId) {
        if (scanDomainState.completedScanId > 0) {
            showResult = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // GL Surface — RGBA8888 EGL config required so glClearColor alpha=0 is
        // transparent rather than black. Without setEGLConfigChooser the default
        // config on many devices is RGB (no alpha) and the clear produces opaque black.
        if (!uiState.showSplash) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(3)
                        // Request RGBA8888 so the alpha channel of the clear colour works
                        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                        setRenderer(vm.renderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                }
            )
        }

        // GAP-7 — Back navigation for all overlays and modals.
        // Without these, pressing back exits the Activity from any depth.
        // Each BackHandler is enabled only when its overlay is visible.
        BackHandler(enabled = showTakesLibrary) {
            showTakesLibrary = false
        }
        BackHandler(enabled = showCalibrateSheet) {
            showCalibrateSheet = false
        }
        BackHandler(enabled = uiState.scanActive && !scanDomainState.freeformActive) {
            vm.cancelScan()
        }
        BackHandler(enabled = uiState.scanActive && scanDomainState.freeformActive) {
            vm.cancelFreeformScan()
        }
        BackHandler(enabled = uiState.workflowMode == WorkflowMode.SETTINGS) {
            vm.setWorkflowMode(WorkflowMode.IDLE)
        }
        BackHandler(enabled = showResult) {
            showResult = false
        }
        BackHandler(enabled = showAssetPicker) {
            showAssetPicker = false
        }
        BackHandler(enabled = uiState.showOnboarding) {
            vm.dismissOnboarding()
        }

        if (uiState.showSplash) {
            SplashScreen(
                permissionDenied     = uiState.cameraPermissionDenied,
                onRequestPermission  = onRequestCameraPermission
            )
        }

        if (!uiState.showSplash && uiState.showOnboarding) {
            OnboardingScreen(onDismiss = { vm.dismissOnboarding() })
        }

        // ENGINE_ARCHITECTURE.md §6.2 — capture flash, fires once per accepted pose.
        WhiteScreenOverlay(trigger = uiState.captureFlashToken)

        // HUD, scan overlays and toolbar stay clear of system bars and display cutouts
        // (edge-to-edge is enforced from targetSdk 35). Full-screen layers around this
        // block (GL surface, flash, splash, settings, modals) stay full-bleed.
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            if (!uiState.showSplash && !uiState.showOnboarding) {
                HudOverlay(
                    perf             = perfState,
                    renderMode       = uiState.renderMode.name,
                    torchOn          = uiState.torchOn,
                    landmarkCount    = landmarkCount,
                    activeSlots      = activeSlotsStr,
                    activeGesture    = activeGesture,
                    handSide         = handSide,
                    oscHealth        = oscHealthState,
                    spatialState     = spatialState,
                    slCalibProgress  = if (scanDomainState.depthMode)
                                           scanDomainState.slCalibrationProgress else -1f,
                    slDrifting       = scanDomainState.slDrifting
                )
            }

            if (uiState.scanActive && !scanDomainState.freeformActive) {
                ScanOverlay(status = scanStatus, onCancel = { vm.cancelScan() })
            }

            // LIMIT-2 — Freeform scan overlay
            if (uiState.scanActive && scanDomainState.freeformActive) {
                FreeformScanOverlay(
                    status   = freeformStatus,
                    onFinish = { vm.finishFreeformScan() },
                    onCancel = { vm.cancelFreeformScan() }
                )
            }

            // Global camera flip — always visible regardless of scan or overlay state
            if (!uiState.showSplash && !uiState.showOnboarding) {
                androidx.compose.material3.IconButton(
                    onClick  = vm::switchCamera,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp)
                        .background(Color(0x66000000), shape = RoundedCornerShape(8.dp))
                ) {
                    Text(
                        text     = if (uiState.isFrontCamera) "REAR" else "FRONT",
                        color    = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (!uiState.showSplash && !uiState.showOnboarding && !uiState.scanActive) {
                Column(modifier = Modifier.align(Alignment.BottomCenter)) {

                    when (uiState.workflowMode) {
                        WorkflowMode.STREAM -> StreamPanel(
                            isStreaming         = streamState.isStreaming,
                            oscHost             = streamState.host,
                            oscPort             = streamState.port,
                            oscSchema           = streamState.schema,
                            oscHealth           = oscHealthState,
                            discoveredReceivers = vm.oscDiscovery.found.collectAsStateWithLifecycle().value,
                            isDiscovering       = vm.oscDiscovery.scanning.collectAsStateWithLifecycle().value,
                            onToggle            = {
                                if (streamState.isStreaming) vm.stopOscStreaming()
                                else vm.startOscStreaming(streamState.host, streamState.port, streamState.schema)
                            },
                            onDiscoverToggle    = {
                                if (vm.oscDiscovery.scanning.value) vm.oscDiscovery.stop()
                                else vm.oscDiscovery.start()
                            },
                            onSelectReceiver    = { rec ->
                                vm.oscManager.setHost(rec.host)
                                vm.oscManager.setPort(rec.port)
                                vm.oscDiscovery.stop()
                            }
                        )
                        WorkflowMode.RECORD -> RecordPanel(
                            isRecording      = recordingState.isRecording,
                            currentTakeLabel = recordingState.currentLabel,
                            takes            = recordingState.takes,
                            onToggle         = {
                                if (recordingState.isRecording) vm.stopRecordingAndExport()
                                else vm.startRecording()
                            },
                            onLabelChange    = vm::setTakeLabel,
                            onBrowseTakes    = { showTakesLibrary = true }
                        )
                        else -> {}
                    }

                    BottomToolbar(
                        workflowMode  = uiState.workflowMode,
                        isStreaming   = streamState.isStreaming,
                        isRecording   = recordingState.isRecording,
                        isScanActive  = uiState.scanActive,
                        onSettings    = { vm.setWorkflowMode(WorkflowMode.SETTINGS) },
                        onAssets      = { showAssetPicker = true },
                        onCalibrate   = { showCalibrateSheet = true },
                        onStream      = { vm.setWorkflowMode(
                            if (uiState.workflowMode == WorkflowMode.STREAM) WorkflowMode.IDLE
                            else WorkflowMode.STREAM) },
                        onRecord      = { vm.setWorkflowMode(
                            if (uiState.workflowMode == WorkflowMode.RECORD) WorkflowMode.IDLE
                            else WorkflowMode.RECORD) }
                    )
                }
            }
        }

        // Gap 1 — Settings screen overlay
        if (uiState.workflowMode == WorkflowMode.SETTINGS) {
            SettingsScreen(
                oscHost           = streamState.host,
                oscPort           = streamState.port,
                oscSchema         = streamState.schema,
                bodyEnabled       = trackingState.bodyEnabled,
                faceEnabled       = trackingState.faceEnabled,
                constraintEnabled = trackingState.constraintEnabled,
                isStreaming       = streamState.isStreaming,
                currentTakeLabel  = recordingState.currentLabel,
                takes             = recordingState.takes,
                showCloud         = uiState.showCloud,
                depthMode         = scanDomainState.depthMode,
                isFrontCamera     = uiState.isFrontCamera,
                roomMapActive     = uiState.roomMapActive,
                roomMapExportPath = uiState.roomMapExportPath,
                onOscHostChange   = { host ->
                    vm.oscManager.setHost(host)
                    if (streamState.isStreaming) vm.startOscStreaming(host, streamState.port, streamState.schema)
                },
                onOscPortChange   = { port ->
                    vm.oscManager.setPort(port)
                    if (streamState.isStreaming) vm.startOscStreaming(streamState.host, port, streamState.schema)
                },
                onSchemaChange    = vm::setOscSchema,
                onBodyToggle      = { vm.enableBodyTracking(!trackingState.bodyEnabled) },
                onFaceToggle      = { vm.enableFaceTracking(!trackingState.faceEnabled) },
                onConstraintToggle = vm::toggleConstraint,
                onRecalibrateOef  = { vm.recalibrateOef() },
                onTakeLabelChange = vm::setTakeLabel,
                onCloudToggle     = vm::toggleCloud,
                onDepthToggle     = vm::toggleDepth,
                onRoomMapToggle   = vm::toggleRoomMap,
                onExportRoomMap   = vm::exportRoomMap,
                onClearRoomMap    = vm::clearRoomMap,
                onDismiss         = { vm.setWorkflowMode(WorkflowMode.IDLE) }
            )
        }

        if (showResult) {
            val liveRetarget by vm.latestRetargetResult.collectAsStateWithLifecycle()
            ScanResultModal(
                pointCount    = scanStatus.totalPoints,
                glbPath       = scanDomainState.exportedGlbPath,
                poseScores    = scanStatus.poseScores,
                meshPositions = depthMeshPositions,
                biometrics         = scanDomainState.handBiometrics,
                biometricHistory   = scanDomainState.biometricHistory,
                jointRomData       = scanDomainState.jointRomData,
                onShare       = { onShareGlb(); showResult = false },
                onRescan      = { showResult = false; vm.startScan() },
                onDismiss     = { showResult = false },
                onCalibrate   = { vm.launchCalibrationCardFlow() },
                retargetResult = liveRetarget,
                loadedAsset    = vm.loadedAsset
            )
        }

        // ASSET-1/2/3 — Asset picker dialog
        if (showAssetPicker) {
            AssetPickerDialog(
                currentAssetName = assetState.name,
                onPickFromDevice = { showAssetPicker = false; onPickAsset() },
                onPickBundled    = { filename, name ->
                    showAssetPicker = false
                    vm.loadBundledAsset(filename, name)
                },
                onRemove         = { showAssetPicker = false; vm.removeAsset() },
                onDismiss        = { showAssetPicker = false }
            )
        }

        if (showCalibrateSheet && !uiState.scanActive) {
            CalibrateSheet(
                onPosedScan  = { showCalibrateSheet = false; vm.startScan() },
                onFreeform   = { showCalibrateSheet = false; vm.startFreeformScan() },
                onDismiss    = { showCalibrateSheet = false }
            )
        }

        // GAP-4 — Takes library sheet
        val localCtx = androidx.compose.ui.platform.LocalContext.current
        if (showTakesLibrary) {
            TakesLibrarySheet(
                takes     = recordingState.takes,
                onDelete  = { take -> vm.recordingManager.deleteTake(take) },
                onShare   = { take ->
                    val path = take.bvhPath ?: take.gltfPath ?: return@TakesLibrarySheet
                    val uri  = androidx.core.content.FileProvider.getUriForFile(
                        localCtx,
                        "${localCtx.packageName}.fileprovider", java.io.File(path)
                    )
                    /* share intent handled in activity layer */
                },
                onDismiss = { showTakesLibrary = false }
            )
        }
    }
}
