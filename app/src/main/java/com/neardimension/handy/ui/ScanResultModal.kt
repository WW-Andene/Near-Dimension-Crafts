package com.arhand.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arhand.scanner.BiometricHistoryEntry
import com.arhand.scanner.HandBiometrics
import com.arhand.scanner.JointRomData
import com.arhand.scanner.JointRom
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Scan result modal — shown when scan completes with GLB export path and stats.
 * Port of the "Export Modal" from the HTML prototype.
 *
 * Includes a "VIEW 3D" button that pushes a full-screen [ModelViewerScreen]
 * over the modal when [meshPositions] is non-empty.
 */
@Composable
fun ScanResultModal(
    pointCount: Int,
    glbPath: String?,
    poseScores: List<Float>,
    meshPositions: FloatArray,
    biometrics: HandBiometrics?,
    biometricHistory: List<BiometricHistoryEntry> = emptyList(),
    jointRomData: JointRomData? = null,
    onShare: () -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
    /** GAP-5 — Trigger credit-card calibration flow. Null if calibration not available. */
    onCalibrate: (() -> Unit)? = null,
    /** ARCH-3 — Live retarget result forwarded to [ModelViewerScreen]. */
    retargetResult: com.arhand.mocap.RetargetResult? = null,
    /** ARCH-3 — Loaded skinned asset forwarded to [ModelViewerScreen]. */
    loadedAsset: com.arhand.mocap.LoadedAsset? = null
) {
    var showViewer by remember { mutableStateOf(false) }

    if (showViewer) {
        ModelViewerScreen(
            meshPositions  = meshPositions,
            onDismiss      = { showViewer = false },
            retargetResult = retargetResult,
            loadedAsset    = loadedAsset
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .background(Color(0xFF0D1117), RoundedCornerShape(16.dp))
                .border(1.dp, Plasma.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SCAN COMPLETE", fontSize = 16.sp, color = Plasma,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            // Stats grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCell("POINTS", pointCount.toString())
                StatCell("POSES",  "8 / 8")
                StatCell("QUALITY", if (poseScores.isEmpty()) "—" else "%.0f%%".format(poseScores.average() * 100f))
            }

            Spacer(Modifier.height(16.dp))

            // Pose quality bar strip
            Text("POSE QUALITY", fontSize = 9.sp, color = Color.White.copy(0.4f),
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                poseScores.forEach { score ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Plasma.copy(alpha = score.coerceIn(0f, 1f)))
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Hand biometrics panel
            if (biometrics != null) {
                BiometricsPanel(biometrics, biometricHistory, jointRomData)
                Spacer(Modifier.height(20.dp))
            }

            // Export path
            if (glbPath != null) {
                Text(
                    text = "GLB saved to Documents",
                    fontSize = 10.sp, color = Color.White.copy(0.5f),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(12.dp))
            }

            // Primary actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ResultButton("SHARE GLB", Modifier.weight(1f), Plasma, onShare)
                ResultButton("RE-SCAN",   Modifier.weight(1f), Color.White.copy(0.6f), onRescan)
            }

            // 3D viewer — shown only when mesh data is available
            if (meshPositions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ResultButton(
                    label   = "VIEW 3D",
                    modifier = Modifier.fillMaxWidth(),
                    color   = Plasma.copy(alpha = 0.75f),
                    onClick = { showViewer = true }
                )
            }

            // GAP-5 — Calibration card entry point
            if (onCalibrate != null) {
                Spacer(Modifier.height(4.dp))
                ResultButton(
                    label    = "CALIBRATE SCALE",
                    modifier = Modifier.fillMaxWidth(),
                    color    = Color(0xFF4FC3F7).copy(alpha = 0.75f),
                    onClick  = onCalibrate
                )
            }

            Spacer(Modifier.height(8.dp))
            ResultButton("CLOSE", Modifier.fillMaxWidth(), Color.White.copy(0.3f), onDismiss)
        }
    }
}

