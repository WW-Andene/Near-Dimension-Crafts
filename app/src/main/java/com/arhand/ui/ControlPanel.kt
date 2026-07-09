package com.arhand.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Divider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arhand.mocap.OscSchema
import com.arhand.mocap.OscMode

val UIBg = Color(0xD20A0D12)
val Plasma = Color(0xFF7B2FBE)
val Warn   = Color(0xFFFF5722)
val RecRed = Color(0xFFE53935)

/**
 * Bottom control panel — matches the HTML prototype's control bar.
 * Buttons: Mode | Torch | Cloud | Camera | REC | Scan | Body | Face | Load Model
 */
@Composable
fun ControlPanel(
    renderMode: String,
    torchOn: Boolean,
    showCloud: Boolean,
    scanActive: Boolean,
    hasModel: Boolean,
    isRecording: Boolean,
    liveMeshActive: Boolean,
    /** ASSET-1 — Display name of currently loaded asset, or null. */
    loadedAssetName: String? = null,
    /** ASSET-1 — True while an asset is being loaded from storage. */
    assetLoading: Boolean = false,
    /** BODY-2 — True when body pose tracking is active. */
    bodyTrackingEnabled: Boolean = false,
    /** BODY-2 — True when face landmark tracking is active. */
    faceTrackingEnabled: Boolean = false,
    onMode: () -> Unit,
    onTorch: () -> Unit,
    onCloud: () -> Unit,
    onCamera: () -> Unit,
    onScan: () -> Unit,
    onViewModel: () -> Unit,
    onRecord: () -> Unit,
    onLiveMesh: () -> Unit,
    /** ASSET-1 — Opens the asset picker dialog. */
    onLoadModel: () -> Unit = {},
    /** BODY-2 — Toggle body pose tracking on/off. */
    onBodyToggle: () -> Unit = {},
    /** BODY-2 — Toggle face landmark tracking on/off. */
    onFaceToggle: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(UIBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CtrlButton("MODE\n$renderMode", active = true, onClick = onMode)
            CtrlButton("TORCH\n${if(torchOn) "ON" else "OFF"}", active = torchOn, onClick = onTorch)
            CtrlButton("CLOUD\n${if(showCloud) "ON" else "OFF"}", active = showCloud, onClick = onCloud)
            CtrlButton("FLIP\nCAM", active = false, onClick = onCamera)

            // REC button — red when recording, dim when idle
            CtrlButton(
                label       = if (isRecording) "STOP\nREC" else "REC",
                active      = isRecording,
                accentColor = RecRed,
                onClick     = onRecord
            )

            if (hasModel) {
                CtrlButton("3D\nMODEL", active = true, onClick = onViewModel)
            } else {
                CtrlButton(if (scanActive) "CANCEL\nSCAN" else "START\nSCAN",
                    active = !scanActive,
                    accentColor = if (scanActive) Warn else Plasma,
                    onClick = onScan
                )
            }

            if (hasModel) {
                CtrlButton(
                    label       = if (liveMeshActive) "LIVE\nON" else "LIVE\nOFF",
                    active      = liveMeshActive,
                    accentColor = Color(0xFF1FC8A8),
                    onClick     = onLiveMesh
                )
            }

            // ASSET-1 — Load / swap 3D model button (always visible)
            val modelLabel = when {
                assetLoading    -> "LOAD\n..."
                loadedAssetName != null -> "MODEL\nSWAP"
                else            -> "LOAD\nMODEL"
            }
            CtrlButton(
                label       = modelLabel,
                active      = loadedAssetName != null,
                accentColor = Color(0xFFFFD600),
                onClick     = { if (!assetLoading) onLoadModel() }
            )

            // BODY-2 — Body pose tracking toggle
            CtrlButton(
                label       = if (bodyTrackingEnabled) "BODY\nON" else "BODY\nOFF",
                active      = bodyTrackingEnabled,
                accentColor = Color(0xFF00BCD4),
                onClick     = onBodyToggle
            )

            // BODY-2 — Face landmark tracking toggle
            CtrlButton(
                label       = if (faceTrackingEnabled) "FACE\nON" else "FACE\nOFF",
                active      = faceTrackingEnabled,
                accentColor = Color(0xFFE91E63),
                onClick     = onFaceToggle
            )
        }
    }
}

