package com.arhand.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
internal fun CalibrateSheet(
    onPosedScan: () -> Unit,
    onFreeform:  () -> Unit,
    onDismiss:   () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .navigationBarsPadding()
                .padding(24.dp)
                .clickable(indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = {}),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text       = "CALIBRATE",
                color      = Color.White,
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(bottom = 4.dp)
            )
            CalibButton("POSED SCAN  ·  8 static poses", Color(0xFF7B2FBE), onPosedScan)
            CalibButton("FREEFORM SCAN  ·  rotate hand freely", Color(0xFF7B2FBE), onFreeform)
            CalibButton("CANCEL", Color(0x66FFFFFF), onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CalibButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text       = label,
            color      = color,
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
