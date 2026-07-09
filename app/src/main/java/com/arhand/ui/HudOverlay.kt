package com.arhand.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arhand.util.PerfState
import com.arhand.util.ModelBudgetManager
import com.arhand.tracking.Gesture

/**
 * Top-right HUD overlay — FPS / inference ms / skip ratio.
 *
 * E4 — Debug mode toggle:
 * Long-press the HUD area to cycle between MINIMAL (hidden) and VERBOSE mode.
 * VERBOSE adds landmark count, OEF alpha, slot assignments.
 * Gated by BuildConfig.DEBUG at runtime.
 */
enum class HudMode { MINIMAL, VERBOSE }

@Composable
fun HudOverlay(
    perf: PerfState,
    renderMode: String,
    torchOn: Boolean,
    landmarkCount: Int = 0,
    activeSlots: String = "--",
    activeGesture: Gesture? = null,
    handSide: com.arhand.tracking.HandSide = com.arhand.tracking.HandSide.UNKNOWN,
    /** Gap 3 — OSC health for connection status indicator. */
    oscHealth: com.arhand.mocap.OscHealthState = com.arhand.mocap.OscHealthState(),
    /** Spatial layer state — always-on depth sensing status. */
    spatialState: com.arhand.depth.SpatialLayer.SpatialState = com.arhand.depth.SpatialLayer.SpatialState(),
    /** Fused-sl-v7: SL calibration progress 0..1, or -1 when SL inactive. */
    slCalibProgress: Float = -1f,
    slDrifting: Boolean = false
) {
    // E4: Long-press cycles MINIMAL → VERBOSE → MINIMAL
    var hudMode by remember { mutableStateOf(HudMode.VERBOSE) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    hudMode = if (hudMode == HudMode.VERBOSE) HudMode.MINIMAL else HudMode.VERBOSE
                })
            }
    ) {
        if (hudMode == HudMode.VERBOSE) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 16.dp),
                horizontalAlignment = Alignment.End
            ) {
                HudLabel("FPS",  "%.0f".format(perf.fps),  if (perf.fps < 30f) Warn else Plasma)
                HudLabel("INF",  "%.1fms".format(perf.inferenceMs), Plasma)
                HudLabel("SKIP", "%.0f%%".format(perf.skipRatio * 100f), Plasma)

                // Spatial layer status — always shown, colour-coded by grounding state
                val spatialColor = when {
                    spatialState.isGrounded  -> Color(0xFF00E676)
                    spatialState.depthActive -> Color(0xFFFFAB00)
                    else                     -> Color.White.copy(0.3f)
                }
                val spatialLabel = when {
                    spatialState.isGrounded  -> "MTR %.0f%%".format(spatialState.depthConfidence * 100f)
                    spatialState.depthActive -> "SFM"
                    else                     -> "OFF"
                }
                HudLabel("DEPTH", spatialLabel, spatialColor)

                // Fused-sl-v7: SL calibration status row
                if (slCalibProgress >= 0f) {
                    val slColor = when {
                        slDrifting           -> Warn
                        slCalibProgress >= 1f -> Color(0xFFFF9FFF)  // calibrated — purple
                        else                 -> Color(0xFFFFAB00)   // calibrating — amber
                    }
                    val slLabel = when {
                        slDrifting            -> "DRIFT"
                        slCalibProgress >= 1f -> "LIVE"
                        else                  -> "CAL %.0f%%".format(slCalibProgress * 100f)
                    }
                    HudLabel("SL", slLabel, slColor)
                }

                // Gap 3 — OSC health indicator (shown whenever streaming has been active)
                if (oscHealth.sentFrames > 0 || oscHealth.isHealthy) {
                    val oscColor = when {
                        !oscHealth.isHealthy              -> Warn
                        oscHealth.dropRate > 0.05f        -> Warn
                        oscHealth.frameRateFps >= 25f     -> Color(0xFF00E676)
                        else                              -> Plasma
                    }
                    val oscLabel = if (oscHealth.isHealthy)
                        "%.0ffps".format(oscHealth.frameRateFps)
                    else "STALE"
                    HudLabel("OSC", oscLabel, oscColor)
                    if (oscHealth.droppedFrames > 0) {
                        HudLabel("DROP", "${oscHealth.droppedFrames}", Warn)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HudLabel("MODE",  renderMode, Plasma)
                HudLabel("TORCH", if (torchOn) "ON" else "OFF", if (torchOn) Plasma else Warn)
                // E4: Verbose-only stats
                Spacer(Modifier.height(8.dp))
                HudLabel("LMS",   landmarkCount.toString(), Plasma.copy(alpha = 0.7f))
                HudLabel("SLOTS", activeSlots,              Plasma.copy(alpha = 0.7f))
                // Hand side
                val sideColor = when (handSide) {
                    com.arhand.tracking.HandSide.PALM    -> Color(0xFF00E676)
                    com.arhand.tracking.HandSide.BACK    -> Color(0xFF448AFF)
                    com.arhand.tracking.HandSide.UNKNOWN -> Plasma.copy(alpha = 0.4f)
                }
                HudLabel("SIDE", handSide.name, sideColor)
                // G4: Active gesture display
                val gestureColor = if (activeGesture != null) Color(0xFF00E676) else Plasma.copy(alpha = 0.4f)
                HudLabel(
                    "GESTURE",
                    activeGesture?.let { "${it.symbol} ${it.label}" } ?: "--",
                    gestureColor
                )

                // ARCH-4 — Pipeline quality and budget metrics
                Spacer(Modifier.height(8.dp))
                HudLabel("HAND", "%.0f%%".format(perf.handConfidence * 100f),
                    if (perf.handConfidence > 0.7f) Color(0xFF00E676) else Warn)
                if (perf.bodyConfidence > 0f || perf.bodyThrottled) {
                    val bodyColor = when {
                        perf.bodyThrottled   -> Warn
                        perf.bodyConfidence > 0.6f -> Color(0xFF00BCD4)
                        else -> Warn
                    }
                    HudLabel("BODY", "%.0f%%%s".format(
                        perf.bodyConfidence * 100f,
                        if (perf.bodyThrottled) "↓" else ""
                    ), bodyColor)
                }
                if (perf.faceConfidence > 0f || perf.faceThrottled) {
                    val faceColor = when {
                        perf.faceThrottled   -> Warn
                        perf.faceConfidence > 0.5f -> Color(0xFFE91E63)
                        else -> Warn
                    }
                    HudLabel("FACE", "%.0f%%%s".format(
                        perf.faceConfidence * 100f,
                        if (perf.faceThrottled) "↓" else ""
                    ), faceColor)
                }
                if (perf.activeModelCount > 1) {
                    HudLabel("MDLS", "${perf.activeModelCount} / %.1fms".format(perf.totalInferenceMs),
                        if (perf.totalInferenceMs > ModelBudgetManager.FRAME_BUDGET_MS) Warn else Plasma.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
fun HudLabel(key: String, value: String, color: Color) {
    Row(horizontalArrangement = Arrangement.End) {
        Text(
            text = "$key ",
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.4f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = value,
            fontSize = 10.sp,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

