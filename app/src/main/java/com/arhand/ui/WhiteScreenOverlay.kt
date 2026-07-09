package com.arhand.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Full-screen white flash — used for the scan capture flash effect.
 * Call trigger() to fire a single 200ms flash animation.
 */
@Composable
fun WhiteScreenOverlay(visible: Boolean) {
    if (!visible) return
    val transition = rememberInfiniteTransition(label = "flash")
    val alpha by transition.animateFloat(
        1f, 0f,
        infiniteRepeatable(tween(200), RepeatMode.Restart),
        label = "flashAlpha"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = alpha))
    )
}
