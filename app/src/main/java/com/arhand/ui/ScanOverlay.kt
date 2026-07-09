package com.arhand.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.window.Dialog
import com.arhand.scanner.Scanner

/**
 * Full-screen scan overlay — rendered on top of the AR view during scanning.
 * Shows: pose name, instruction, quality bar, progress rings, countdown.
 */
@Composable
fun ScanOverlay(status: Scanner.ScanStatus, onCancel: () -> Unit) {
    // E2: Track whether the cancel confirmation dialog is visible
    var showCancelDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (status.state) {
            Scanner.ScanState.PREFLIGHT  -> PreflightOverlay(status.preflightScore, status.preflightProgress)
            Scanner.ScanState.COUNTDOWN  -> CountdownOverlay(status.countdownSec)
            Scanner.ScanState.CAPTURING  -> CaptureOverlay(status)
            Scanner.ScanState.PROCESSING -> ProcessingOverlay()
            Scanner.ScanState.FAILED     -> FailedOverlay(status.failureReason, onCancel)
            else -> {}
        }

        // Cancel button — always visible during scan (including pre-flight)
        if (status.state != Scanner.ScanState.DONE && status.state != Scanner.ScanState.IDLE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 48.dp, end = 16.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                // E2: Show confirmation dialog if past pose 2, else cancel immediately
                ScanButton("✕ CANCEL", accentColor = Warn, onClick = {
                    if (status.poseIndex >= 2) showCancelDialog = true else onCancel()
                })
            }
        }

        // E2: Confirmation dialog
        if (showCancelDialog) {
            Dialog(onDismissRequest = { showCancelDialog = false }) {
                Column(
                    modifier = Modifier
                        .background(UIBg, RoundedCornerShape(16.dp))
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "CANCEL SCAN?",
                        fontSize = 15.sp, color = Warn,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Progress will be lost.",
                        fontSize = 12.sp, color = Color.White.copy(0.7f),
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ScanButton("KEEP GOING", accentColor = Plasma, onClick = { showCancelDialog = false })
                        ScanButton("CANCEL", accentColor = Warn, onClick = { showCancelDialog = false; onCancel() })
                    }
                }
            }
        }
    }
}

/**
 * E5 — Pre-flight quality check overlay.
 * Shown during the [Scanner.ScanState.PREFLIGHT] phase while 30 frames are evaluated.
 * Displays a live quality bar and a progress indicator. If quality is too low the scan
 * will automatically fail with an actionable reason message.
 */
@Composable
fun PreflightOverlay(qualityScore: Float, progress: Float) {
    val qualityOk = qualityScore >= 0.40f
    val barColor  = if (qualityOk) Plasma else Warn

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .background(UIBg, RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .widthIn(min = 240.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "CHECKING HAND POSITION",
                fontSize = 12.sp, color = Color.White.copy(0.7f),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))

            // Quality bar
            Text(
                "QUALITY: ${(qualityScore * 100).toInt()}%",
                fontSize = 10.sp,
                color = barColor,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { qualityScore.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = Color.White.copy(0.1f)
            )
            Spacer(Modifier.height(12.dp))

            // Frame evaluation progress
            Text(
                "EVALUATING…",
                fontSize = 9.sp, color = Color.White.copy(0.4f),
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Color.White.copy(0.3f),
                trackColor = Color.White.copy(0.08f)
            )

            if (!qualityOk && qualityScore > 0f) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Move hand to centre\nor improve lighting",
                    fontSize = 11.sp, color = Warn.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun CountdownOverlay(sec: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = sec.toString(),
                fontSize = (64 * scale).sp,
                color = Plasma,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text("POSITION YOUR HAND", fontSize = 12.sp, color = Color.White.copy(0.7f),
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun CaptureOverlay(status: Scanner.ScanStatus) {
    // E1: Resolve pose drawable at runtime using the pose id convention
    val context = LocalContext.current
    val poseDrawableRes = remember(status.pose.id) {
        context.resources.getIdentifier(
            "hand_pose_${status.pose.id}", "drawable", context.packageName
        ).takeIf { it != 0 }
    }

    Box(Modifier.fillMaxSize()) {
        // Top info bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp)
                .background(UIBg, RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "POSE ${status.poseIndex + 1} / 8",
                fontSize = 10.sp, color = Color.White.copy(0.5f),
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = status.pose.label.uppercase(),
                fontSize = 18.sp, color = Plasma,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
            // E1: Pose silhouette drawable
            if (poseDrawableRes != null) {
                Spacer(Modifier.height(8.dp))
                Image(
                    painter = painterResource(id = poseDrawableRes),
                    contentDescription = status.pose.label,
                    modifier = Modifier.size(64.dp),
                    colorFilter = ColorFilter.tint(Plasma.copy(alpha = 0.85f))
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = status.pose.instruction,
                fontSize = 12.sp, color = Color.White.copy(0.8f),
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
            )
        }

        // Bottom progress section
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Quality bar
            Text("QUALITY", fontSize = 9.sp, color = Color.White.copy(0.4f),
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { status.quality },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = if (status.quality >= 0.55f) Plasma else Warn,
                trackColor = Color.White.copy(0.1f)
            )
            Spacer(Modifier.height(12.dp))
            // Hold / rep progress
            LinearProgressIndicator(
                progress = { status.holdProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Plasma,
                trackColor = Color.White.copy(0.1f)
            )
            Spacer(Modifier.height(8.dp))
            // 8-pose pip row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until 8) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    i < status.poseIndex  -> Plasma
                                    i == status.poseIndex -> Plasma.copy(alpha = 0.6f)
                                    else                  -> Color.White.copy(alpha = 0.15f)
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun ProcessingOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "proc")
    val alpha by infiniteTransition.animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("BUILDING MODEL", fontSize = 14.sp, color = Plasma.copy(alpha = alpha),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Running marching cubes...", fontSize = 11.sp, color = Color.White.copy(0.5f),
                fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * E3 — Failed scan feedback.
 * Shown when [Scanner.ScanState.FAILED] is emitted. Displays the failure reason
 * (if available) and a "Try Again" button that resets back to IDLE.
 */
@Composable
fun FailedOverlay(reason: String?, onTryAgain: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .background(UIBg, RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "⚠ SCAN FAILED",
                fontSize = 16.sp, color = Warn,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = reason?.takeIf { it.isNotBlank() } ?: "An unexpected error occurred.",
                fontSize = 12.sp, color = Color.White.copy(0.75f),
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            ScanButton("↺ TRY AGAIN", accentColor = Plasma, onClick = onTryAgain)
        }
    }
}


@Composable
fun ScanButton(label: String, accentColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, accentColor, RoundedCornerShape(8.dp))
            .background(accentColor.copy(0.1f), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 11.sp, color = accentColor,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}
