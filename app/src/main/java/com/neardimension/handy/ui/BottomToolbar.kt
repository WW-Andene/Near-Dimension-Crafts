package com.neardimension.handy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BottomToolbar(
    workflowMode: WorkflowMode,
    isStreaming:  Boolean,
    isRecording:  Boolean,
    isScanActive: Boolean,
    onSettings:   () -> Unit,
    onAssets:     () -> Unit,
    onCalibrate:  () -> Unit,
    onStream:     () -> Unit,
    onRecord:     () -> Unit
) {
    val Mono = FontFamily.Monospace
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC111111))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarItem("SETTINGS", workflowMode == WorkflowMode.SETTINGS, Color.White, onSettings)
        ToolbarItem("ASSETS",   false,                                  Color(0xFFFFD600), onAssets)
        ToolbarItem("SCAN",     isScanActive,                           Color(0xFF7B2FBE), onCalibrate)
        ToolbarItem("STREAM",   isStreaming,                            Color(0xFF00C853), onStream)
        ToolbarItem("REC",      isRecording,                            Color(0xFFD50000), onRecord)
    }
}

@Composable
private fun ToolbarItem(label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text       = label,
            color      = if (active) activeColor else Color.White.copy(alpha = 0.45f),
            fontSize   = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        if (active) {
            Spacer(Modifier.height(3.dp))
            Box(Modifier.size(4.dp).background(activeColor, shape = RoundedCornerShape(2.dp)))
        }
    }
}
