package com.arhand.ui

import androidx.compose.ui.graphics.Color

// Shared UI palette — used across HudOverlay, ScanOverlay, FreeformScanOverlay,
// ScanResultModal, SettingsScreen, SplashScreen, ModelViewerScreen, OnboardingScreen,
// and MainActivity. Previously declared in ControlPanel.kt; kept here after that
// file's now-unused composables were removed, since these constants are not dead.
val UIBg   = Color(0xD20A0D12)
val Plasma = Color(0xFF7B2FBE)
val Warn   = Color(0xFFFF5722)
