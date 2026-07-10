package com.arhand.feature.scan

import com.arhand.scanner.HandBiometrics
import com.arhand.scanner.JointRomData

/**
 * Which scan mode is currently running: nothing, a posed scan, or a freeform scan.
 *
 * Before this existed, "which mode is active" was represented by four booleans spread across
 * two objects — `AppUiState.scanActive`, `ScanState.freeformActive`,
 * `SpatialFrameRouter.isScanActive`, `SpatialFrameRouter.isFreeformActive` — that every scan
 * start/cancel/complete call site had to remember to set together. That shape caused two real
 * bugs: `SpatialFrameRouter`'s posed-scan capture branch was dead code because nothing ever set
 * `isScanActive` for a posed scan (ENGINE_ARCHITECTURE.md §10.3), and the freeform-scan `FAILED`
 * watcher only reset 2 of the 4 flags, leaving the router still thinking a freeform scan was
 * active after it had already failed. `com.arhand.ui.AppViewModel.setScanLifecycle` is now the
 * single place that sets all four from one [ScanLifecycle] value, so a call site can no longer
 * forget one of them.
 */
sealed class ScanLifecycle {
    object Idle     : ScanLifecycle()
    object Posed    : ScanLifecycle()
    object Freeform : ScanLifecycle()
}

/**
 * Immutable state snapshot for the scan feature.
 *
 * Collected independently by consumers such as [com.arhand.ui.ScanResultModal] —
 * changes here don't recompose unrelated screens.
 */
data class ScanState(
    val isActive:              Boolean                  = false,
    val hasStoredModel:        Boolean                  = false,
    /**
     * Monotonically incremented only when a freeform scan *successfully* produces a
     * model (never touched by cancel/failure). [com.arhand.ui.MainActivity]'s result-modal
     * trigger keys off a change in this id instead of the ambient [hasStoredModel] flag,
     * so a cancelled/failed scan after an earlier successful one can't re-show the modal.
     */
    val completedScanId:       Int                      = 0,
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
    /**
     * LIMIT-2 — True while a freeform (continuous) scan is accumulating frames.
     * Set only via [com.arhand.ui.AppViewModel.setScanLifecycle] — see [ScanLifecycle].
     */
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