@Composable
fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, color = Plasma,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 9.sp, color = Color.White.copy(0.4f),
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ResultButton(label: String, modifier: Modifier, color: Color, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 11.sp, color = color,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hand Biometrics Panel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Displays the computed hand biometrics in a compact monospace grid.
 * All values are shown in mm using [HandBiometrics.fmtMm].
 */
@Composable
fun BiometricsPanel(b: HandBiometrics, history: List<BiometricHistoryEntry> = emptyList(), romData: JointRomData? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Plasma.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // Section header
        Text(
            text = "HAND BIOMETRICS",
            fontSize = 9.sp,
            color = Plasma.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )

        Spacer(Modifier.height(10.dp))

        // Top row: palm width, hand span, wrist
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BiometricCell("PALM WIDTH",  HandBiometrics.fmtMm(b.palmWidth))
            BiometricCell("HAND SPAN",   HandBiometrics.fmtMm(b.handSpan))
            BiometricCell("WRIST CIRC",  HandBiometrics.fmtMm(b.wristCircumference))
        }

        Spacer(Modifier.height(10.dp))

        // Divider
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.07f))
        )

        Spacer(Modifier.height(10.dp))

        // Finger lengths row
        Text(
            text = "FINGER LENGTHS",
            fontSize = 8.sp,
            color = Color.White.copy(alpha = 0.35f),
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FingerLengthCell("THB", b.thumbLength)
            FingerLengthCell("IDX", b.indexLength)
            FingerLengthCell("MID", b.middleLength)
            FingerLengthCell("RNG", b.ringLength)
            FingerLengthCell("PNK", b.pinkyLength)
        }

        Spacer(Modifier.height(8.dp))

        // Inline finger-length bar chart — relative proportions
        val lengths = listOf(b.thumbLength, b.indexLength, b.middleLength, b.ringLength, b.pinkyLength)
        val maxLen = lengths.max()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            lengths.forEach { len ->
                val fraction = if (maxLen > 0f) len / maxLen else 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((28 * fraction).dp)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(Plasma.copy(alpha = 0.55f + 0.35f * fraction))
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Divider
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.07f))
        )

        Spacer(Modifier.height(8.dp))

        // Knuckle breadth + quality note
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BiometricCell("KNUCKLE BREADTH", HandBiometrics.fmtMm(b.knuckleBreadth))
            Text(
                text = "QUALITY %.0f%%".format(b.sourcePoseQuality * 100f),
                fontSize = 8.sp,
                color = Plasma.copy(alpha = 0.45f),
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(6.dp))

        // Use-case hint
        Text(
            text = "Use for ring sizing · glove sizing · VR avatar calibration",
            fontSize = 8.sp,
            color = Color.White.copy(alpha = 0.25f),
            fontFamily = FontFamily.Monospace
        )

        if (romData != null) {
            val measuredJoints = romData.joints.filter { it.measured }
            if (measuredJoints.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                Spacer(Modifier.height(8.dp))
                RomPanel(romData)
            }
        }

        val ringSizes = ringSizesFromKnuckleBreadth(b.knuckleBreadth)
        if (ringSizes != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.07f))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "RING SIZE ESTIMATE",
                fontSize = 8.sp,
                color = Color.White.copy(alpha = 0.35f),
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BiometricCell("US / CA",  ringSizes.us)
                BiometricCell("EU / ISO", ringSizes.eu)
                BiometricCell("UK",       ringSizes.uk)
                BiometricCell("CIRC.",    "%.1f mm".format(ringSizes.circumferenceMm))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Based on knuckle breadth · dominant hand may differ ±½ size",
                fontSize = 7.sp,
                color = Color.White.copy(alpha = 0.2f),
                fontFamily = FontFamily.Monospace
            )
        }

        if (history.size >= 2) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HISTORY (${history.size} scans)",
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.35f),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(6.dp))

            // Two-column sparkline grid: one cell per metric
            val metrics = listOf(
                "PALM W"   to history.map { it.biometrics.palmWidth },
                "HAND SP"  to history.map { it.biometrics.handSpan },
                "WRIST"    to history.map { it.biometrics.wristCircumference },
                "INDEX"    to history.map { it.biometrics.indexLength },
                "MIDDLE"   to history.map { it.biometrics.middleLength },
                "KNUCKLE"  to history.map { it.biometrics.knuckleBreadth }
            )

            // Render in rows of 2
            metrics.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (label, values) ->
                        SparklineCell(label = label, values = values, modifier = Modifier.weight(1f))
                    }
                    // Pad odd row
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }

            Text(
                text = "Newest → oldest  ·  mm scale",
                fontSize = 7.sp,
                color = Color.White.copy(alpha = 0.18f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * D3 — Ring size computation.
 *
 * Standard method: ring circumference ≈ knuckle circumference = π × knuckleBreadth.
 * Maps to ISO 8653 (EU), US/Canada, and British letter scales.
 *
 * ISO table sourced from ISO 8653:2016. US sizes are 1⁄4-step increments starting
 * at size 0 = 36.98 mm circumference, step = 2.035 mm. UK sizes are A–Z+6.
 */
private data class RingSizes(
    val us: String,
    val eu: String,
    val uk: String,
    val circumferenceMm: Float
)

private fun ringSizesFromKnuckleBreadth(knuckleBreadthWorld: Float): RingSizes? {
    val diameterMm = HandBiometrics.toMm(knuckleBreadthWorld)
    if (diameterMm <= 0f) return null
    val circumMm = (PI * diameterMm).toFloat()

    // US size: size = (circumference_mm − 36.98) / 2.035
    val usRaw  = (circumMm - 36.98f) / 2.035f
    val usWhole = usRaw.toInt().coerceAtLeast(1)
    val usFrac  = usRaw - usWhole
    val usStr   = when {
        usFrac < 0.16f -> "$usWhole"
        usFrac < 0.41f -> "$usWhole¼"
        usFrac < 0.66f -> "$usWhole½"
        usFrac < 0.91f -> "$usWhole¾"
        else           -> "${usWhole + 1}"
    }

    // EU / ISO 8653: size ≈ circumference in mm, rounded to nearest 0.5
    val euMm  = ((circumMm * 2).roundToInt() / 2.0f)
    val euStr = "%.1f".format(euMm)

    // UK letter sizes: A = 37.0 mm, each step = 1.25 mm
    val ukLetters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val ukIndex   = ((circumMm - 37.0f) / 1.25f).toInt().coerceIn(0, ukLetters.lastIndex)
    val ukStr     = ukLetters[ukIndex].toString()

    return RingSizes(us = usStr, eu = euStr, uk = ukStr, circumferenceMm = circumMm)
}

/**
 * D4 — Mini sparkline composable.
 *
 * Draws a polyline chart of [values] (world-unit floats) scaled to the cell height.
 * The most recent value (index 0) is on the LEFT; oldest on the right.
 * Renders the current value and delta vs. the oldest measurement below the chart.
 *
 * @param label   Metric label shown above the chart.
 * @param values  Ordered list newest → oldest. Minimum 2 entries to show delta.
 */
@Composable
private fun SparklineCell(
    label: String,
    values: List<Float>,
    modifier: Modifier = Modifier
) {
    val accentColor = Plasma
    Column(
        modifier = modifier
            .border(1.dp, accentColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.02f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // Label
        Text(
            text = label,
            fontSize = 7.sp,
            color = Color.White.copy(alpha = 0.35f),
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp
        )
        Spacer(Modifier.height(4.dp))

        if (values.size >= 2) {
            // Sparkline canvas
            val minVal = values.min()
            val maxVal = values.max()
            val range  = (maxVal - minVal).coerceAtLeast(1e-6f)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                val w = size.width
                val h = size.height
                val stepX = w / (values.size - 1).coerceAtLeast(1).toFloat()

                val path = Path()
                values.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = h - ((v - minVal) / range) * h * 0.85f - h * 0.075f  // slight margin
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = accentColor.copy(alpha = 0.7f), style = Stroke(width = 1.5f))

                // Dot at current (newest) value
                val curY = h - ((values[0] - minVal) / range) * h * 0.85f - h * 0.075f
                drawCircle(color = accentColor, radius = 3.5f, center = Offset(0f, curY))
            }

            Spacer(Modifier.height(3.dp))

            // Current value and delta
            val currentMm = HandBiometrics.toMm(values[0])
            val oldestMm  = HandBiometrics.toMm(values.last())
            val deltaMm   = currentMm - oldestMm
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "%.1f mm".format(currentMm),
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (kotlin.math.abs(deltaMm) > 0.05f) {
                    val sign  = if (deltaMm > 0f) "+" else ""
                    val dColor = when {
                        deltaMm > 0f  -> Color(0xFF69F0AE)   // green = grew
                        deltaMm < 0f  -> Color(0xFFFF5252)   // red   = shrunk
                        else          -> Color.White.copy(alpha = 0.3f)
                    }
                    Text(
                        text = "$sign%.1f".format(deltaMm),
                        fontSize = 7.sp,
                        color = dColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            // Fallback: single value, no sparkline
            Text(
                text = "%.1f mm".format(HandBiometrics.toMm(values.firstOrNull() ?: 0f)),
                fontSize = 8.sp,
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BiometricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.9f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            fontSize = 7.sp,
            color = Color.White.copy(alpha = 0.35f),
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun FingerLengthCell(abbrev: String, worldLen: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = HandBiometrics.fmtMm(worldLen),
            fontSize = 9.sp,
            color = Color.White.copy(alpha = 0.75f),
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = abbrev,
            fontSize = 7.sp,
            color = Color.White.copy(alpha = 0.3f),
            fontFamily = FontFamily.Monospace
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Renders the joint range-of-motion result as a compact two-column grid.
 *
 * Each cell shows:
 *   • Joint label
 *   • Range bar (filled to range / 90° as a rough "full ROM" reference)
 *   • "XX°–YY°  (ZZ°)" text
 *
 * Joints with [JointRom.measured] = false are omitted.
 */
@Composable
private fun RomPanel(romData: JointRomData) {
    val measured = romData.joints.filter { it.measured }

    Text(
        text = "JOINT ROM",
        fontSize = 9.sp,
        color = Plasma.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Range of motion across all scan poses",
        fontSize = 7.sp,
        color = Color.White.copy(alpha = 0.25f),
        fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(8.dp))

    measured.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pair.forEach { joint ->
                RomJointCell(joint, modifier = Modifier.weight(1f))
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(5.dp))
    }

    // Summary: average ROM
    val avg = romData.averageRangeDeg
    if (!avg.isNaN()) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Avg ROM  %.0f°  ·  Clinical reference: 85–90° per finger joint".format(avg),
            fontSize = 7.sp,
            color = Color.White.copy(alpha = 0.22f),
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun RomJointCell(joint: JointRom, modifier: Modifier = Modifier) {
    // Full ROM reference: 90° for finger joints is a reasonable clinical benchmark
    val REF_RANGE = 90f
    val fraction = (joint.rangeDeg / REF_RANGE).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .border(1.dp, Plasma.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.02f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = joint.label.uppercase(),
            fontSize = 7.sp,
            color = Color.White.copy(alpha = 0.35f),
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp
        )
        Spacer(Modifier.height(4.dp))

        // Range bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(
                        Plasma.copy(alpha = 0.55f + 0.35f * fraction)
                    )
            )
        }
        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "%.0f°–%.0f°".format(joint.minDeg, joint.maxDeg),
                fontSize = 8.sp,
                color = Color.White.copy(alpha = 0.65f),
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "%.0f°".format(joint.rangeDeg),
                fontSize = 8.sp,
                color = Plasma.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
