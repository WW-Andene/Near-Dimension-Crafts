package com.arhand.ui

import android.opengl.GLSurfaceView
import android.view.MotionEvent
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
import androidx.compose.ui.viewinterop.AndroidView
import com.arhand.render.ModelViewerRenderer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen interactive 3D model viewer composable.
 *
 * Gesture model:
 *   Single-finger drag  → orbit (azimuth + elevation)
 *   Two-finger pinch    → zoom (orbitRadius)
 *
 * The [ModelViewerRenderer] is retained across recompositions via [remember];
 * mesh data is injected via [meshPositions].
 */
@Composable
fun ModelViewerScreen(
    meshPositions: FloatArray,
    onDismiss: () -> Unit,
    /** ARCH-3 — Live retarget result from the tracking pipeline. Null = static mesh display. */
    retargetResult: com.arhand.mocap.RetargetResult? = null,
    /** ARCH-3 — Loaded skinned asset. When both this and [retargetResult] are non-null,
     *  the viewer drives the character skeleton in real time. */
    loadedAsset: com.arhand.mocap.LoadedAsset? = null
) {
    val viewerRenderer = remember { ModelViewerRenderer() }
    // Captured from the factory below so the DisposableEffect can release GL resources on
    // the GL thread when this screen leaves composition — a fresh ModelViewerRenderer + GL
    // context are created each time this screen is entered (see ENGINE_ARCHITECTURE.md §7.2),
    // and without this, repeated navigation to this screen within one process lifetime never
    // frees the previous instance's GL objects.
    var glSurface by remember { mutableStateOf<GLSurfaceView?>(null) }

    // Keep renderer in sync with the latest mesh and live data
    LaunchedEffect(meshPositions) {
        viewerRenderer.meshPositions = meshPositions
    }
    // ARCH-3 — Update live fields every recomposition (driven by State from AppViewModel)
    viewerRenderer.retargetResult = retargetResult
    viewerRenderer.loadedAsset    = loadedAsset

    DisposableEffect(Unit) {
        onDispose {
            glSurface?.queueEvent { viewerRenderer.release() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0B0E))) {

        // GL Surface
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                object : GLSurfaceView(ctx) {
                    // Touch state
                    private var lastX    = 0f
                    private var lastY    = 0f
                    private var lastSpan = 0f
                    private var isDrag   = false
                    private var isPinch  = false

                    init {
                        setEGLContextClientVersion(3)
                        setRenderer(viewerRenderer)
                        renderMode = RENDERMODE_CONTINUOUSLY
                    }

                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lastX  = event.x; lastY = event.y
                                isDrag = true; isPinch = false
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> {
                                if (event.pointerCount == 2) {
                                    lastSpan = span(event)
                                    isPinch = true; isDrag = false
                                }
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (isPinch && event.pointerCount == 2) {
                                    val newSpan = span(event)
                                    val delta   = (lastSpan - newSpan) * 0.02f
                                    viewerRenderer.orbitRadius =
                                        (viewerRenderer.orbitRadius + delta).coerceIn(1f, 10f)
                                    lastSpan = newSpan
                                } else if (isDrag) {
                                    val dx = event.x - lastX
                                    val dy = event.y - lastY
                                    viewerRenderer.azimuthDeg   += dx * 0.4f
                                    viewerRenderer.elevationDeg =
                                        (viewerRenderer.elevationDeg - dy * 0.3f).coerceIn(-89f, 89f)
                                    lastX = event.x; lastY = event.y
                                }
                            }
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_POINTER_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                isDrag = false; isPinch = false
                            }
                        }
                        return true
                    }

                    private fun span(e: MotionEvent): Float {
                        val dx = e.getX(0) - e.getX(1)
                        val dy = e.getY(0) - e.getY(1)
                        return hypot(dx, dy)
                    }
                }.also { glSurface = it }
            },
            update = { /* renderer state updated via @Volatile fields */ }
        )

        // Overlay UI
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "3D MODEL VIEWER",
                fontSize = 13.sp,
                color = Plasma,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "DRAG TO ORBIT  •  PINCH TO ZOOM",
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.35f),
                fontFamily = FontFamily.Monospace
            )
        }

        // Close button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .border(1.dp, Color.White.copy(0.35f), RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(0.06f))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 32.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "CLOSE",
                fontSize = 11.sp,
                color = Color.White.copy(0.6f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
