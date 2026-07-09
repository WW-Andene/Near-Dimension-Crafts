package com.arhand.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arhand.scanner.PhotometricStereoCapture
import com.arhand.feature.scan.NeuralReconDiagnostics

/**
 * Phase 2 placeholder — ARCore Depth API panel.
 * Shows real-time depth confidence and toggle for depth mesh overlay.
 * Active only when ARCore depth is available.
 *
 * G2 — Also exposes the neural implicit reconstruction toggle.
 * G5 — Also exposes the photometric stereo flash capture toggle.
 */
@Composable
fun DepthScannerPanel(
    depthMode: Boolean,
    depthConfidence: Float,
    onToggleDepth: () -> Unit,
    depthApiAvailable: Boolean = false,
    // G2 params
    neuralReconEnabled: Boolean = false,
    neuralDiagnostics: NeuralReconDiagnostics? = null,
    onToggleNeuralRecon: () -> Unit = {},
    // G5 params
    photoStereoEnabled: Boolean = false,
    photoStereoFrameCount: Int = 0,
    photoStereoComplete: Boolean = false,
    isFrontCamera: Boolean = false,
    onTogglePhotoStereo: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UIBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── ARCore Depth row ──────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("DEPTH API", fontSize = 9.sp, color = Color.White.copy(0.4f),
                    fontFamily = FontFamily.Monospace)
                Text(
                    text = when {
                        isFrontCamera      -> "REAR ONLY"
                        !depthApiAvailable -> "SFM ONLY"
                        depthMode          -> "TSDF ON"
                        else               -> "TSDF OFF"
                    },
                    fontSize = 12.sp,
                    color = when {
                        isFrontCamera      -> Color.White.copy(0.2f)
                        !depthApiAvailable -> Color.White.copy(0.2f)
                        depthMode          -> Plasma
                        else               -> Color.White.copy(0.3f)
                    },
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            }

            if (depthMode) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("CONFIDENCE", fontSize = 9.sp, color = Color.White.copy(0.4f),
                        fontFamily = FontFamily.Monospace)
                    Text("%.0f%%".format(depthConfidence * 100f), fontSize = 12.sp, color = Plasma,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            CtrlButton(
                label = if (depthMode) "DEPTH\nON" else "DEPTH\nOFF",
                active = depthMode,
                onClick = if (!isFrontCamera) onToggleDepth else ({})
            )
        }

        // ── G2: Neural Recon row ──────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("NEURAL RECON", fontSize = 9.sp, color = Color.White.copy(0.4f),
                    fontFamily = FontFamily.Monospace)
                Text(
                    text = when {
                        neuralReconEnabled && neuralDiagnostics != null ->
                            "LOSS %.3f".format(neuralDiagnostics.trainLoss)
                        neuralReconEnabled -> "ENABLED"
                        else -> "CAPSULE SDF"
                    },
                    fontSize = 12.sp,
                    color = if (neuralReconEnabled) Plasma else Color.White.copy(0.3f),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
                if (neuralReconEnabled && neuralDiagnostics != null) {
                    Text(
                        text = "${neuralDiagnostics.meshVertexCount / 3} TRI · ${neuralDiagnostics.trainEpochs} EP",
                        fontSize = 9.sp, color = Color.White.copy(0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            CtrlButton(
                label = if (neuralReconEnabled) "MLP\nON" else "MLP\nOFF",
                active = neuralReconEnabled,
                onClick = onToggleNeuralRecon
            )
        }

        // ── G5: Photometric Stereo row ────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("PHOTO STEREO", fontSize = 9.sp, color = Color.White.copy(0.4f),
                    fontFamily = FontFamily.Monospace)
                Text(
                    text = when {
                        isFrontCamera            -> "REAR ONLY"
                        photoStereoComplete      -> "DONE · ${PhotometricStereoCapture.TARGET_PAIRS} PAIRS"
                        photoStereoEnabled && photoStereoFrameCount > 0 ->
                            "$photoStereoFrameCount / ${PhotometricStereoCapture.TARGET_PAIRS}"
                        photoStereoEnabled       -> "READY"
                        else                     -> "OFF"
                    },
                    fontSize = 12.sp,
                    color = when {
                        isFrontCamera        -> Color.White.copy(0.2f)
                        photoStereoComplete  -> Plasma
                        photoStereoEnabled   -> Color(0xFF4FC3F7)
                        else                 -> Color.White.copy(0.3f)
                    },
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
                if (photoStereoEnabled && !isFrontCamera) {
                    Text(
                        text = "TORCH FLASH · NORMAL MAP",
                        fontSize = 9.sp, color = Color.White.copy(0.4f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            CtrlButton(
                label = if (photoStereoEnabled && !isFrontCamera) "STEREO\nON" else "STEREO\nOFF",
                active = photoStereoEnabled && !isFrontCamera,
                onClick = onTogglePhotoStereo
            )
        }
    }
}
