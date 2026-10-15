package com.arhand.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Full-screen white flash — the scan capture flash effect, fired once per accepted pose.
 *
 * ENGINE_ARCHITECTURE.md §6.2 — this used to take a plain `visible: Boolean` and animate via
 * `rememberInfiniteTransition`/`RepeatMode.Restart`, which flashes forever once `visible` is
 * true rather than the single 200ms flash its own doc comment described, and nothing called it
 * anyway. [trigger] is a monotonically-incrementing token (see `AppUiState.captureFlashToken`,
 * bumped once per captured pose in `Scanner`) — each new value fires exactly one 200ms fade,
 * matching a camera-shutter flash rather than a strobe.
 */
@Composable
fun WhiteScreenOverlay(trigger: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        alpha.snapTo(1f)
        alpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(200))
    }
    if (alpha.value > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = alpha.value))
        )
    }
}
