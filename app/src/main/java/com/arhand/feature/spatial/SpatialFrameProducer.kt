package com.arhand.feature.spatial

import android.app.Application
import android.graphics.Bitmap
import com.arhand.camera.BitmapGrayscaleShim
import com.arhand.camera.CameraFrameProvider
import com.arhand.depth.CrossChannelArbiter
import com.arhand.depth.DepthCarver
import com.arhand.depth.StructuredLightDepthSource
import com.arhand.depth.SpatialLayer
import com.arhand.mocap.BiomechanicalConstraintFilter
import com.arhand.mocap.BoneRetargeter
import com.arhand.mocap.BodyRetargeter
import com.arhand.mocap.QuaternionEmaFilter
import com.arhand.tracking.BodyPipeline
import com.arhand.scanner.CLAHEAnalyzer
import com.arhand.tracking.FacePipeline
import com.arhand.tracking.HandPipeline
import com.arhand.tracking.HandTrackerManager
import com.arhand.tracking.LM
import com.arhand.util.FrameThrottler
import com.arhand.util.ModelBudgetManager
import com.arhand.util.PerfMonitor
import com.arhand.util.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * SpatialFrameProducer — single owner of the camera loop and all sensing pipelines.
 *
 * ## Responsibility
 *
 * On each camera frame, this class:
 *   1. Feeds the bitmap to all depth sub-sources (SpatialLayer shim, SL pipeline)
 *   2. Submits to hand/body/face inference on throttled frames
 *   3. On each hand result: retargets joints, applies OEF/constraints/body-alignment
 *   4. Applies SL depth correction per landmark (fused-sl-v7 bilinear + gradient)
 *   5. Assembles one [SpatialFrame] with all fields populated
 *   6. Emits it via [frames] SharedFlow
 *
 * All consumers (OSC, BVH, renderer, scanner, HUD) subscribe to [frames]. Nothing
 * else touches the camera bitmap or the tracking pipelines directly.
 *
 * ## AppViewModel relationship
 *
 * AppViewModel holds one SpatialFrameProducer. It calls [init] once (after camera
 * init), [setBodyEnabled]/[setFaceEnabled] on toggle, and [close] on cleared.
 * AppViewModel's job is UI state and feature toggles — not frame assembly.
 *
 * ## SL depth integration (fused-sl-v7)
 *
 * When SL is calibrated, each hand landmark gets a SL-corrected z:
 *
 *   z_bilerp = bilinear(slDepthGrid, lm.x, lm.y)
 *   dz       = (gradX × (fx-0.5) + gradY × (fy-0.5)) × GRAD_SCALE
 *   z_final  = clamp(z_bilerp + dz, 0, 1)
 *
 * Occlusion is detected when |z_final - lm.z| > SpatializedHand.OCCLUSION_THRESH.
 */