@Composable
fun CtrlButton(
    label: String,
    active: Boolean,
    accentColor: Color = Plasma,
    onClick: () -> Unit
) {
    val borderColor = if (active) accentColor else Color.White.copy(alpha = 0.15f)
    val textColor   = if (active) accentColor else Color.White.copy(alpha = 0.5f)

    Column(
        modifier = Modifier
            .width(60.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(if (active) accentColor.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        label.split("\n").forEach { line ->
            Text(
                text = line,
                fontSize = 9.sp,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

val Cyan   = Color(0xFF00E5FF)
val OscGreen = Color(0xFF00E676)
val OscBlue  = Color(0xFF448AFF)   // G3: receive mode accent

/**
 * OSC streaming/receiving control row.
 *
 * Row 1 (always visible): SEND/RECEIVE mode toggle chips.
 * Row 2 (SEND mode):  schema selector chips + START/STOP button.
 * Row 2 (RECEIVE mode): port display + START/STOP button.
 *
 * G3: In RECEIVE mode the device listens for incoming hand pose bundles from
 * another Handy device on the LAN and renders the remote hand instead.
 *
 * @param isStreaming     Whether OSC is currently broadcasting (SEND mode).
 * @param isReceiving     Whether OSC receiver is active (RECEIVE mode).
 * @param activeSchema    The currently selected [OscSchema] (SEND mode).
 * @param oscMode         Current [OscMode]: SEND or RECEIVE.
 * @param receivePort     UDP port the receiver is (or will be) listening on.
 * @param onModeSelect    Called when the user taps a mode chip.
 * @param onSchemaSelect  Called when the user taps a schema chip (SEND mode only).
 * @param onToggle        Called to start/stop the active mode's stream or receiver.
 */
@Composable
fun OscPanel(
    isStreaming:    Boolean,
    isReceiving:    Boolean,
    activeSchema:   OscSchema,
    oscMode:        OscMode,
    receivePort:    Int,
    onModeSelect:   (OscMode) -> Unit,
    onSchemaSelect: (OscSchema) -> Unit,
    onToggle:       () -> Unit
) {
    val busy = isStreaming || isReceiving

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UIBg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ── Row 1: SEND / RECEIVE mode chips ──────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text       = "OSC",
                fontSize   = 8.sp,
                color      = Color.White.copy(alpha = 0.4f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.width(28.dp)
            )
            OscMode.entries.forEach { mode ->
                val active = mode == oscMode
                val accent = when {
                    active && mode == OscMode.SEND    && isStreaming -> OscGreen
                    active && mode == OscMode.RECEIVE && isReceiving -> OscBlue
                    active                                           ->
                        if (mode == OscMode.SEND) Cyan else OscBlue
                    else                                             ->
                        Color.White.copy(alpha = 0.15f)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, accent, RoundedCornerShape(4.dp))
                        .background(if (active) accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(enabled = !busy) { onModeSelect(mode) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = mode.label,
                        fontSize   = 8.sp,
                        color      = accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Row 2: schema / port + START-STOP ─────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (oscMode == OscMode.SEND) {
                // Schema chips
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OscSchema.entries.forEach { schema ->
                        val active = schema == activeSchema
                        val accent = if (isStreaming && active) OscGreen else Cyan
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .border(
                                    1.dp,
                                    if (active) accent else Color.White.copy(alpha = 0.15f),
                                    RoundedCornerShape(4.dp)
                                )
                                .background(if (active) accent.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable(enabled = !isStreaming) { onSchemaSelect(schema) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text       = schema.label,
                                fontSize   = 8.sp,
                                color      = if (active) accent else Color.White.copy(alpha = 0.4f),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                // START / STOP (send)
                val streamAccent = if (isStreaming) OscGreen else Cyan
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, streamAccent, RoundedCornerShape(6.dp))
                        .background(streamAccent.copy(alpha = 0.1f))
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = if (isStreaming) "STOP\nSEND" else "START\nSEND",
                        fontSize   = 8.sp,
                        color      = streamAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                // RECEIVE mode: show listening port
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val portAccent = if (isReceiving) OscBlue else Color.White.copy(alpha = 0.4f)
                    Text(
                        text       = "PORT",
                        fontSize   = 7.sp,
                        color      = Color.White.copy(alpha = 0.4f),
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text       = receivePort.toString(),
                        fontSize   = 9.sp,
                        color      = portAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    if (isReceiving) {
                        // Animated "LIVE" indicator
                        Text(
                            text       = "● LIVE",
                            fontSize   = 7.sp,
                            color      = OscBlue,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                // START / STOP (receive)
                val rcvAccent = if (isReceiving) OscBlue else Cyan
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, rcvAccent, RoundedCornerShape(6.dp))
                        .background(rcvAccent.copy(alpha = 0.1f))
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = if (isReceiving) "STOP\nRCV" else "START\nRCV",
                        fontSize   = 8.sp,
                        color      = rcvAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
