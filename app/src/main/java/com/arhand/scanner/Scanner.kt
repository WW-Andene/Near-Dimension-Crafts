package com.arhand.scanner

import com.arhand.tracking.FaceExpressions
import com.arhand.tracking.HandLandmarks
import com.arhand.util.Vec3
import com.arhand.util.landmarkToWorld
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 8-pose scan state machine — port of SCANNER from the HTML prototype.
 *
 * States:
 *   IDLE → COUNTDOWN → CAPTURING → PROCESSING → DONE | FAILED
 *
 * Flow per pose:
 *   1. QualityEngine gates: must pass QUALITY_THRESHOLD
 *   2. Static pose: hold for holdMs at sufficient quality
 *   3. Motion pose: detect reps using motion score crossing threshold
 *   4. On completion: capture point cloud snapshot and advance to next pose
 */
class Scanner {

    enum class ScanState { IDLE, PREFLIGHT, COUNTDOWN, CAPTURING, PROCESSING, DONE, FAILED }

    data class ScanStatus(
        val state: ScanState = ScanState.IDLE,
        val poseIndex: Int = 0,
        val pose: ScanPose = ScanPoses.ALL[0],
        val quality: Float = 0f,
        val holdProgress: Float = 0f,   // 0..1 for static hold progress bar
        val repCount: Int = 0,          // current rep count for motion poses
        val countdownSec: Int = 3,
        val totalPoints: Int = 0,
        val poseScores: List<Float> = emptyList(),
        /** E3: Human-readable reason shown in FailedOverlay. Null = generic message. */
        val failureReason: String? = null,
        /** E5: Pre-flight quality score (0..1). Shown in PreflightOverlay. */
        val preflightScore: Float = 0f,
        /** E5: Number of preflight frames evaluated so far (out of PREFLIGHT_FRAMES). */
        val preflightProgress: Float = 0f,
        /**
         * BODY-5 — Body stability score (0–1). 1.0 = stable, 0.0 = drifted.
         * Null when body tracking is inactive.
         */
        val bodyStability: Float? = null
    )

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status

    /**
     * HAND-6 — Emits the completed pose index (0-based) each time a pose is successfully
     * captured. Collected by [com.arhand.ui.AppViewModel] to trigger incremental
     * [NeuralImplicitCarver.trainIncremental] passes during the scan.
     *
     * Uses replay=0 so late collectors don't receive stale completions from a previous scan.
     */
    private val _poseCaptureDone = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val poseCaptureDone: SharedFlow<Int> = _poseCaptureDone

    // BODY-5 — Body stability gate state
    /** Reference wrist position for the current pose (set on first accepted frame). */
    private var bodyRefWristX: Float = 0f
    private var bodyRefWristY: Float = 0f
    /** Reference shoulder midpoint Y for current pose. */
    private var bodyRefShoulderY: Float = 0f
    private var bodyRefSet: Boolean = false
    /** Last computed body stability score. Published to ScanStatus.bodyStability. */
    private var lastBodyStability: Float? = null

    companion object {
        /** BODY-5 — Maximum allowed normalised wrist drift between frames to pass stability gate. */
        const val BODY_DRIFT_THRESH = 0.08f
        /** E5 — Number of frames to evaluate during pre-flight before deciding. */
        const val PREFLIGHT_FRAMES = 30
        /** E5 — Minimum average quality score to allow scan start. */
        const val PREFLIGHT_MIN_QUALITY = 0.40f
    }

    private var poseIndex   = 0
    private var scanState   = ScanState.IDLE
    private var holdStartMs = 0L
    private var countdownStartMs = 0L

    // E5: Pre-flight quality evaluation state
    private var preflightFrameCount = 0
    private var preflightScoreSum   = 0f

    // Accumulated point cloud across all poses
    val cloudPoints = mutableListOf<Vec3>()
    private val poseScores = mutableListOf<Float>()

    // during the hold window. capturePosePoints selects top-weighted frames rather
    // than using only the single completion frame.
    private data class FrameCandidate(val lms: HandLandmarks, val compositeScore: Float)
    private val poseFrameBuffer = mutableListOf<FrameCandidate>()

