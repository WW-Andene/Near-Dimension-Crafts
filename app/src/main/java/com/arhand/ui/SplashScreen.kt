package com.arhand.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SplashScreen() {
    val transition = rememberInfiniteTransition(label = "splash")
    val pulse by transition.animateFloat(
        0.6f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "HANDY",
                fontSize = 36.sp,
                color = Plasma.copy(alpha = pulse),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AR HAND SCANNER",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f),
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text = "initializing...",
                fontSize = 10.sp,
                color = Plasma.copy(alpha = pulse * 0.7f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
