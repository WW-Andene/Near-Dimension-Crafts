package com.arhand.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ─── Onboarding page data ─────────────────────────────────────────────────────

private data class OnboardingPage(
    val glyph:    String,
    val title:    String,
    val body:     String,
    val accent:   Color
)

private val PAGES = listOf(
    OnboardingPage(
        glyph  = "✋",
        title  = "HANDY",
        body   = "AR Hand Scanner captures the geometry of your hand using your phone camera — no depth sensor required.",
        accent = Plasma
    ),
    OnboardingPage(
        glyph  = "👁",
        title  = "HOW TO SCAN",
        body   = "Hold your hand 20–40 cm from the lens. Follow the pose guide through 8 positions. Keep your hand steady during each countdown.",
        accent = Color(0xFF00E5FF)
    ),
    OnboardingPage(
        glyph  = "🖐",
        title  = "WHAT YOU GET",
        body   = "A 3D mesh of your hand, biometric measurements (finger lengths, ring size, wrist circumference), and a motion-capture BVH file.",
        accent = Color(0xFF69FF47)
    ),
    OnboardingPage(
        glyph  = "📤",
        title  = "EXPORT",
        body   = "Share your scan as a GLB model, stream live hand poses via OSC to VSeeFace, Unreal LiveLink, or any compatible receiver.",
        accent = Color(0xFFFFD740)
    )
)

// ─── OnboardingScreen ─────────────────────────────────────────────────────────

/**
 * E6 — Guided first-launch onboarding.
 *
 * 4-page swipeable intro gated by the DataStore "onboarding_seen" key.
 * Shown once; dismissed permanently by tapping CONTINUE on the last page
 * or GET STARTED on any page via the skip button.
 *
 * @param onDismiss Called when the user finishes or skips the onboarding.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onDismiss: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGES.size })
    val scope      = rememberCoroutineScope()
    val isLast     = pagerState.currentPage == PAGES.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state    = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            OnboardingPage(page = PAGES[pageIndex])
        }

        // ── Skip button (top-right, hidden on last page) ──────────────────────
        if (!isLast) {
            PlasmaButton(
                label    = "SKIP",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 24.dp),
                accent   = Color.White.copy(alpha = 0.35f),
                onClick  = onDismiss
            )
        }

        // ── Bottom nav bar ────────────────────────────────────────────────────
        Column(
            modifier            = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Dot indicators
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(PAGES.size) { i ->
                    val active = i == pagerState.currentPage
                    val width by animateDpAsState(
                        targetValue = if (active) 24.dp else 8.dp,
                        animationSpec = tween(250),
                        label = "dot_$i"
                    )
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(
                                if (active) PAGES[i].accent
                                else Color.White.copy(alpha = 0.25f)
                            )
                    )
                }
            }

            // CTA button
            PlasmaButton(
                label   = if (isLast) "GET STARTED" else "NEXT",
                accent  = PAGES[pagerState.currentPage].accent,
                onClick = {
                    if (isLast) {
                        onDismiss()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                }
            )
        }
    }
}

// ─── Single onboarding page ───────────────────────────────────────────────────

@Composable
private fun OnboardingPage(page: OnboardingPage) {
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text     = page.glyph,
            fontSize = 72.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text       = page.title,
            fontSize   = 18.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color      = page.accent,
            letterSpacing = 4.sp,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text       = page.body,
            fontSize   = 14.sp,
            fontFamily = FontFamily.Monospace,
            color      = Color.White.copy(alpha = 0.75f),
            lineHeight = 22.sp,
            textAlign  = TextAlign.Center
        )
    }
}

// ─── Shared pill button ───────────────────────────────────────────────────────

@Composable
private fun PlasmaButton(
    label:    String,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
    accent:   Color    = Plasma
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.15f))
            .clickable(
                indication          = null,
                interactionSource   = remember { MutableInteractionSource() },
                onClick             = onClick
            )
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text          = label,
            fontSize      = 12.sp,
            fontFamily    = FontFamily.Monospace,
            fontWeight    = FontWeight.Bold,
            color         = accent,
            letterSpacing = 2.sp
        )
    }
}
