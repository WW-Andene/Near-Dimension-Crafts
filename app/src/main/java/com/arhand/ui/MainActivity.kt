package com.arhand.ui

import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arhand.mocap.AssetLoader
import com.arhand.mocap.OscSchema
import com.arhand.mocap.OscMode
import com.arhand.render.RenderMode
import com.arhand.scanner.Scanner
import java.io.File
import com.arhand.feature.record.TakeEntry
import com.arhand.feature.scan.NeuralReconDiagnostics

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

    // Hot-path flows — independent collection prevents full-tree recomposition
    val activeGesture         by vm.activeGesture.collectAsStateWithLifecycle()
    val depthConfidence       by vm.depthConfidence.collectAsStateWithLifecycle()
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
    // Show result modal when freeform scan completes and GLB is ready
    LaunchedEffect(scanDomainState.freeformActive, scanDomainState.hasStoredModel) {
        if (!scanDomainState.freeformActive && scanDomainState.hasStoredModel) {
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

        if (!uiState.showSplash && !uiState.showOnboarding) {
            HudOverlay(
                perf             = perfState,
                renderMode       = uiState.renderMode.name,
                torchOn          = uiState.torchOn,
                landmarkCount    = processedHands.sumOf { it.landmarks.size },
                activeSlots      = processedHands.map { it.slotIndex }.sorted()
                                       .joinToString(",").ifEmpty { "--" },
                activeGesture    = activeGesture,
                handSide         = processedHands.firstOrNull()?.side
                                       ?: com.arhand.tracking.HandSide.UNKNOWN,
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
                        "com.arhand.fileprovider", java.io.File(path)
                    )
                    /* share intent handled in activity layer */
                },
                onDismiss = { showTakesLibrary = false }
            )
        }
    }
}

/**
 * ASSET-5 — Asset browser: scans getExternalFilesDir("models") and lists .glb files
 * alongside bundled assets. Returns list of (displayLabel, filePath) pairs.
 */
@Composable
private fun rememberDeviceAssets(context: android.content.Context): List<Pair<String, String>> {
    return remember {
        val dirs = listOfNotNull(
            context.getExternalFilesDir("models"),
            java.io.File(context.cacheDir, "models")   // Item 3: generated bundled assets
        )
        dirs.flatMap { dir ->
            dir.mkdirs()
            dir.listFiles { f -> f.extension.lowercase() == "glb" }
                ?.sortedBy { it.name }
                ?.map { file -> file.nameWithoutExtension to file.absolutePath }
                ?: emptyList()
        }.distinctBy { it.second }
    }
}

// ─── ASSET-1/2/3/5: Asset picker dialog ──────────────────────────────────────

/**
 * Asset picker dialog with three sections:
 *  1. Built-in — bundled GLBs shipped with the APK.
 *  2. From device — files in getExternalFilesDir("models") + system file picker.
 *  3. Remove — reverts to the default puppet.
 */
