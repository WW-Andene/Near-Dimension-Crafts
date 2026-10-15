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
import com.neardimension.handy.mocap.OscSchema

// ── Gap 7: Stream panel ───────────────────────────────────────────────────────

@Composable
internal fun StreamPanel(
    isStreaming:         Boolean,
    oscHost:             String,
    oscPort:             Int,
    oscSchema:           OscSchema,
    oscHealth:           com.neardimension.handy.mocap.OscHealthState,
    discoveredReceivers: List<com.neardimension.handy.feature.stream.OscDiscovery.DiscoveredReceiver> = emptyList(),
    isDiscovering:       Boolean = false,
    onToggle:            () -> Unit,
    onDiscoverToggle:    () -> Unit = {},
    onSelectReceiver:    (com.neardimension.handy.feature.stream.OscDiscovery.DiscoveredReceiver) -> Unit = {}
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
