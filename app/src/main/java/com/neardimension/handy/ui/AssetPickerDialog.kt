package com.neardimension.handy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.window.Dialog
import com.neardimension.handy.mocap.AssetLoader
import java.io.File

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
                // CC BY 4.0 attribution for the bundled body model (required by its licence).
                Text(
                    text       = AssetLoader.Bundled.BODY_CREDIT,
                    fontSize   = 8.sp,
                    color      = Color.White.copy(alpha = 0.4f),
                    fontFamily = FontFamily.Monospace
                )

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