    private val romAccumulator = RomAccumulator()
    /** ROM result — populated when the scan reaches PROCESSING state. */
    var romData: JointRomData? = null
        private set

    // Motion state
    private var lastMotionScore = 0f
    private var repCount        = 0
    private var motionPhase     = false   // true = moving in "open" direction
    // G6: Keep previous landmarks for temporal consistency scoring
    private var prevLms: HandLandmarks? = null

    fun start() {
        poseIndex = 0
        scanState = ScanState.PREFLIGHT
        preflightFrameCount = 0
        preflightScoreSum   = 0f
        holdStartMs      = 0L   // reset so the first STATIC pose hold timer starts clean
        countdownStartMs = 0L   // to complete instantly on restart if time has elapsed
        motionPhase      = false
        cloudPoints.clear()
        poseScores.clear()
        romAccumulator.reset()
        romData = null
        poseFrameBuffer.clear()
        repCount = 0
        prevLms = null
        emitStatus()
    }

    fun reset() {
        poseIndex = 0
        scanState = ScanState.IDLE
        cloudPoints.clear()
        poseScores.clear()
        romAccumulator.reset()
        romData = null
        poseFrameBuffer.clear()
        repCount = 0
        holdStartMs = 0L
        countdownStartMs = 0L
        preflightFrameCount = 0
        preflightScoreSum   = 0f
        prevLms = null
        emitStatus()
    }

    /**
     * Feed a processed hand frame.
     * Call every tracking frame while scan is active.
     */
    fun update(
        lms:            HandLandmarks?,
        claheContrast:  Float,
        nowMs:          Long,
        aspect:         Float,
        mirrorX:        Boolean = false,
        faceExpressions: FaceExpressions? = null,
        /**
         * BODY-5 — Optional body pose landmarks from [com.arhand.tracking.BodyPipeline].
         * When provided, a body stability gate rejects frames where the wrist or shoulder
         * position has drifted beyond [BODY_DRIFT_THRESH] from the pose reference position.
         */
        bodyLandmarks: com.arhand.tracking.PoseLandmarks? = null
    ) {
        when (scanState) {
            ScanState.PREFLIGHT  -> updatePreflight(lms, claheContrast, nowMs)
            ScanState.COUNTDOWN  -> updateCountdown(nowMs)
            ScanState.CAPTURING  -> updateCapture(lms, claheContrast, nowMs, aspect, mirrorX, faceExpressions, bodyLandmarks)
            else -> {}
        }
    }

    /**
     * E5 — Pre-flight quality check.
     * Evaluates [PREFLIGHT_FRAMES] frames of quality. If the average score >= [PREFLIGHT_MIN_QUALITY]
     * the scan advances to COUNTDOWN automatically. If quality is too low after all frames
     * the scan is FAILED with a descriptive reason so the user can reposition.
     */
    private fun updatePreflight(lms: HandLandmarks?, claheContrast: Float, nowMs: Long) {
        val quality = if (lms != null && lms.size >= 21) {
            QualityEngine.evaluate(lms, claheContrast, prevLms).score
        } else {
            0f
        }
        prevLms = lms

        preflightFrameCount++
        preflightScoreSum += quality

        val progress = preflightFrameCount.toFloat() / PREFLIGHT_FRAMES
        val avgScore = preflightScoreSum / preflightFrameCount

        _status.value = _status.value.copy(
            state            = ScanState.PREFLIGHT,
            preflightScore   = avgScore,
            preflightProgress = progress
        )

        if (preflightFrameCount >= PREFLIGHT_FRAMES) {
            if (avgScore >= PREFLIGHT_MIN_QUALITY) {
                // Quality is good — proceed to countdown
                scanState = ScanState.COUNTDOWN
                countdownStartMs = nowMs
                emitStatus()
            } else {
                // Quality too low — fail with actionable hint
                val reason = buildPreflightFailReason(avgScore)
                markFailed(reason)
            }
        }
    }

    private fun buildPreflightFailReason(avgScore: Float): String {
        return if (avgScore < 0.20f)
            "Hand not detected. Place your hand in the centre of the frame."
        else
            "Quality too low (${(avgScore * 100).toInt()}%). Move hand to centre and improve lighting."
    }

