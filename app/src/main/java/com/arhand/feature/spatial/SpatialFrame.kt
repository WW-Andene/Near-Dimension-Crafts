package com.arhand.feature.spatial

import com.arhand.depth.PlaneFitter
import com.arhand.depth.SlamLite
import com.arhand.depth.SurfaceNormals
import com.arhand.mocap.RetargetResult
import com.arhand.mocap.BodyRetargetResult
import com.arhand.tracking.FaceExpressions
import com.arhand.tracking.HandLandmarks
import com.arhand.tracking.PoseLandmarks
import com.arhand.util.Vec3

/**
 * SpatialFrame — the unified per-frame truth object.
 *
 * Every sensing pipeline (hand tracking, body tracking, face tracking, structured
 * light depth, ARCore world frame, monocular depth, scene understanding, rPPG)
 * contributes to a single immutable frame that all consumers (OSC, BVH, renderer,
 * scanner, HUD) read from.
 *
 * ## v27 additions
 *
 * Fields from the Handy v3 architecture redesign (Layer 1 additions):
 *   [da2Depth]       — Depth Anything v2 monocular depth per block
 *   [fusedMeters]    — XR affine-calibrated metric depth per block
 *   [pspProfile]     — PSP absolute phase height map (null when stale)
 *   [surfaceNormals] — per-block X/Y/Z normals from depth gradients
 *   [ao]             — ambient occlusion proxy [0,1] per block
 *   [planes]         — RANSAC detected floor/wall/ceiling planes
 *   [slamPose]       — accumulated SLAM camera pose (tx,ty,tz,rx,ry,rz)
 *   [slamDelta]      — per-frame camera motion (tx,ty,rz)
 *   [rppgAmplitude]  — BVP AC amplitude [0,1]
 *   [rppgBPM]        — estimated heart rate (BPM)
 *   [jbuDepth]       — JBU 2× upsampled depth (16×12)
 *   [fusionWeights]  — live per-channel weight snapshot for diagnostic HUD
 *   [metricSource]   — "XR" | "DA2" | "STEREO" | "NONE"
 */