@Composable
fun AssetPickerDialog(
    currentAssetName: String?,
    onPickFromDevice: () -> Unit,
    onPickBundled:    (filename: String, name: String) -> Unit,
    onRemove:         () -> Unit,
    onDismiss:        () -> Unit
) {
    val ctx          = androidx.compose.ui.platform.LocalContext.current
    val deviceAssets = rememberDeviceAssets(ctx)
    val dropPath     = ctx.getExternalFilesDir("models")?.absolutePath ?: ""

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0E1117))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Header
                Text(
                    text       = "LOAD 3D MODEL",
                    fontSize   = 11.sp,
                    color      = Color(0xFF7B2FBE),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                if (currentAssetName != null) {
                    Text(
                        text       = "Current: $currentAssetName",
                        fontSize   = 9.sp,
                        color      = Color.White.copy(alpha = 0.4f),
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Built-in section
                Text(
                    text       = "BUILT-IN",
                    fontSize   = 8.sp,
                    color      = Color.White.copy(alpha = 0.3f),
                    fontFamily = FontFamily.Monospace
                )
                AssetLoader.Bundled.ALL.forEach { (label, filename) ->
                    AssetPickerRow(label = label, onClick = { onPickBundled(filename, label) })
                }

                // ASSET-5 — Device files from drop folder
                if (deviceAssets.isNotEmpty()) {
                    Text(
                        text       = "FROM DEVICE FOLDER",
                        fontSize   = 8.sp,
                        color      = Color.White.copy(alpha = 0.3f),
                        fontFamily = FontFamily.Monospace
                    )
                    deviceAssets.forEach { (label, path) ->
                        AssetPickerRow(
                            label   = label,
                            accent  = Color(0xFF00E5FF),
                            onClick = {
                                onDismiss()
                                // Load file URI from absolute path
                                val uri = android.net.Uri.fromFile(java.io.File(path))
                                onPickBundled(path, label)   // reuse bundled path for direct load
                            }
                        )
                    }
                }

                // Browse with system file picker
                Text(
                    text       = "FROM DEVICE",
                    fontSize   = 8.sp,
                    color      = Color.White.copy(alpha = 0.3f),
                    fontFamily = FontFamily.Monospace
                )
                AssetPickerRow(
                    label   = "Browse files (.glb)",
                    accent  = Color(0xFF00E5FF),
                    onClick = onPickFromDevice
                )

                // ASSET-5 — Drop folder hint
                Text(
                    text       = "Drop .glb files in:\n$dropPath",
                    fontSize   = 7.sp,
                    color      = Color.White.copy(alpha = 0.25f),
                    fontFamily = FontFamily.Monospace
                )

                // Remove section
                if (currentAssetName != null) {
                    AssetPickerRow(
                        label   = "Remove — revert to default",
                        accent  = Color(0xFFFF5722),
                        onClick = onRemove
                    )
                }

                // Cancel
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = "CANCEL",
                        fontSize   = 9.sp,
                        color      = Color.White.copy(alpha = 0.3f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetPickerRow(
    label:   String,
    accent:  Color  = Color(0xFF7B2FBE),
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text       = label,
            fontSize   = 10.sp,
            color      = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Gap 7: Stream panel ───────────────────────────────────────────────────────

@Composable
private fun StreamPanel(
    isStreaming:         Boolean,
    oscHost:             String,
    oscPort:             Int,
    oscSchema:           OscSchema,
    oscHealth:           com.arhand.mocap.OscHealthState,
    discoveredReceivers: List<com.arhand.feature.stream.OscDiscovery.DiscoveredReceiver> = emptyList(),
    isDiscovering:       Boolean = false,
    onToggle:            () -> Unit,
    onDiscoverToggle:    () -> Unit = {},
    onSelectReceiver:    (com.arhand.feature.stream.OscDiscovery.DiscoveredReceiver) -> Unit = {}
) {
    val accentCol = if (isStreaming) Color(0xFF00C853) else Color.White.copy(0.3f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0C12))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // Big toggle
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(accentCol.copy(0.12f))
                .border(1.dp, accentCol, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (isStreaming) "■  STREAMING" else "▶  START",
                fontSize = 10.sp, color = accentCol,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
        }
        // Status info
        Column(modifier = Modifier.weight(1f)) {
            Text("$oscSchema  •  $oscHost:$oscPort",
                fontSize = 9.sp, color = Color.White.copy(0.5f),
                fontFamily = FontFamily.Monospace)
            if (isStreaming) {
                val healthColor = if (oscHealth.isHealthy) Color(0xFF00C853) else Color(0xFFFF6D00)
                Text(
                    if (oscHealth.isHealthy) "%.0f fps  •  ${oscHealth.sentFrames} sent".format(oscHealth.frameRateFps)
                    else "NO SIGNAL",
                    fontSize = 8.sp, color = healthColor,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text("TAP to start streaming", fontSize = 8.sp,
                    color = Color.White.copy(0.3f), fontFamily = FontFamily.Monospace)
            }
        }
        // GAP-11 — DISCOVER button
        val discoverColor = if (isDiscovering) Plasma else Color.White.copy(0.25f)
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .background(discoverColor.copy(0.1f))
                .border(1.dp, discoverColor, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .clickable(onClick = onDiscoverToggle)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Text(if (isDiscovering) "SCAN…" else "FIND",
                fontSize = 8.sp, color = discoverColor,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        }

        // GAP-11 — Discovered receivers list
        if (discoveredReceivers.isNotEmpty()) {
            discoveredReceivers.forEach { rec ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(Plasma.copy(0.08f))
                        .border(1.dp, Plasma.copy(0.3f),
                            androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .clickable { onSelectReceiver(rec) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(rec.name, fontSize = 9.sp, color = Color.White.copy(0.8f),
                            fontFamily = FontFamily.Monospace)
                        Text("${rec.service}  •  ${rec.host}:${rec.port}",
                            fontSize = 7.sp, color = Plasma.copy(0.6f),
                            fontFamily = FontFamily.Monospace)
                    }
                    Text("USE →", fontSize = 7.sp, color = Plasma,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Gap 5/7: Record panel ─────────────────────────────────────────────────────

@Composable
private fun RecordPanel(
    isRecording:      Boolean,
    currentTakeLabel: String,
    takes:            List<TakeEntry>,
    onToggle:         () -> Unit,
    onLabelChange:    (String) -> Unit,
    onBrowseTakes:    () -> Unit   // GAP-4
) {
    val accentCol = if (isRecording) Color(0xFFD50000) else Color.White.copy(0.3f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0C12))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(accentCol.copy(0.12f))
                    .border(1.dp, accentCol, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isRecording) "■  STOP" else "●  REC",
                    fontSize = 10.sp, color = accentCol,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            }
            // Inline take label editor
            if (!isRecording) {
                androidx.compose.foundation.text.BasicTextField(
                    value         = currentTakeLabel,
                    onValueChange = onLabelChange,
                    singleLine    = true,
                    textStyle     = androidx.compose.ui.text.TextStyle(
                        color      = Color.White.copy(0.8f),
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .background(Color.White.copy(0.05f))
                                .border(1.dp, Color.White.copy(0.1f),
                                    androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            if (currentTakeLabel.isEmpty()) {
                                Text("Take name…", fontSize = 11.sp,
                                    color = Color.White.copy(0.2f), fontFamily = FontFamily.Monospace)
                            }
                            inner()
                        }
                    }
                )
            } else {
                Text("Recording  •  take ${takes.size + 1}",
                    fontSize = 9.sp, color = accentCol, fontFamily = FontFamily.Monospace)
            }
        }
        // Last take summary + BROWSE button (GAP-4)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            takes.lastOrNull()?.let { last ->
                Text(
                    "Last: \"${last.label}\"  •  ${last.durationMs / 1000}s",
                    fontSize = 8.sp, color = Color.White.copy(0.3f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            } ?: Text("No takes yet", fontSize = 8.sp, color = Color.White.copy(0.15f),
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))

            if (takes.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(Plasma.copy(0.1f))
                        .border(1.dp, Plasma.copy(0.4f),
                            androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .clickable(onClick = onBrowseTakes)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("LIBRARY  ${takes.size}", fontSize = 8.sp, color = Plasma,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── GAP-4: Takes Library Sheet ────────────────────────────────────────────────

/**
 * Full-screen takes library: browse, share, and delete recorded takes.
 *
 * Displayed as a modal overlay when the user taps "LIBRARY" in [RecordPanel].
 * Each row shows the take label, duration, frame count, and date, with share
 * and delete actions. Deletion removes both the list entry and the files on disk.
 */
@Composable
private fun TakesLibrarySheet(
    takes:    List<TakeEntry>,
    onDelete: (TakeEntry) -> Unit,
    onShare:  (TakeEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE080A0F))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 52.dp, start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TAKES  (${takes.size})", fontSize = 14.sp, color = Plasma,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .border(1.dp, Color.White.copy(0.2f),
                            androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("✕ CLOSE", fontSize = 9.sp, color = Color.White.copy(0.5f),
                        fontFamily = FontFamily.Monospace)
                }
            }

            if (takes.isEmpty()) {
                Text("No takes recorded yet.",
                    fontSize = 12.sp, color = Color.White.copy(0.3f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(takes.reversed()) { take ->
                        TakeRow(take = take, onShare = { onShare(take) }, onDelete = { onDelete(take) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TakeRow(
    take:     TakeEntry,
    onShare:  () -> Unit,
    onDelete: () -> Unit
) {
    val date = java.text.SimpleDateFormat("MMM d  HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(take.takenAtMs))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(0.04f),
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(0.08f),
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(take.label, fontSize = 11.sp, color = Color.White.copy(0.9f),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(
                "${take.frameCount} frames  •  ${take.durationMs / 1000}s  •  $date",
                fontSize = 8.sp, color = Color.White.copy(0.4f),
                fontFamily = FontFamily.Monospace
            )
            if (take.bvhPath != null)
                Text("BVH", fontSize = 7.sp, color = Plasma.copy(0.6f),
                    fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(8.dp))
        // Share
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .background(Plasma.copy(0.1f))
                .border(1.dp, Plasma.copy(0.4f),
                    androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .clickable(onClick = onShare)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("↑", fontSize = 11.sp, color = Plasma, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(6.dp))
        // Delete
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .background(Warn.copy(0.08f))
                .border(1.dp, Warn.copy(0.4f),
                    androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .clickable(onClick = onDelete)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("✕", fontSize = 11.sp, color = Warn, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── LIMIT-2: Freeform scan panel ──────────────────────────────────────────────

/**
 * Bottom bar panel for the FREEFORM workflow mode.
 *
 * Shown when [WorkflowMode.FREEFORM] is selected. Displays start/finish/cancel
 * controls and key status (coverage, frame count, depth mode toggle) without
 * duplicating the full [FreeformScanOverlay] — the overlay is shown on top of the
 * AR view when a scan is in progress; this panel is the idle launcher.
 */
@Composable
private fun FreeformPanel(
    isActive:      Boolean,
    status:        com.arhand.scanner.FreeformScanner.FreeformStatus,
    torchOn:       Boolean,
    depthMode:     Boolean,
    onStart:       () -> Unit,
    onFinish:      () -> Unit,
    onCancel:      () -> Unit,
    onTorch:       () -> Unit,
    onToggleDepth: () -> Unit
) {
    val accentCol   = if (isActive) Plasma else Color.White.copy(0.3f)
    val coveragePct = (status.coveragePercent * 100).toInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0C12))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Primary action button
            if (!isActive) {
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(Plasma.copy(0.12f))
                        .border(1.dp, Plasma, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable(onClick = onStart)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▶  FREE SCAN", fontSize = 10.sp, color = Plasma,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            } else {
                // Active: show Finish + Cancel
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(Plasma.copy(0.12f))
                        .border(1.dp, Plasma, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable(enabled = status.frameCount >= com.arhand.scanner.FreeformScanner.MIN_FRAMES_FOR_MESH,
                            onClick = onFinish)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓ FINISH", fontSize = 10.sp, color = Plasma,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(Warn.copy(0.08f))
                        .border(1.dp, Warn, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕ CANCEL", fontSize = 10.sp, color = Warn,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            // Status info column
            Column {
                if (isActive) {
                    Text("$coveragePct% coverage  •  ${status.frameCount} frames",
                        fontSize = 9.sp, color = Plasma.copy(0.8f),
                        fontFamily = FontFamily.Monospace)
                    Text("${status.elapsedSec.toInt()}s / ${com.arhand.scanner.FreeformScanner.MAX_DURATION_SEC.toInt()}s  •  rotate your hand freely",
                        fontSize = 8.sp, color = Color.White.copy(0.4f),
                        fontFamily = FontFamily.Monospace)
                } else {
                    Text("Continuous free-rotation scan", fontSize = 9.sp,
                        color = Color.White.copy(0.5f), fontFamily = FontFamily.Monospace)
                    Text("No pose holding required", fontSize = 8.sp,
                        color = Color.White.copy(0.3f), fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Depth + Torch toggles
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val depthCol = if (depthMode) Plasma else Color.White.copy(0.2f)
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .border(1.dp, depthCol, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .background(depthCol.copy(0.08f))
                    .clickable(onClick = onToggleDepth)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("DEPTH ${if (depthMode) "ON" else "OFF"}", fontSize = 8.sp,
                    color = depthCol, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold)
            }
            val torchCol = if (torchOn) Plasma else Color.White.copy(0.2f)
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .border(1.dp, torchCol, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .background(torchCol.copy(0.08f))
                    .clickable(onClick = onTorch)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("TORCH ${if (torchOn) "ON" else "OFF"}", fontSize = 8.sp,
                    color = torchCol, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BottomToolbar(
    workflowMode: WorkflowMode,
    isStreaming:  Boolean,
    isRecording:  Boolean,
    isScanActive: Boolean,
    onSettings:   () -> Unit,
    onAssets:     () -> Unit,
    onCalibrate:  () -> Unit,
    onStream:     () -> Unit,
    onRecord:     () -> Unit
) {
    val Mono = FontFamily.Monospace
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC111111))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarItem("SETTINGS", workflowMode == WorkflowMode.SETTINGS, Color.White, onSettings)
        ToolbarItem("ASSETS",   false,                                  Color(0xFFFFD600), onAssets)
        ToolbarItem("SCAN",     isScanActive,                           Color(0xFF7B2FBE), onCalibrate)
        ToolbarItem("STREAM",   isStreaming,                            Color(0xFF00C853), onStream)
        ToolbarItem("REC",      isRecording,                            Color(0xFFD50000), onRecord)
    }
}

@Composable
private fun ToolbarItem(label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text       = label,
            color      = if (active) activeColor else Color.White.copy(alpha = 0.45f),
            fontSize   = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        if (active) {
            Spacer(Modifier.height(3.dp))
            Box(Modifier.size(4.dp).background(activeColor, shape = RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun CalibrateSheet(
    onPosedScan: () -> Unit,
    onFreeform:  () -> Unit,
    onDismiss:   () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(24.dp)
                .clickable(indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = {}),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text       = "CALIBRATE",
                color      = Color.White,
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(bottom = 4.dp)
            )
            CalibButton("POSED SCAN  ·  8 static poses", Color(0xFF7B2FBE), onPosedScan)
            CalibButton("FREEFORM SCAN  ·  rotate hand freely", Color(0xFF7B2FBE), onFreeform)
            CalibButton("CANCEL", Color(0x66FFFFFF), onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CalibButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text       = label,
            color      = color,
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
