package com.arhand.ui

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
import com.arhand.feature.record.TakeEntry

// ── Gap 5/7: Record panel ─────────────────────────────────────────────────────

@Composable
internal fun RecordPanel(
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
internal fun TakesLibrarySheet(
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
            .safeDrawingPadding()
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
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val date = remember(take.takenAtMs, locale) {
        java.text.SimpleDateFormat("MMM d  HH:mm", locale).format(java.util.Date(take.takenAtMs))
    }

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