data class SpatialFrame(

    // ── Identity ──────────────────────────────────────────────────────────────

    val timestamp:       Long,
    val aspect:          Float,
    val isFrontCamera:   Boolean,

    // ── Hand tracking ─────────────────────────────────────────────────────────

    val primaryHand:        SpatializedHand?,
    val secondaryHand:      SpatializedHand?,
    val primaryRetarget:    RetargetResult?,
    val secondaryRetarget:  RetargetResult?,

    // ── Body tracking ─────────────────────────────────────────────────────────

    val body:           BodyRetargetResult?,
    val bodyLandmarks:  PoseLandmarks?,

    // ── Face tracking ─────────────────────────────────────────────────────────

    val face:           FaceExpressions?,

    // ── Spatial grounding ─────────────────────────────────────────────────────

    val metricGrounded:  Boolean,
    val cameraWorldPos:  Vec3?,
    val depthConfidence: Float,

    // ── Structured light ──────────────────────────────────────────────────────

    val slDepth:      FloatArray?,
    val slGradX:      FloatArray?,
    val slGradY:      FloatArray?,
    val slCalibrated: Boolean,
    val slDrifting:   Boolean,

    // ── v27 Layer 1: monocular + fused metric depth ───────────────────────────

    /** DA2 per-block relative (or XR-calibrated metric) depth. Null when model absent. */
    val da2Depth:     FloatArray?,

    /** XR affine-calibrated absolute metric depth (metres) per block. */
    val fusedMeters:  FloatArray?,

    /** PSP absolute phase height map (8×6). Null when stale or no capture yet. */
    val pspProfile:   FloatArray?,

    // ── v27 Layer 1: scene understanding ─────────────────────────────────────

    /** Per-block surface normals from depth gradients. Null when depth unavailable. */
    val surfaceNormals: SurfaceNormals.NormalMap?,

    /** Per-block ambient occlusion proxy [0–1]. Null when depth unavailable. */
    val ao:             FloatArray?,

    /** RANSAC-detected floor / wall / ceiling planes. Empty list when no planes found. */
    val planes:         List<PlaneFitter.DetectedPlane>,

    // ── v27 Layer 1: visual SLAM ──────────────────────────────────────────────

    /** Accumulated SLAM camera pose since last reset. Null until first frame processed. */
    val slamPose:  SlamLite.CameraPose?,

    /** Per-frame camera motion delta from SlamLite. */
    val slamDelta: SlamLite.PoseDelta?,

    // ── v27 Layer 2: biometrics ───────────────────────────────────────────────

    /** rPPG blood-volume pulse AC amplitude as fraction of DC (0–1). */
    val rppgAmplitude: Float,

    /** rPPG estimated heart rate in BPM. 0 until warm (~4 s). */
    val rppgBPM:       Int,

    /**
     * ENGINE_ARCHITECTURE.md §10.5 — SNS (sympathetic nervous system) arousal proxy:
     * rolling standard deviation of [rppgAmplitude] over the last ~32 frames. Higher
     * values correlate with higher-frequency amplitude variability, a proxy for
     * stress/arousal. 0 until warm.
     */
    val rppgSnsProxy: Float,

    // ── v27 Layer 1: joint-bilateral upsampled depth ──────────────────────────

    /** JBU 2× upsampled depth grid (16×12, row-major). Null when SL depth unavailable. */
    val jbuDepth: FloatArray?,

    // ── v27 diagnostic ────────────────────────────────────────────────────────

    /** Live per-channel arbiter weight snapshot for the diagnostic HUD. */
    val fusionWeights: FusionWeights,

    /**
     * Which source is providing metric scale: "XR" (ARCore), "DA2" (monocular),
     * "STEREO" (dual-camera), or "NONE" (relative only).
     */
    val metricSource: String,

    // ── Frame quality ─────────────────────────────────────────────────────────

    val frameConfidence:      Float,
    val activePipelineCount:  Int,

    // ── Cross-cadence age (ENGINE_ARCHITECTURE.md §5.1) ───────────────────────
    // fusionWeights/metricGrounded/rppg* above are written by Core-layer code at raw-frame
    // rate but this SpatialFrame is assembled at the throttled hand-inference rate — these
    // ages (milliseconds since each value was actually last computed) let a consumer see how
    // synchronised (or not) they really are to [timestamp]/the bundled hand landmarks, instead
    // of silently assuming "whatever Core last computed" is current. Low-risk observability
    // only, per that finding's recommended fix — no discarding/gating added without on-device
    // data showing the gap is actually large enough to matter.

    /** Milliseconds since [fusionWeights] was last recomputed. */
    val fusionWeightsAgeMs: Long = 0L,
    /** Milliseconds since [metricGrounded]/[metricSource]'s underlying ARCore callback last fired. */
    val metricModeAgeMs:    Long = 0L,
    /** Milliseconds since [rppgAmplitude]/[rppgBPM] were last computed. */
    val rppgAgeMs:           Long = 0L
)

/**
 * A tracked hand with per-landmark SL depth correction and occlusion flags.
 *
 * [slCorrectedZ] is a 21-element float array where each value is the SL-corrected
 * depth at that landmark. Consumers prefer [slCorrectedZ[i]] over [landmarks[i].z].
 *
 * [occluded] flags landmarks where occlusion probability exceeds the hysteresis
 * threshold in [com.arhand.tracking.OcclusionEngine].
 */
data class SpatializedHand(
    val slotIndex:    Int,
    val landmarks:    HandLandmarks,
    val slCorrectedZ: FloatArray?,
    val occluded:     BooleanArray
) {
    companion object {
        const val OCCLUSION_THRESH = 0.22f
    }
}

/**
 * Live per-channel weight snapshot from [CrossChannelArbiter] for the diagnostic HUD.
 * All values are the arbiter's normalised weight [0–1] for that channel this frame.
 */
data class FusionWeights(
    val base:   Float = 0f,
    val sl:     Float = 0f,
    val psp:    Float = 0f,
    val dvel:   Float = 0f,
    val da2:    Float = 0f,
    val lca:    Float = 0f,
    val xr:     Float = 0f,
    val pol:    Float = 0f,
    val flare:  Float = 0f,
    val moire:  Float = 0f,
    val rs:     Float = 0f,
    val stereo: Float = 0f
) {
    val sum: Float get() = base + sl + psp + dvel + da2 + lca + xr + pol + flare + moire + rs + stereo

    companion object {
        val ZERO = FusionWeights()
    }
}
