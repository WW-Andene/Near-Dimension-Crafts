package com.arhand.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arhand.mocap.OscSchema
import com.arhand.feature.record.TakeEntry

/**
 * Gap 1 — Persistent settings screen.
 *
 * Covers all configuration that needs to survive sessions:
 *  - OSC target host and port
 *  - OSC schema preset
 *  - Body and face tracking toggles
 *  - Biomechanical constraint filter toggle
 *  - Recording cap duration
 *  - OEF recalibrate trigger
 *
 * Gap 7 — Accessible from a tab in the mode-separated ControlPanel
 * (SCAN | STREAM | RECORD | SETTINGS).
 */
@Composable
fun SettingsScreen(
    // Current values
    oscHost:           String,
    oscPort:           Int,
    oscSchema:         OscSchema,
    bodyEnabled:       Boolean,
    faceEnabled:       Boolean,
    constraintEnabled: Boolean,
    isStreaming:       Boolean,
    currentTakeLabel:  String,
    takes:             List<TakeEntry>,

    // Callbacks
    onOscHostChange:        (String) -> Unit,
    onOscPortChange:        (Int) -> Unit,
    onSchemaChange:         (OscSchema) -> Unit,
    onBodyToggle:           () -> Unit,
    onFaceToggle:           () -> Unit,
    onConstraintToggle:     () -> Unit,
    onRecalibrateOef:       () -> Unit,
    onTakeLabelChange:      (String) -> Unit,
    onDismiss:              () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080A0F))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text       = "SETTINGS",
                    fontSize   = 13.sp,
                    color      = Plasma,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(0.06f))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("DONE", fontSize = 10.sp, color = Color.White.copy(0.5f),
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            // ── OSC ───────────────────────────────────────────────────────────
            SettingsSection("OSC OUTPUT") {
                // Schema selector
                Text("Schema", fontSize = 9.sp, color = Color.White.copy(0.4f),
                    fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OscSchema.entries.forEach { schema ->
                        val active = schema == oscSchema
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp,
                                    if (active) Plasma else Color.White.copy(0.15f),
                                    RoundedCornerShape(4.dp))
                                .background(if (active) Plasma.copy(0.12f) else Color.Transparent)
                                .clickable(enabled = !isStreaming) { onSchemaChange(schema) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(schema.label, fontSize = 9.sp,
                                color = if (active) Plasma else Color.White.copy(0.4f),
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Host field
                SettingsTextField(
                    label       = "Target IP",
                    value       = oscHost,
                    placeholder = "192.168.1.x",
                    enabled     = !isStreaming,
                    keyboard    = KeyboardType.Uri,
                    onValueChange = onOscHostChange
                )

                // Port field
                SettingsTextField(
                    label       = "Port",
                    value       = if (oscPort > 0) oscPort.toString() else "",
                    placeholder = "9000",
                    enabled     = !isStreaming,
                    keyboard    = KeyboardType.Number,
                    onValueChange = { s -> s.toIntOrNull()?.let { onOscPortChange(it) } }
                )

                if (isStreaming) {
                    Text(
                        "Stop streaming to edit OSC settings.",
                        fontSize = 8.sp,
                        color    = Warn,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // ── Tracking ──────────────────────────────────────────────────────
            SettingsSection("TRACKING") {
                SettingsToggle("Body pose tracking",  bodyEnabled,      onBodyToggle)
                SettingsToggle("Face expressions",    faceEnabled,      onFaceToggle)
                SettingsToggle("Biomechanical constraints", constraintEnabled, onConstraintToggle)

                Spacer(Modifier.height(4.dp))
                Text(
                    "Constraints clip anatomically impossible joint angles.\nOff = raw retarget output.",
                    fontSize = 8.sp, color = Color.White.copy(0.3f),
                    fontFamily = FontFamily.Monospace
                )
            }

            // ── Filter calibration ────────────────────────────────────────────
            SettingsSection("SMOOTHING") {
                Text(
                    "OEF parameters are calibrated automatically after each scan.\n" +
                    "Use this to re-run calibration with the last scan data.",
                    fontSize = 8.sp, color = Color.White.copy(0.3f),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                SettingsButton("RECALIBRATE FILTER", onClick = onRecalibrateOef)
            }

            // ── Recording ─────────────────────────────────────────────────────
            SettingsSection("RECORDING") {
                SettingsTextField(
                    label         = "Take label",
                    value         = currentTakeLabel,
                    placeholder   = "Take 1",
                    enabled       = true,
                    keyboard      = KeyboardType.Text,
                    onValueChange = onTakeLabelChange
                )

                if (takes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("TAKES THIS SESSION", fontSize = 8.sp,
                        color = Color.White.copy(0.3f), fontFamily = FontFamily.Monospace)
                    takes.reversed().forEach { take ->
                        TakeRow(take)
                    }
                }
            }
        }
    }
}

// ── Sub-components ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text       = title,
            fontSize   = 8.sp,
            color      = Color.White.copy(alpha = 0.3f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.padding(bottom = 2.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(0.04f))
                .border(1.dp, Color.White.copy(0.06f), RoundedCornerShape(10.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = Color.White.copy(0.8f),
            fontFamily = FontFamily.Monospace)
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor  = Plasma,
                checkedTrackColor  = Plasma.copy(0.3f),
                uncheckedTrackColor = Color.White.copy(0.1f)
            )
        )
    }
}

@Composable
private fun SettingsTextField(
    label:         String,
    value:         String,
    placeholder:   String,
    enabled:       Boolean,
    keyboard:      KeyboardType,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 8.sp, color = Color.White.copy(0.4f),
            fontFamily = FontFamily.Monospace)
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            enabled       = enabled,
            textStyle     = TextStyle(
                color      = if (enabled) Color.White else Color.White.copy(0.35f),
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            singleLine      = true,
            decorationBox   = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(if (enabled) 0.06f else 0.02f))
                        .border(1.dp, Color.White.copy(if (enabled) 0.12f else 0.04f),
                            RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 11.sp,
                            color = Color.White.copy(0.2f), fontFamily = FontFamily.Monospace)
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
private fun SettingsButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Plasma.copy(0.5f), RoundedCornerShape(6.dp))
            .background(Plasma.copy(0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 9.sp, color = Plasma,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TakeRow(take: TakeEntry) {
    val durSec = take.durationMs / 1000
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(take.label, fontSize = 10.sp, color = Color.White.copy(0.8f),
                fontFamily = FontFamily.Monospace)
            Text("${take.frameCount} frames  •  ${durSec}s",
                fontSize = 8.sp, color = Color.White.copy(0.3f),
                fontFamily = FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (take.bvhPath != null) {
                Text("BVH", fontSize = 8.sp, color = Color(0xFF00E676),
                    fontFamily = FontFamily.Monospace)
            }
            if (take.gltfPath != null) {
                Text("GLB", fontSize = 8.sp, color = Color(0xFF00BCD4),
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}