    private fun updateCountdown(nowMs: Long) {
        val elapsed = (nowMs - countdownStartMs) / 1000f
        val remaining = (3f - elapsed).toInt().coerceAtLeast(1)
        _status.value = _status.value.copy(
            state = ScanState.COUNTDOWN,
            countdownSec = remaining
        )
        if (elapsed >= 3f) {
            scanState = ScanState.CAPTURING
            holdStartMs = nowMs
            emitStatus()
        }
    }

    private fun updateCapture(
        lms: HandLandmarks?,
        claheContrast: Float,
        nowMs: Long,
        aspect: Float,
        mirrorX: Boolean,
        faceExpressions: FaceExpressions? = null,
        bodyLandmarks: com.arhand.tracking.PoseLandmarks? = null
    ) {
        if (lms == null) {
            emitStatus(); return
        }

        // If face data is absent (face tracking off or front-camera not used), gate stays open.
        val faceOk = faceExpressions == null ||
            (faceExpressions.leftBlink < 0.5f && faceExpressions.rightBlink < 0.5f)
        if (!faceOk) {

            // Without this, time elapsed during the blink is silently counted toward the hold,
            // causing the pose to advance too early on the frame after the blink ends.
            holdStartMs = 0L
            emitStatus(); return
        }

        val pose = ScanPoses.ALL[poseIndex]
        val quality = QualityEngine.evaluate(lms, claheContrast, prevLms)
        prevLms = lms

        // D5: accumulate joint angles every frame (visibility-gated inside accumulator)
        romAccumulator.update(lms, prevLms)

        // BODY-5 — Body stability gate.
        // When body landmarks are provided, gate acceptance on wrist position stability.
        // On first accepted frame per pose: record reference wrist position.
        // On subsequent frames: reject if wrist has drifted > BODY_DRIFT_THRESH.
        var bodyStable = true
        if (bodyLandmarks != null && bodyLandmarks.size > com.arhand.tracking.PL.LEFT_WRIST) {
            val wristLm = bodyLandmarks[com.arhand.tracking.PL.LEFT_WRIST]
            val wristX  = wristLm.x
            val wristY  = wristLm.y
            if (!bodyRefSet) {
                // First frame for this pose — set reference
                bodyRefWristX  = wristX
                bodyRefWristY  = wristY
                bodyRefSet     = true
                lastBodyStability = 1f
            } else {
                val dx = wristX - bodyRefWristX
                val dy = wristY - bodyRefWristY
                val drift = kotlin.math.sqrt(dx * dx + dy * dy)
                val stability = (1f - drift / BODY_DRIFT_THRESH).coerceIn(0f, 1f)
                lastBodyStability = stability
                if (drift > BODY_DRIFT_THRESH) bodyStable = false
            }
        } else {
            lastBodyStability = null
        }

        var holdProgress = 0f
        var advanced = false

        if (quality.passed && bodyStable) {

            // compositeScore = static quality × temporal consistency.
            val compositeScore = quality.score * quality.temporalConsistency
            poseFrameBuffer.add(FrameCandidate(lms, compositeScore))

            when (pose.type) {
                PoseType.STATIC -> {
                    if (holdStartMs == 0L) holdStartMs = nowMs
                    val held = nowMs - holdStartMs
                    holdProgress = (held.toFloat() / pose.holdMs).coerceIn(0f, 1f)
                    if (held >= pose.holdMs) advanced = true
                }
                PoseType.MOTION -> {
                    val score = when (pose.motionKey) {
                        "fingerCurl"  -> QualityEngine.fingerCurlScore(lms)
                        "wristRoll"   -> QualityEngine.wristRollScore(lms)
                        "fingerWave"  -> QualityEngine.fingerWaveScore(lms)
                        "thumbWave"   -> QualityEngine.thumbWaveScore(lms)
                        else          -> 0f
                    }
                    // Rep detection: crossing 0.5 threshold in each direction
                    if (!motionPhase && score > 0.65f) {
                        motionPhase = true
                    } else if (motionPhase && score < 0.35f) {
                        motionPhase = false
                        repCount++
                    }
                    holdProgress = (repCount.toFloat() / pose.reps).coerceIn(0f, 1f)
                    if (repCount >= pose.reps) advanced = true
                }
            }
        } else {
            holdStartMs = 0L
            motionPhase = false   // reset phase so re-entry doesn't fire a phantom rep
            // Reset repCount only when hand is fully absent (grace period exceeded resets
            // the pipeline), but here we reset on quality fail during motion to prevent
            // accumulated reps from a previous interrupted attempt being counted toward
            // the current hold window.
            repCount = 0
        }

        if (advanced) {
            // Capture snapshot points for this pose
            capturePosePoints(lms, aspect, quality.score, mirrorX)
            advancePose()
        } else {
            _status.value = _status.value.copy(
                state = ScanState.CAPTURING,
                poseIndex = poseIndex,
                pose = pose,
                quality = quality.score,
                holdProgress = holdProgress,
                repCount = repCount
            )
        }
    }

