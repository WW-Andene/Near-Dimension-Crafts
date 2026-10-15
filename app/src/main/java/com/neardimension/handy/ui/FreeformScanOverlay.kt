package com.arhand.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.arhand.scanner.FreeformScanner
import kotlin.math.PI

/**
 * LIMIT-2 — Full-screen overlay for the continuous freeform scan mode.
 *
 * Shows:
 *   - Polar coverage ring: 32-segment arc grid showing which viewing angles are captured
 *   - Quality bar: current frame quality score
 *   - Motion indicator: green = good motion speed, amber = too slow, red = too fast
 *   - Frame count and elapsed time
 *   - FINISH button (enabled after minimum coverage threshold)
 *   - CANCEL button with confirmation dialog
 */
@Composable
fun FreeformScanOverlay(
    status:   FreeformScanner.FreeformStatus,
    onFinish: () -> Unit,
    onCancel: () -> Unit
) {
    var showCancelDialog by remember { mutableStateOf(false) }
    val canFinish = status.frameCount >= FreeformScanner.MIN_FRAMES_FOR_MESH

    Box(modifier = Modifier.fillMaxSize()) {

        when (status.state) {
            FreeformScanner.State.ACTIVE   -> FreeformActiveOverlay(status, canFinish, onFinish,
                                                 onCancelRequest = { showCancelDialog = true })
            FreeformScanner.State.COMPLETE -> ProcessingOverlay()   // reuse posed scan composable
            FreeformScanner.State.FAILED   -> FailedOverlay(status.failureReason, onCancel)
            else                           -> {}
        }

        if (showCancelDialog) {
            Dialog(onDismissRequest = { showCancelDialog = false }) {
                Column(
                    modifier = Modifier
                        .background(UIBg, RoundedCornerShape(16.dp))
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("CANCEL SCAN?", fontSize = 15.sp, color = Warn,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("${status.frameCount} frames will be lost.",
                        fontSize = 12.sp, color = Color.White.copy(0.7f),
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ScanButton("KEEP GOING", accentColor = Plasma,
                            onClick = { showCancelDialog = false })
                        ScanButton("CANCEL", accentColor = Warn,
                            onClick = { showCancelDialog = false; onCancel() })
                    }
                }
            }
        }
    }
}

@Composable
private fun FreeformActiveOverlay(
    status:       FreeformScanner.FreeformStatus,
    canFinish:    Boolean,
    onFinish:     () -> Unit,
    onCancelRequest: () -> Unit
) {
    val coveragePct = (status.coveragePercent * 100).toInt()
    val elapsed     = status.elapsedSec.toInt()
    val remaining   = status.remainingSec.toInt()

    // Motion status colour: green = in range, amber = too slow, red = not assessed
    val motionColor = when {
        status.motionOk              -> Color(0xFF00C853)   // good speed
        status.quality > 0f          -> Color(0xFFFFAB00)   // quality ok but motion gate failed
        else                         -> Color.White.copy(0.3f)
    }

    // Coverage ring pulse when a new segment is filled
    val ringAlpha by animateFloatAsState(
        targetValue = if (status.coveragePercent > 0f) 1f else 0.5f,
        animationSpec = tween(300),
        label = "ringAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Coverage ring — top centre ────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp),
            contentAlignment = Alignment.Center
        ) {
            CoverageRing(
                coveragePercent = status.coveragePercent,
                alpha           = ringAlpha,
                modifier        = Modifier.size(120.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$coveragePct%",
                    fontSize = 20.sp, color = Plasma,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
                Text(
                    "COVERAGE",
                    fontSize = 8.sp, color = Color.White.copy(0.5f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // ── Instruction ───────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 184.dp)
                .background(UIBg, RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                "Slowly rotate your hand through all angles",
                fontSize = 12.sp, color = Color.White.copy(0.85f),
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
            )
        }

        // ── Bottom metrics + buttons ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 108.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Quality bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("QUALITY", fontSize = 8.sp, color = Color.White.copy(0.4f),
                    fontFamily = FontFamily.Monospace)
                LinearProgressIndicator(
                    progress = { status.quality.coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color    = if (status.quality >= FreeformScanner.FREEFORM_MIN_QUALITY) Plasma else Warn,
                    trackColor = Color.White.copy(0.1f)
                )
                Text("${(status.quality * 100).toInt()}%", fontSize = 8.sp,
                    color = Color.White.copy(0.5f), fontFamily = FontFamily.Monospace)
            }

            // Motion indicator row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("MOTION", fontSize = 8.sp, color = Color.White.copy(0.4f),
                    fontFamily = FontFamily.Monospace)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(motionColor.copy(0.25f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(if (status.motionOk) 1f else 0.3f)
                            .background(motionColor)
                    )
                }
                Text(
                    when {
                        status.motionOk     -> "OK"
                        status.quality > 0f -> "SLOW"
                        else                -> "--"
                    },
                    fontSize = 8.sp, color = motionColor, fontFamily = FontFamily.Monospace
                )
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${status.frameCount} frames",
                    fontSize = 9.sp, color = Color.White.copy(0.5f),
                    fontFamily = FontFamily.Monospace)
                Text("${elapsed}s / ${FreeformScanner.MAX_DURATION_SEC.toInt()}s",
                    fontSize = 9.sp, color = Color.White.copy(0.5f),
                    fontFamily = FontFamily.Monospace)
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // CANCEL
                Box(
                    modifier = Modifier
                        .border(1.dp, Warn.copy(0.6f), RoundedCornerShape(8.dp))
                        .background(Warn.copy(0.08f), RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onCancelRequest)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("✕ CANCEL", fontSize = 11.sp, color = Warn,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                // FINISH — enabled once minimum frame count is reached
                val finishColor = if (canFinish) Plasma else Color.White.copy(0.2f)
                Box(
                    modifier = Modifier
                        .border(1.dp, finishColor, RoundedCornerShape(8.dp))
                        .background(finishColor.copy(0.1f), RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = canFinish, onClick = onFinish)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("✓ FINISH", fontSize = 11.sp, color = finishColor,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * 32-segment polar coverage ring.
 *
 * Each segment corresponds to one of the 32 viewpoint buckets
 * (8 azimuth × 4 elevation) tracked by [FreeformScanner]. Filled segments
 * are drawn in Plasma; empty segments are drawn faintly. The ring grows as
 * the user rotates their hand to show which angles are still missing.
 *
 * Implementation note: we approximate the 32 buckets as 32 equal arc slices.
 * Perfect bucket-to-arc mapping requires knowing the individual bucket states,
 * which [FreeformScanner.FreeformStatus] doesn't expose directly. The coverage
 * fraction drives a contiguous filled arc from the top — a simple and clear
 * visual without requiring bucket-level public state on the scanner.
 */
@Composable
private fun CoverageRing(
    coveragePercent: Float,
    alpha:           Float,
    modifier:        Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth  = 8.dp.toPx()
        val radius       = (size.minDimension / 2f) - strokeWidth / 2f
        val center       = Offset(size.width / 2f, size.height / 2f)
        val totalBuckets = FreeformScanner.TOTAL_BUCKETS   // 32
        val sliceDeg     = 360f / totalBuckets
        val filledCount  = (coveragePercent * totalBuckets).toInt()
        val gapDeg       = 2f   // small gap between segments

        for (i in 0 until totalBuckets) {
            val startAngle = -90f + i * sliceDeg + gapDeg / 2f
            val sweepAngle = sliceDeg - gapDeg
            val filled     = i < filledCount
            val color      = if (filled) Plasma.copy(alpha = alpha)
                             else Color.White.copy(alpha = 0.12f * alpha)
            drawArc(
                color      = color,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter  = false,
                topLeft    = Offset(center.x - radius, center.y - radius),
                size       = Size(radius * 2, radius * 2),
                style      = Stroke(width = strokeWidth)
            )
        }
    }
}
