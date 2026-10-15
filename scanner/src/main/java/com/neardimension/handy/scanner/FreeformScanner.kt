package com.arhand.scanner

import android.graphics.Bitmap
import com.arhand.tracking.HandLandmarks
import com.arhand.util.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Continuous freeform hand scan — LiDAR-replacement mode.
 *
 * The user holds their hand in front of the camera and slowly rotates it through
 * all angles over 10–20 seconds. This scanner continuously accumulates frames
 * without requiring specific poses to be held.
 *
 * ## Gating criteria (applied per frame)
 *
 *   1. Quality score >= [FREEFORM_MIN_QUALITY] (same QualityEngine, lower threshold
 *      than posed scan's 0.55 — some motion blur is expected and frame volume compensates)
 *   2. Motion velocity in [MOTION_MIN, MOTION_MAX] — too still = redundant frame,
 *      too fast = landmark jitter
 *   3. Hand visible (21 landmarks detected)
 *   4. Viewpoint novelty — discard frames whose wrist→middle_mcp orientation is
 *      within [NOVELTY_ANGLE_DEG] of an already-accepted frame (ensures angular coverage)
 *
 * ## Coverage tracking
 *
 * A 32-bucket polar grid (8 azimuth × 4 elevation) tracks which viewing angles have
 * been captured. The [FreeformStatus.coveragePercent] drives the polar ring UI and the
 * auto-complete trigger. 75% coverage (~24/32 buckets) + [MIN_FRAMES_FOR_MESH] accepted
 * frames + [MIN_DURATION_SEC] elapsed → auto-complete.
 *
 * ## ScanPipeline compatibility
 *
 * Output types match the posed [Scanner] exactly:
 *   - [capturedFrames]  : List<Pair<List<Vec3>, Float>> — world landmarks + quality
 *   - [biometricFrames] : List<Pair<HandLandmarks, Float>> — for HandBiometrics.compute()
 *   - [capturedBitmaps] : List<Bitmap> — for TextureBaker
 *
 * [ScanPipeline.process] accepts these unchanged via [ScanInput] — no pipeline
 * modifications needed. The TSDF volume is shared with AppViewModel and accumulated
 * by the existing depth integration loop.
 */
class FreeformScanner {

    enum class State { IDLE, ACTIVE, COMPLETE, FAILED }

    data class FreeformStatus(
        val state:           State   = State.IDLE,
        val frameCount:      Int     = 0,
        val coveragePercent: Float   = 0f,      // 0–1: fraction of 32-bucket grid covered
        val elapsedSec:      Float   = 0f,
        val remainingSec:    Float   = MAX_DURATION_SEC,
        val quality:         Float   = 0f,
        val motionOk:        Boolean = false,
        val failureReason:   String? = null
    )

    companion object {
        const val MAX_DURATION_SEC     = 20f
        const val MIN_DURATION_SEC     = 8f
        /** Lower than posed-scan threshold (0.55) — motion blur is expected during rotation. */
        const val FREEFORM_MIN_QUALITY = 0.40f
        /** Minimum accepted frames before a mesh is plausible. */
        const val MIN_FRAMES_FOR_MESH  = 60
        /** Target frame count for full coverage (10s × 30fps, quality-gated to ~200). */
        const val TARGET_FRAMES        = 300
        /** Minimum angular distance (degrees) between accepted viewpoints. */
        const val NOVELTY_ANGLE_DEG    = 12f
        /** Squared sum of per-landmark 2D displacement below which a frame is "still". */
        const val MOTION_MIN           = 0.002f
        /** Squared sum above which a frame is "too fast" (landmark jitter). */
        const val MOTION_MAX           = 0.08f
        /** Coverage trigger: auto-complete when this fraction of buckets is filled. */
        const val AUTO_COMPLETE_COVERAGE = 0.75f

        // Polar coverage grid dimensions
        const val AZ_BUCKETS    = 8
        const val EL_BUCKETS    = 4
        const val TOTAL_BUCKETS = AZ_BUCKETS * EL_BUCKETS  // 32
    }

    private val _status = MutableStateFlow(FreeformStatus())
    val status: StateFlow<FreeformStatus> = _status

    // ── Accumulated outputs (ScanPipeline-compatible) ─────────────────────────

    val capturedFrames  = mutableListOf<Pair<List<Vec3>, Float>>()
    val biometricFrames = mutableListOf<Pair<HandLandmarks, Float>>()
    val capturedBitmaps = mutableListOf<Bitmap>()

    // ── Coverage state ────────────────────────────────────────────────────────

    private val coverageBuckets      = BooleanArray(TOTAL_BUCKETS)
    private var coveredBuckets       = 0
    private val acceptedOrientations = mutableListOf<Pair<Float, Float>>()

    // ── Runtime state ─────────────────────────────────────────────────────────

    private var startMs = 0L
    private var prevLms: HandLandmarks? = null
    private var active  = false

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        capturedFrames.clear()
        biometricFrames.clear()
        capturedBitmaps.clear()
        coverageBuckets.fill(false)
        acceptedOrientations.clear()
        coveredBuckets = 0
        prevLms = null
        startMs = System.currentTimeMillis()
        active  = true
        _status.value = FreeformStatus(state = State.ACTIVE, remainingSec = MAX_DURATION_SEC)
    }

    fun reset() {
        active = false
        capturedFrames.clear()
        biometricFrames.clear()
        capturedBitmaps.clear()
        coverageBuckets.fill(false)
        acceptedOrientations.clear()
        coveredBuckets = 0
        prevLms = null
        _status.value = FreeformStatus(state = State.IDLE)
    }

    /**
     * Feed a camera frame. Call every tracking frame while active.
     *
     * @param lms           Current hand landmarks (null if hand not detected)
     * @param claheContrast CLAHE contrast score from CLAHEAnalyzer
     * @param bitmap        Current camera frame for texture baking (sampled every 10th frame)
     * @param worldFrames   World-space landmarks from DepthCarver.landmarksToWorld — the
     *                      values stored in capturedFrames for SDF/MLP reconstruction
     * @param aspect        Viewport aspect ratio
     * @param mirrorX       True for front camera
     * @param nowMs         Current timestamp in milliseconds
     */
    fun update(
        lms:           HandLandmarks?,
        claheContrast: Float,
        bitmap:        Bitmap?,
        worldFrames:   List<Vec3>?,
        aspect:        Float,
        mirrorX:       Boolean,
        nowMs:         Long
    ) {
        if (!active) return

        val elapsedSec   = (nowMs - startMs) / 1000f
        val remainingSec = (MAX_DURATION_SEC - elapsedSec).coerceAtLeast(0f)

        // Hard time cap
        if (elapsedSec >= MAX_DURATION_SEC) {
            complete(elapsedSec)
            return
        }

        // Hand not visible
        if (lms == null || lms.size < 21) {
            _status.value = _status.value.copy(
                state        = State.ACTIVE,
                elapsedSec   = elapsedSec,
                remainingSec = remainingSec,
                quality      = 0f,
                motionOk     = false
            )
            prevLms = null
            return
        }

        val quality   = QualityEngine.evaluate(lms, claheContrast, prevLms)
        val motionMag = if (prevLms != null) motionMagnitude(lms, prevLms!!) else Float.MAX_VALUE
        val motionOk  = motionMag in MOTION_MIN..MOTION_MAX
        prevLms = lms

        if (quality.score < FREEFORM_MIN_QUALITY || !motionOk) {
            _status.value = _status.value.copy(
                state        = State.ACTIVE,
                frameCount   = capturedFrames.size,
                coveragePercent = coveredBuckets.toFloat() / TOTAL_BUCKETS,
                elapsedSec   = elapsedSec,
                remainingSec = remainingSec,
                quality      = quality.score,
                motionOk     = motionOk
            )
            return
        }

        // Viewpoint novelty gate
        val (az, el) = wristOrientation(lms)
        if (!isNovel(az, el)) {
            _status.value = _status.value.copy(
                state        = State.ACTIVE,
                frameCount   = capturedFrames.size,
                coveragePercent = coveredBuckets.toFloat() / TOTAL_BUCKETS,
                elapsedSec   = elapsedSec,
                remainingSec = remainingSec,
                quality      = quality.score,
                motionOk     = true
            )
            return
        }

        // Accept this frame
        acceptedOrientations.add(Pair(az, el))
        markCoverageBucket(az, el)

        if (worldFrames != null && worldFrames.size == 21) {
            capturedFrames.add(Pair(worldFrames, quality.score))
        }
        // Sample biometrics every 5th accepted frame
        if (capturedFrames.size % 5 == 0) {
            biometricFrames.add(Pair(lms, quality.score))
        }
        // Sample bitmaps every 10th accepted frame for texture baking
        if (bitmap != null && capturedFrames.size % 10 == 0) {
            capturedBitmaps.add(
                bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            )
        }

        val coverage = coveredBuckets.toFloat() / TOTAL_BUCKETS

        // Auto-complete: sufficient time + coverage + frame count
        if (elapsedSec >= MIN_DURATION_SEC
            && coverage >= AUTO_COMPLETE_COVERAGE
            && capturedFrames.size >= TARGET_FRAMES) {
            complete(elapsedSec)
            return
        }

        _status.value = _status.value.copy(
            state           = State.ACTIVE,
            frameCount      = capturedFrames.size,
            coveragePercent = coverage,
            elapsedSec      = elapsedSec,
            remainingSec    = remainingSec,
            quality         = quality.score,
            motionOk        = true
        )
    }

    /**
     * User manually taps "Finish" before auto-complete triggers.
     * Validates minimum frame count before transitioning to COMPLETE.
     */
    fun finish() {
        if (!active) return
        val elapsed = (System.currentTimeMillis() - startMs) / 1000f
        if (capturedFrames.size < MIN_FRAMES_FOR_MESH) {
            markFailed(
                "Not enough coverage (${capturedFrames.size}/$MIN_FRAMES_FOR_MESH frames). " +
                "Keep rotating your hand."
            )
        } else {
            complete(elapsed)
        }
    }

    fun markFailed(reason: String) {
        active = false
        _status.value = _status.value.copy(state = State.FAILED, failureReason = reason)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun complete(elapsedSec: Float) {
        active = false
        if (capturedFrames.size < MIN_FRAMES_FOR_MESH) {
            markFailed(
                "Coverage too low after ${elapsedSec.toInt()}s. " +
                "Move hand more slowly in a full arc."
            )
            return
        }
        _status.value = _status.value.copy(
            state           = State.COMPLETE,
            frameCount      = capturedFrames.size,
            coveragePercent = coveredBuckets.toFloat() / TOTAL_BUCKETS,
            elapsedSec      = elapsedSec,
            remainingSec    = 0f
        )
    }

    /**
     * Compute hand orientation as (azimuth, elevation) in radians.
     *
     * Uses the WRIST → MIDDLE_MCP direction vector in screen-space as a proxy for
     * the hand's viewing angle relative to the camera. This is stable across all
     * hand poses and directly reflects how much of the dorsal vs. palmar surface
     * is visible, which is what matters for reconstruction coverage.
     *
     * Azimuth: rotation around Y (left/right hand rotation, -π to π)
     * Elevation: tilt above/below horizontal (-π/2 to π/2)
     */
    private fun wristOrientation(lms: HandLandmarks): Pair<Float, Float> {
        val w  = lms[com.arhand.tracking.LM.WRIST]
        val m  = lms[com.arhand.tracking.LM.MIDDLE_MCP]
        val dx = m.x - w.x
        val dy = m.y - w.y
        val dz = m.z - w.z
        val len = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz).coerceAtLeast(1e-6f)
        val az  = kotlin.math.atan2(dx / len, dz / len)
        val el  = kotlin.math.asin((-dy / len).coerceIn(-1f, 1f))
        return Pair(az, el)
    }

    private fun isNovel(az: Float, el: Float): Boolean {
        val threshold = Math.toRadians(NOVELTY_ANGLE_DEG.toDouble()).toFloat()
        for ((az2, el2) in acceptedOrientations) {
            val daz = az - az2
            val del = el - el2
            if (kotlin.math.sqrt(daz*daz + del*del) < threshold) return false
        }
        return true
    }

    private fun markCoverageBucket(az: Float, el: Float) {
        val azIdx = ((az + Math.PI.toFloat()) / (2f * Math.PI.toFloat()) * AZ_BUCKETS)
            .toInt().coerceIn(0, AZ_BUCKETS - 1)
        val elIdx = ((el + Math.PI.toFloat() / 2f) / Math.PI.toFloat() * EL_BUCKETS)
            .toInt().coerceIn(0, EL_BUCKETS - 1)
        val bucket = elIdx * AZ_BUCKETS + azIdx
        if (!coverageBuckets[bucket]) {
            coverageBuckets[bucket] = true
            coveredBuckets++
        }
    }

    /**
     * Sum of squared per-landmark 2D displacements between two frames.
     * Used to gate on motion velocity — too slow (redundant) or too fast (jitter).
     */
    private fun motionMagnitude(a: HandLandmarks, b: HandLandmarks): Float {
        if (a.size != b.size || a.isEmpty()) return Float.MAX_VALUE
        var sum = 0f
        for (i in a.indices) {
            val dx = a[i].x - b[i].x
            val dy = a[i].y - b[i].y
            sum += dx*dx + dy*dy
        }
        return sum
    }
}
