package com.arhand.feature.scan

import com.arhand.scanner.HandBiometrics
import com.arhand.scanner.JointRomData

/**
 * Immutable state snapshot for the scan feature.
 *
 * Collected independently by consumers such as [com.arhand.ui.ScanResultModal] —
 * changes here don't recompose unrelated screens.
 */
data class ScanState(
    val isActive:              Boolean                  = false,
    val hasStoredModel:        Boolean                  = false,
    val exportedGlbPath:       String?                  = null,
    val handBiometrics:        HandBiometrics?          = null,
    val biometricHistory:      List<com.arhand.scanner.BiometricHistoryEntry> = emptyList(),
    val jointRomData:          JointRomData?            = null,
    val neuralReconEnabled:    Boolean                  = false,
    val neuralReconDiagnostics: NeuralReconDiagnostics? = null,
    val photoStereoEnabled:    Boolean                  = false,
    val depthMode:             Boolean                  = false,
    val depthApiAvailable:     Boolean                  = false,
    val arcoreDepthHw:         Boolean                  = false,
    /** LIMIT-2 — True while a freeform (continuous) scan is accumulating frames. */
    val freeformActive:        Boolean                  = false,
    /** LIMIT-2 — Latest status from [com.arhand.scanner.FreeformScanner]. */
    val freeformStatus:        com.arhand.scanner.FreeformScanner.FreeformStatus? = null,
    /** GAP-5 — True while the calibration card flow is active. */
    val calibrationCardActive: Boolean                  = false,

    /**
     * Fused-sl-v7 — Structured light calibration progress (0..1).
     * 0 = not started / off, 1 = fully calibrated. Shown in HUD as "SL XX%".
     */
    val slCalibrationProgress: Float                   = 0f,
    /** True when SL drift detection has flagged ambient light change. */
    val slDrifting:            Boolean                  = false
)
