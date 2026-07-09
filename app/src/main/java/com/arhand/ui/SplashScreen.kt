package com.arhand.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * @param permissionDenied When true, shows a retry prompt instead of the
 *   "initializing..." pulse — without this, a user who denies CAMERA access
 *   has no way to see why the app is stuck or to try again.
 * @param onRequestPermission Re-triggers the CAMERA permission request (or, if
 *   the user permanently denied it, should open the app's system settings page).
 */
@Composable
fun SplashScreen(
    permissionDenied: Boolean = false,
    onRequestPermission: () -> Unit = {}
) {
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
            if (permissionDenied) {
                Text(
                    text = "Camera access is required to use Handy.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "GRANT CAMERA ACCESS",
                    fontSize = 11.sp,
                    color = Plasma,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier
                        .border(1.dp, Plasma, RoundedCornerShape(4.dp))
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onRequestPermission)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
            } else {
                Text(
                    text = "initializing...",
                    fontSize = 10.sp,
                    color = Plasma.copy(alpha = pulse * 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