class SpatialFrameProducer(
    private val app:          Application,
    private val scope:        CoroutineScope,
    val spatialLayer:         SpatialLayer,
    val slSource:             StructuredLightDepthSource,
    val handPipeline:         HandPipeline,
    val bodyPipeline:         BodyPipeline,
    val facePipeline:         FacePipeline,
    val bodyRetargeter:       BodyRetargeter,
    val frameThrottler:       FrameThrottler,
    val clahe:                CLAHEAnalyzer,
    val modelBudget:          ModelBudgetManager,
    val perfMonitor:          PerfMonitor
) {
    companion object {
        private const val SL_MAP_W = 8
        private const val SL_MAP_H = 6
        private const val GRAD_SCALE = 0.015f
        private const val STALE_FRAMES = 3  // S4.4: frames before landmark depth is stale
    }

    // ── Output ────────────────────────────────────────────────────────────────

    private val _frames = MutableSharedFlow<SpatialFrame>(extraBufferCapacity = 2)
    val frames: SharedFlow<SpatialFrame> = _frames

    /** Latest frame aspect ratio — read by scan tools. */
    val currentAspect = MutableStateFlow(9f / 16f)

    /** Latest raw bitmap — read by scan tools for texture baking. */
    @Volatile var latestBitmap: Bitmap? = null
        private set

    // S4.4: Per-landmark depth age — frames since last valid SL depth (2 hands × 21 landmarks)
    private val landmarkDepthAge = Array(2) { IntArray(21) }

    // ── Per-hand retargeters (owned here, not in ViewModel) ──────────────────

    private var boneRetargeter          = BoneRetargeter(com.arhand.mocap.AssetLoader.DEFAULT_PUPPET.bindPose)
    private var boneRetargeterSecondary = BoneRetargeter(com.arhand.mocap.AssetLoader.DEFAULT_PUPPET.bindPose)
    private val qEma                    = QuaternionEmaFilter()
    private val qEmaSecondary           = QuaternionEmaFilter()

    /** Call when a new asset is loaded to update the bind pose for retargeting. */
    fun onAssetLoaded(asset: com.arhand.mocap.LoadedAsset) {
        boneRetargeter          = BoneRetargeter(asset.bindPose)
        boneRetargeterSecondary = BoneRetargeter(asset.bindPose)
        qEma.reset(); qEmaSecondary.reset()
    }

    // ── Pipeline enable state ─────────────────────────────────────────────────

    @Volatile var bodyEnabled:       Boolean = false
    @Volatile var faceEnabled:       Boolean = false
    @Volatile var constraintEnabled: Boolean = true
    @Volatile var slEnabled:         Boolean = false
    @Volatile var isFrontCamera:     Boolean = true

    /**
     * Mirrors AppUiState.scanActive (kept in sync by a single collector in AppViewModel's
     * init, not by threading a second flag through every scan start/cancel/complete call
     * site — that duplication pattern is exactly what caused earlier bugs this session
     * where a lifecycle flag was set in some places but not others).
     */
    @Volatile var scanActive:        Boolean = false

    // ── Camera / depth infra ──────────────────────────────────────────────────

    internal val frameProvider = CameraFrameProvider()
    private val depthShim     = BitmapGrayscaleShim()
    private var trackerMgr:   HandTrackerManager? = null
    private var frameJob:     Job? = null

    fun init() {
        trackerMgr = HandTrackerManager(app) { rawHands, conf, ts ->
            handPipeline.update(rawHands, conf, ts, mirrorX = isFrontCamera)
        }
        trackerMgr!!.init()

        spatialLayer.start(depthShim)

        frameJob = scope.launch(Dispatchers.Default) {
            frameProvider.frames.collect { bitmap ->
                processBitmap(bitmap)
            }
        }

        // Collect body results → bodyRetargeter → latestBodyResult
        scope.launch {
            bodyPipeline.processed.collect { lms ->
                if (lms != null && bodyEnabled) {
                    _latestBodyResult.value = bodyRetargeter.retarget(lms)
                    _latestBodyLandmarks.value = lms
                } else {
                    _latestBodyResult.value   = null
                    _latestBodyLandmarks.value = null
                }
            }
        }
    }

    private val _latestBodyResult    = MutableStateFlow<com.arhand.mocap.BodyRetargetResult?>(null)
    private val _latestBodyLandmarks = MutableStateFlow<com.arhand.tracking.PoseLandmarks?>(null)

    fun setCameraTextureName(texId: Int) = spatialLayer.setCameraTextureName(texId)
    fun onGlFrame()                      = spatialLayer.onDrawFrame()

    fun setAspect(aspect: Float) { currentAspect.value = aspect }

    // ── Body / Face enable ───────────────────────────────────────────────────

    fun enableBody(enabled: Boolean) {
        bodyEnabled = enabled
        if (enabled) bodyPipeline.init(app) else bodyPipeline.close()
    }

    fun enableFace(enabled: Boolean) {
        faceEnabled = enabled
        if (enabled) facePipeline.init(app) else facePipeline.close()
    }

    // ── Core per-frame logic ──────────────────────────────────────────────────

    private fun processBitmap(bitmap: Bitmap) {
        latestBitmap = bitmap

        // Always feed spatial layer (ARCore / SfM / Photometric — always-on)
        depthShim.onBitmap(bitmap, System.currentTimeMillis())

        // SL depth — rear camera only, when enabled
        val slResult = if (slEnabled && !isFrontCamera) {
            slSource.processBitmap(bitmap)
            slSource.getLastResult()
        } else null

        // v27: SLAM + rPPG — always-on visual odometry and biometrics
        val flowSnapshot = spatialLayer.processBitmap(bitmap)

        // v27: DA2, DRASL, JBU — aux depth sources fed with current SL depth for JBU.
        // flowSnapshot is this exact bitmap's SlamLite reading, passed through rather than
        // read from a shared field, so it can't be overwritten by a later frame while this
        // one's DA2 inference is still in flight (ENGINE_ARCHITECTURE.md §5.2).
        spatialLayer.fusedDepth.processAuxSources(bitmap, slResult?.depth, scope, flowSnapshot)

        val ts    = System.currentTimeMillis()
        val nowMs = ts

        if (!frameThrottler.shouldInfer()) return

        val inferStart = System.currentTimeMillis()
        // clahe.lastContrastScore only feeds Scanner/FreeformScanner's quality gating
        // (AppViewModel passes it into scanner.update/freeformScanner.update) — nothing
        // reads it during plain live tracking, so only run this pass (Bitmap.createScaledBitmap
        // + 64-tile histogram + bilinear pass) while a scan is actually active.
        if (scanActive) clahe.process(bitmap)

        // Hand inference
        trackerMgr?.detect(bitmap, ts)

        // Body / face under budget
        val bodyActive = bodyEnabled
        val faceActive = faceEnabled
        val dec        = modelBudget.tick(bodyActive, faceActive)

        if (dec.submitBody && bodyActive) {
            val scaled = scaledIfNeeded(bitmap, dec.bodyThrottled)
            bodyPipeline.detect(scaled, ts)
        }
        if (dec.submitFace && faceActive) {
            val scaled = scaledIfNeeded(bitmap, dec.faceThrottled)
            facePipeline.detect(scaled, ts)
        }
        val inferMs = (System.currentTimeMillis() - inferStart).toFloat()
        perfMonitor.onInference(inferMs)
        frameThrottler.reportInferenceMs(inferMs, handPipeline.motionMag.value)
    }

    private fun scaledIfNeeded(bmp: Bitmap, throttled: Boolean): Bitmap {
        if (!throttled || bmp.width <= 320) return bmp
        val scale = 320f / bmp.width
        return Bitmap.createScaledBitmap(bmp, 320, (bmp.height * scale).toInt(), false)
    }

    // ── Hand results → SpatialFrame assembly ─────────────────────────────────

    /**
     * Called by AppViewModel's handPipeline.processed.collect subscriber.
     * Assembles and emits one SpatialFrame per hand-pipeline result.
     *
     * All SL depth correction, retargeting, body alignment, and occlusion detection
     * happen here — not in AppViewModel.
     */
    fun assembleFrame(
        hands:              List<com.arhand.tracking.ProcessedHand>,
        aspect:             Float,
        constraintEnabled:  Boolean
    ) {
        val bodyResult   = _latestBodyResult.value
        val bodyLms      = _latestBodyLandmarks.value
        val faceExpr     = facePipeline.expressions.value.takeIf { faceEnabled }
        val slRes        = if (slEnabled && !isFrontCamera) slSource.getLastResult() else null
        val spatState    = spatialLayer.state.value

        // Build 8×6 SL depth map for the frame
        val slDepthMap: FloatArray? = slRes?.let { buildSLMap(it) }

        // ── v27 scene understanding + biometrics ──────────────────────────────────
        val fused      = spatialLayer.fusedDepth
        val da2Blocks  = fused.da2.depthBlocks
        val metricMode = fused.metricMode
        val depthSrc   = da2Blocks ?: slDepthMap   // best available 8×6 depth grid

        val surfNormals    = depthSrc?.let { spatialLayer.normals.compute(it, SL_MAP_W, SL_MAP_H) }
        val aoMap          = depthSrc?.let { spatialLayer.ao.compute(it, SL_MAP_W, SL_MAP_H) }
        val detectedPlanes = depthSrc?.let { spatialLayer.planes.fit(it, SL_MAP_W, SL_MAP_H) } ?: emptyList()

        // S3.3: Feed far-plane metric distance to refine DA2 log-linear calibration
        if (metricMode) {
            detectedPlanes.firstOrNull { kotlin.math.abs(it.d) > 3f }?.let { plane ->
                spatialLayer.feedFarPlaneAnchor(kotlin.math.abs(plane.d))
            }
        }

        val slamSrc       = spatialLayer.slam
        val slamPoseSnap  = slamSrc.pose.takeIf  { slamSrc.featureCount > 0 }
        val slamDeltaSnap = slamSrc.delta.takeIf { slamSrc.featureCount > 0 }

        val rppgSrc   = spatialLayer.rppg
        val pspData   = fused.psp
        val pspSnap   = if (!pspData.isStale) pspData.phaseBlocks else null
        val jbuDepth  = fused.jbuDepthBlocks

        val wArr = fused.getMeanArbiterWeights()
        val fusWeights = FusionWeights(
            base   = wArr.getOrElse(CrossChannelArbiter.CH_BASE)   { 0f },
            sl     = wArr.getOrElse(CrossChannelArbiter.CH_SL)     { 0f },
            psp    = wArr.getOrElse(CrossChannelArbiter.CH_PSP)    { 0f },
            dvel   = wArr.getOrElse(CrossChannelArbiter.CH_DVEL)   { 0f },
            da2    = wArr.getOrElse(CrossChannelArbiter.CH_DA2)    { 0f },
            lca    = wArr.getOrElse(CrossChannelArbiter.CH_LCA)    { 0f },
            xr     = wArr.getOrElse(CrossChannelArbiter.CH_XR)     { 0f },
            pol    = wArr.getOrElse(CrossChannelArbiter.CH_POL)    { 0f },
            flare  = wArr.getOrElse(CrossChannelArbiter.CH_FLARE)  { 0f },
            moire  = wArr.getOrElse(CrossChannelArbiter.CH_MOIRE)  { 0f },
            rs     = wArr.getOrElse(CrossChannelArbiter.CH_RS)     { 0f },
            stereo = wArr.getOrElse(CrossChannelArbiter.CH_STEREO) { 0f }
        )
        val metricSrc = when {
            metricMode            -> "XR"
            da2Blocks != null     -> "DA2"
            slSource.isCalibrated -> "SL"
            else                  -> "NONE"
        }

        // ── Primary hand ───────────────────────────────────────────────────────
        val primary = hands.firstOrNull()
        val (primarySpatial, primaryRetarget) = if (primary != null) {
            val spatial = spatializeHand(primary.slotIndex, primary.landmarks, slRes)
            val bodyWristPos = bodyResult?.let {
                if (primary.slotIndex == 1) it.wristLeft else it.wristRight
            }
            val bodyWristVis = bodyResult?.confidence ?: 0f
            val raw = boneRetargeter.retarget(
                lms           = primary.landmarks,
                aspect        = aspect,
                mirrorX       = isFrontCamera,
                bodyWristHint = bodyWristPos,
                bodyWristVis  = bodyWristVis,
                // See AppViewModel's equivalent call — without the real camera-capture
                // aspect, landmarkToWorld's crop compensation silently no-ops.
                camAspect     = latestBitmap?.let { it.width.toFloat() / it.height.toFloat() } ?: aspect
            )
            val retarget = raw?.let { r ->
                val smoothed = qEma.apply(r)
                val constrained = if (constraintEnabled)
                    BiomechanicalConstraintFilter.apply(smoothed) else smoothed
                // Body-wrist world alignment
                if (bodyEnabled && bodyResult != null && bodyResult.confidence > 0.5f) {
                    val bw = if (primary.slotIndex == 1) bodyResult.wristLeft else bodyResult.wristRight
                    if (bw != null) constrained.copy(wristTransform = constrained.wristTransform.copy(position = bw))
                    else constrained
                } else constrained
            }
            Pair(spatial, retarget)
        } else Pair(null, null)

        // ── Secondary hand ─────────────────────────────────────────────────────
        val secondary = hands.firstOrNull { it.slotIndex == 1 }
        val (secondarySpatial, secondaryRetarget) = if (secondary != null) {
            val spatial = spatializeHand(secondary.slotIndex, secondary.landmarks, slRes)
            val bodyWristPos = bodyResult?.wristLeft
            val raw = boneRetargeterSecondary.retarget(
                lms           = secondary.landmarks,
                aspect        = aspect,
                mirrorX       = isFrontCamera,
                bodyWristHint = bodyWristPos,
                bodyWristVis  = bodyResult?.confidence ?: 0f,
                camAspect     = latestBitmap?.let { it.width.toFloat() / it.height.toFloat() } ?: aspect
            )
            val retarget = raw?.let { r ->
                val smoothed = qEmaSecondary.apply(r)
                if (constraintEnabled) BiomechanicalConstraintFilter.apply(smoothed) else smoothed
            }
            Pair(spatial, retarget)
        } else Pair(null, null)

        // ── Pipeline count ─────────────────────────────────────────────────────
        val activePipelines = 1 + (if (bodyEnabled) 1 else 0) + (if (faceEnabled) 1 else 0)
        val confidence      = (primaryRetarget?.wristTransform?.let { 1f } ?: 0f)

        // ── Emit SpatialFrame ──────────────────────────────────────────────────
        val frame = SpatialFrame(
            timestamp            = System.currentTimeMillis(),
            aspect               = aspect,
            isFrontCamera        = isFrontCamera,
            primaryHand          = primarySpatial,
            secondaryHand        = secondarySpatial,
            primaryRetarget      = primaryRetarget,
            secondaryRetarget    = secondaryRetarget,
            body                 = bodyResult?.takeIf { bodyEnabled },
            bodyLandmarks        = bodyLms?.takeIf { bodyEnabled },
            face                 = faceExpr,
            metricGrounded       = spatState.isGrounded,
            cameraWorldPos       = if (spatState.isGrounded)
                Vec3(spatState.cameraWorldX, spatState.cameraWorldY, spatState.cameraWorldZ) else null,
            depthConfidence      = spatState.depthConfidence,
            slDepth              = slDepthMap,
            slGradX              = slRes?.gradX,
            slGradY              = slRes?.gradY,
            slCalibrated         = slSource.isCalibrated,
            slDrifting           = slSource.isDrifting,
            da2Depth             = da2Blocks,
            fusedMeters          = if (metricMode) da2Blocks else null,
            pspProfile           = pspSnap,
            surfaceNormals       = surfNormals,
            ao                   = aoMap,
            planes               = detectedPlanes,
            slamPose             = slamPoseSnap,
            slamDelta            = slamDeltaSnap,
            rppgAmplitude        = rppgSrc.amplitude,
            rppgBPM              = rppgSrc.bpm,
            jbuDepth             = jbuDepth,
            fusionWeights        = fusWeights,
            metricSource         = metricSrc,
            frameConfidence      = confidence,
            activePipelineCount  = activePipelines
        )

        _frames.tryEmit(frame)
    }

    // ── SL per-landmark depth correction ─────────────────────────────────────

    /**
     * Apply bilinear SL depth + phase-gradient tilt correction per landmark.
     * S4.4: Falls back to DA2 bilinear depth when SL has been absent > STALE_FRAMES.
     */
    private fun spatializeHand(
        slotIndex: Int,
        lms:       com.arhand.tracking.HandLandmarks,
        slRes:     StructuredLightDepthSource.SLResult?
    ): SpatializedHand {
        val occluded  = BooleanArray(lms.size)
        val ageSlot   = slotIndex.coerceIn(0, 1)
        val depthAge  = landmarkDepthAge[ageSlot]
        val denseMap  = spatialLayer.fusedDepth.da2.denseDepth

        val slAvail = slRes != null && slSource.isCalibrated
        if (!slAvail && denseMap == null) {
            for (i in 0 until minOf(lms.size, 21)) depthAge[i]++
            return SpatializedHand(slotIndex, lms, null, occluded)
        }

        val correctedZ = FloatArray(lms.size)
        val bW = slRes?.bW ?: 0; val bH = slRes?.bH ?: 0

        for (i in lms.indices) {
            val lm     = lms[i]
            val ageIdx = i.coerceIn(0, 20)

            // Try SL depth correction
            var slZ: Float? = null
            if (slAvail && bW > 0) {
                val bxf = lm.x * bW; val byf = lm.y * bH
                val bxi = bxf.toInt().coerceIn(0, bW - 2)
                val byi = byf.toInt().coerceIn(0, bH - 2)
                val fx  = bxf - bxi; val fy  = byf - byi
                val i00 = byi * bW + bxi;       val i10 = byi * bW + (bxi + 1)
                val i01 = (byi + 1) * bW + bxi; val i11 = (byi + 1) * bW + (bxi + 1)
                if (slRes!!.depth.size > i11) {
                    val z = slRes.depth[i00]*(1f-fx)*(1f-fy) + slRes.depth[i10]*fx*(1f-fy) +
                            slRes.depth[i01]*(1f-fx)*fy       + slRes.depth[i11]*fx*fy
                    val gx = if (slRes.gradX.size > i11)
                        slRes.gradX[i00]*(1f-fx)*(1f-fy) + slRes.gradX[i10]*fx*(1f-fy) +
                        slRes.gradX[i01]*(1f-fx)*fy       + slRes.gradX[i11]*fx*fy else 0f
                    val gy = if (slRes.gradY.size > i11)
                        slRes.gradY[i00]*(1f-fx)*(1f-fy) + slRes.gradY[i10]*fx*(1f-fy) +
                        slRes.gradY[i01]*(1f-fx)*fy       + slRes.gradY[i11]*fx*fy else 0f
                    slZ = (z + (gx*(fx-0.5f) + gy*(fy-0.5f))*GRAD_SCALE).coerceIn(0f, 1f)
                }
            }

            // S4.4: Use SL when fresh; fall back to DA2 when SL has been absent too long
            val zFinal = when {
                slZ != null -> { depthAge[ageIdx] = 0; slZ }
                denseMap != null && depthAge[ageIdx] > STALE_FRAMES ->
                    spatialLayer.sampleDenseDepthAtLandmark(lm.x, lm.y, denseMap)
                else -> lm.z
            }
            if (slZ == null) depthAge[ageIdx]++

            correctedZ[i] = zFinal
            occluded[i]   = kotlin.math.abs(zFinal - lm.z) > SpatializedHand.OCCLUSION_THRESH
        }

        return SpatializedHand(slotIndex, lms, correctedZ, occluded)
    }

    /**
     * Downsample the full SL block grid to the 8×6 OSC map by block-averaging.
     * Matches the HTML sendOSC() map construction.
     */
    private fun buildSLMap(slRes: StructuredLightDepthSource.SLResult): FloatArray {
        val map   = FloatArray(SL_MAP_W * SL_MAP_H)
        val bW    = slRes.bW; val bH = slRes.bH
        val stepX = bW.toFloat() / SL_MAP_W
        val stepY = bH.toFloat() / SL_MAP_H
        for (my in 0 until SL_MAP_H) {
            for (mx in 0 until SL_MAP_W) {
                var sum = 0f; var cnt = 0
                val x0 = (mx * stepX).toInt(); val x1 = ((mx+1)*stepX).toInt().coerceAtMost(bW)
                val y0 = (my * stepY).toInt(); val y1 = ((my+1)*stepY).toInt().coerceAtMost(bH)
                for (by in y0 until y1) for (bx in x0 until x1) {
                    val idx = by * bW + bx
                    if (idx < slRes.depth.size) { sum += slRes.depth[idx]; cnt++ }
                }
                map[my * SL_MAP_W + mx] = if (cnt > 0) sum / cnt else 0f
            }
        }
        return map
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    fun close() {
        frameJob?.cancel()
        trackerMgr?.close()
        bodyPipeline.close()
        facePipeline.close()
        spatialLayer.close()
    }

    fun resetSLCalibration() = slSource.resetCalibration()
}