    private fun capturePosePoints(lms: HandLandmarks, aspect: Float, qualityScore: Float, mirrorX: Boolean) {

        // "Top-weighted" = highest compositeScore (quality × temporalConsistency).
        // Use top 50% of buffered frames, minimum 1. Fall back to the completion
        // frame if the buffer is empty (e.g. pose completed on the very first frame).
        val candidates = if (poseFrameBuffer.isNotEmpty()) {
            val sorted = poseFrameBuffer.sortedByDescending { it.compositeScore }
            val topN   = (sorted.size / 2).coerceAtLeast(1)
            sorted.take(topN)
        } else {
            listOf(FrameCandidate(lms, qualityScore))
        }

        val pairs = com.arhand.tracking.CONNECTIONS
        for (candidate in candidates) {
            for ((a, b) in pairs) {
                if (a >= candidate.lms.size || b >= candidate.lms.size) continue
                val pA = candidate.lms[a]; val pB = candidate.lms[b]
                for (t in 0..3) {
                    val tv = t / 3f
                    val x = pA.x + (pB.x - pA.x) * tv
                    val y = pA.y + (pB.y - pA.y) * tv
                    val z = pA.z + (pB.z - pA.z) * tv
                    val (wx, wy, wz) = landmarkToWorld(
                        com.arhand.tracking.Landmark(x, y, z), aspect, mirrorX = mirrorX
                    )
                    cloudPoints.add(Vec3(wx, wy, wz))
                }
            }
        }

        // Report the best composite score for this pose
        val bestScore = candidates.maxOf { it.compositeScore }.coerceIn(0f, 1f)
        poseScores.add(bestScore)
    }

    private fun advancePose() {
        // HAND-6 — Emit completed pose index before incrementing so collectors
        // receive the 0-based index of the pose that just finished.
        _poseCaptureDone.tryEmit(poseIndex)

        holdStartMs = 0L
        repCount = 0
        motionPhase = false
        poseFrameBuffer.clear()   // G6: reset frame buffer for next pose
        // BODY-5 — Reset reference position for the new pose
        bodyRefSet = false
        lastBodyStability = null
        poseIndex++

        if (poseIndex >= ScanPoses.COUNT) {
            // D5: finalise ROM from all accumulated frames
            romData = romAccumulator.build()
            scanState = ScanState.PROCESSING
            _status.value = _status.value.copy(
                state = ScanState.PROCESSING,
                totalPoints = cloudPoints.size,
                poseScores = poseScores.toList()
            )
        } else {
            scanState = ScanState.CAPTURING
            emitStatus()
        }
    }

    fun markDone() {
        scanState = ScanState.DONE
        _status.value = _status.value.copy(state = ScanState.DONE, totalPoints = cloudPoints.size)
    }

    /** E3: Mark the scan as failed. [reason] is shown in FailedOverlay; null = generic message. */
    fun markFailed(reason: String? = null) {
        scanState = ScanState.FAILED
        _status.value = _status.value.copy(state = ScanState.FAILED, failureReason = reason)
    }

    private fun emitStatus() {
        val pose = ScanPoses.ALL.getOrElse(poseIndex) { ScanPoses.ALL.last() }
        _status.value = _status.value.copy(
            state = scanState,
            poseIndex = poseIndex,
            pose = pose,
            totalPoints = cloudPoints.size,
            poseScores = poseScores.toList(),
            bodyStability = lastBodyStability   // BODY-5
        )
    }
}
