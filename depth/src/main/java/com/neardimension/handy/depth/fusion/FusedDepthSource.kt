package com.arhand.depth.fusion

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.arhand.depth.ArCoreDepthSource
import com.arhand.depth.CrossChannelArbiter
import com.arhand.depth.DarkRoomAdaptiveSL
import com.arhand.depth.DepthAnythingSource
import com.arhand.depth.DepthSource
import com.arhand.depth.DepthSourceCallback
import com.arhand.depth.FlareDetector
import com.arhand.depth.JointBilateralUpsampler
import com.arhand.depth.MoireDetector
import com.arhand.depth.PhaseShiftingProfilometry
import com.arhand.depth.PhotometricDepthSource
import com.arhand.depth.RollingShutterStereo
import com.arhand.depth.SfMDepthSource
import com.arhand.depth.StereoDepthSource
import com.arhand.util.PointCloudStore
import com.arhand.camera.BitmapGrayscaleShim
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * FusedDepthSource — software LiDAR.
 *
 * Combines three complementary depth signals into a single coherent [PointCloudStore]:
 *
 *   ARCore  → metric scale + world-space anchor. Best quality when tracking. Confidence 1.0.
 *             Always-on — started in [start].
 *   SfM     → optical flow triangulation. Works without ARCore. Confidence 0.6.
 *             When ARCore is available, SfM depth is rescaled to metric using the
 *             dynamically calibrated [sfmScale] instead of the hardcoded PX_TO_M guess.
 *             Always-on — started in [start].
 *   Photometric → surface normals from torch on/off pairs. Adds surface topology detail
 *             that neither ARCore nor SfM can resolve. Confidence 0.35.
 *             When ARCore scale is known, photometric depth is rescaled to match.
 *             Reconstruction-only — started/stopped by [setReconstructionActive], not [start],
 *             since it drives the physical torch on/off every frame it runs.
 *
 * Dual-camera stereo, rolling-shutter stereo, and phase-shifting profilometry are
 * additional reconstruction-only channels folded into the arbiter (see
 * [CrossChannelArbiter]) — also gated by [setReconstructionActive].
 *
 * ## Scale calibration (ARCore ↔ SfM)
 *
 * SfM's `PX_TO_M = 0.001f` is a guess. Real scale depends on the device's sensor size,
 * optics, and the scene depth. ARCore gives us a ground truth: each frame we have both
 * an ARCore metric camera displacement (metres) and a SfM pixel displacement (pixels).
 *
 *   sfmScale = arcore_displacement_m / sfm_displacement_px
 *
 * This is computed as an EMA over recent frames to smooth out single-frame noise.
 * Once [sfmScale] is warm (> [SCALE_MIN_SAMPLES] frames), SfM depth values are
 * multiplied by `sfmScale / PX_TO_M` to convert from the SfM coordinate frame into metres.
 *
 * ## Continuity fallback
 *
 * When ARCore tracking drops (PAUSED or STOPPED), SfM continues uninterrupted — it only
 * needs the camera feed. The last valid [sfmScale] is held so SfM output remains metric
 * until ARCore recovers. Photometric likewise continues independently.
 *
 * ## Voxel deduplication
 *
 * All three sources push into a shared [PointCloudStore] via [onPoints]. Points that map
 * to the same [ArCoreDepthSource.voxelKey] are deduplicated by keeping the highest
 * confidence value — ARCore beats SfM beats Photometric at the same voxel.
 *
 * ## Degradation ladder
 *
 * | Hardware            | Active sources          | Quality                   |
 * |---------------------|-------------------------|---------------------------|
 * | ARCore depth hw     | ARCore + SfM + Photo    | Full — metric, continuous |
 * | No ARCore depth hw  | SfM + Photo             | Relative, continuous      |
 * | No torch            | ARCore (or SfM alone)   | Metric/relative, no normals|
 * | Emulator            | SfM only                | Relative only             |
 *
 * [isAvailable] always returns true — at minimum, SfM works on any device with a camera.
 */
class FusedDepthSource(
    private val context: Context,
    val store: PointCloudStore = PointCloudStore()
) : DepthSource {

    override val mode = DepthSource.Mode.SFM  // composite; mode is informational only

    // ─── Sub-sources ──────────────────────────────────────────────────────────

    val arcore      = ArCoreDepthSource(context)
    val sfm         = SfMDepthSource(context)
    val photometric = PhotometricDepthSource(context)

    // v27 additional sources
    val da2         = DepthAnythingSource(context)
    val psp         = PhaseShiftingProfilometry()
    val drasl       = DarkRoomAdaptiveSL()
    val rsStereo    = RollingShutterStereo()
    val jbu         = JointBilateralUpsampler()
    val flareDetector = FlareDetector()
    val moireDetector = MoireDetector()
    /** S3.1 — Dual-lens stereo depth. Probes Camera2 at start; no-ops when hardware absent. */
    val stereo      = StereoDepthSource(context)

    /** True when ARCore is actively tracking and depth is metric. */
    @Volatile var metricMode: Boolean = false
        private set

    /** JBU-upsampled (16×12) depth blocks from the most recent SL map. Null when unavailable. */
    @Volatile var jbuDepthBlocks: FloatArray? = null
        private set

    /** True once the scale calibration has warmed up. */
    var sfmScaleWarm = false
        private set

    /** S1.3 — True once sfmScale has converged (< 5% variance over 30 frames) and frozen. */
    @Volatile var sfmScaleFrozen: Boolean = false
        private set
    private val scaleHistory  = FloatArray(30)
    private var scaleHistIdx  = 0
    private var scaleHistFull = false

    /** Current dynamic SfM→metric scale factor (metres per SfM depth unit). */
    var sfmScale: Float = PX_TO_M_DEFAULT
        private set

    private var sfmScaleSamples = 0

    /** S1.2/S3.3 — Last ARCore mean scene depth for dual-anchor DA2 calibration (metres). */
    @Volatile var lastArcoreMeanDepth: Float = Float.NaN
        private set

    /** Wall-clock time [lastArcoreMeanDepth] was last written — see [feedFarPlaneAnchor]. */
    @Volatile private var lastArcoreMeanDepthMs: Long = 0L

    /** Last ARCore world-space camera position (x, y, z in metres). */
    private var arcoreCamX = Float.NaN
    private var arcoreCamY = Float.NaN
    private var arcoreCamZ = Float.NaN
    /**
     * Wall-clock time of the last ARCore callback — see the time-bound check in
     * [updateScaleCalibration]. Also exposed for [SpatialFrame]'s §5.1 age tagging: [metricMode]
     * is set from this same callback, so this timestamp is its freshness signal too.
     */
    @Volatile var lastArcoreCallbackMs = 0L
        private set

    /** Last SfM cumulative camera position (pixels). */
    private var sfmCamPx = 0f
    private var sfmCamPy = 0f

    // Voxel dedup map: key → best confidence seen so far

    private val MAX_VOXELS = 200_000
    private val voxelConf = LinkedHashMap<Long, Float>(MAX_VOXELS, 0.75f, false)
    private var voxelFlushCounter = 0

    // ─── DepthSource lifecycle ────────────────────────────────────────────────

    override fun isAvailable() = true

    // CrossChannelArbiter — v27 12-channel per-block (8×6 = 48) confidence weighting.
    // Each spatial block gets its own agreement weights, so DA2's spatial variance drives
    // the arbiter independently per image region.
    private val arbiter = CrossChannelArbiter(blockCount = BLOCK_COUNT)

    // Per-channel per-block signal arrays: srcSignals[channelIdx][blockIdx]
    private val srcSignals       = Array(CrossChannelArbiter.CHANNEL_COUNT) { FloatArray(BLOCK_COUNT) }

    // Pre-allocated scratch arrays — avoid per-frame allocation on the hot path
    private val zeroBlocks       = FloatArray(BLOCK_COUNT)
    private val slBoostedBlocks  = FloatArray(BLOCK_COUNT)
    private val da2GatedBlocks   = FloatArray(BLOCK_COUNT)  // S2.1/S2.2 luma-gated DA2 signal
    private val dvelGatedBlocks  = FloatArray(BLOCK_COUNT)  // S2.1 luma-gated SfM signal

    // Arbiter weights cached after recomputeArbiter(); indexed [blockIdx × CHANNEL_COUNT + chIdx]
    private val cachedWeights    = FloatArray(BLOCK_COUNT * CrossChannelArbiter.CHANNEL_COUNT)

    /**
     * ENGINE_ARCHITECTURE.md §5.1 — wall-clock time [cachedWeights] was last recomputed.
     * `SpatialFrameProducer.assembleFrame()` reads `getMeanArbiterWeights()` at the throttled
     * hand-inference rate while this is written at raw-frame rate; exposed so a `SpatialFrame`
     * consumer can see how old the weights actually are instead of assuming they're synchronised
     * to the bundled hand landmarks.
     */
    @Volatile var arbiterWeightsTimestampMs: Long = 0L
        private set

    // Pre-allocated result buffer for getMeanArbiterWeights() — avoids per-frame allocation
    // (called once per assembled SpatialFrame from SpatialFrameProducer.assembleFrame).
    private val meanWeightsBuf   = FloatArray(CrossChannelArbiter.CHANNEL_COUNT)

    // S1.1 — ALS sensor for outdoor SL/PSP suppression
    @Volatile private var lastLux: Float = 0f
    private var sensorManager: SensorManager? = null
    private var alsListener: SensorEventListener? = null

    // S2.5 — Cached mean luma for low-light HUD label
    @Volatile private var lastLuma255: Float = 128f

    /** Broadcast a frame-level [confidence] to all blocks for channel [chIdx]. */
    private fun updateSourceSignal(chIdx: Int, confidence: Float) {
        if (chIdx in srcSignals.indices) srcSignals[chIdx].fill(confidence.coerceIn(0f, 1f))
    }

    /** Copy [blocks] (length ≤ BLOCK_COUNT) into the per-block signal for channel [chIdx]. */
    private fun updateSourceSignalBlocks(chIdx: Int, blocks: FloatArray) {
        if (chIdx in srcSignals.indices)
            blocks.copyInto(srcSignals[chIdx], endIndex = minOf(blocks.size, BLOCK_COUNT))
    }

    /** S2.5 — Return the low-light override label when mean luma is below the dark threshold. */
    private fun lowLightLabel(base: String): String =
        if (lastLuma255 < 20f) "LOW LIGHT — HANDS ONLY" else base

    /**
     * Recompute per-block arbiter weights from all current source signals.
     * Call once per frame at the end of [processAuxSources]; results cached in [cachedWeights].
     */
    private fun recomputeArbiter() {
        val luma255 = drasl.lastMeanLuma  // 0–255 range
        lastLuma255 = luma255             // S2.5: cache for HUD label
        val draslMult = if (luma255 < DarkRoomAdaptiveSL.DARK_LUMA_THRESHOLD)
            1f + (DarkRoomAdaptiveSL.MAX_BOOST - 1f) * (1f - luma255 / DarkRoomAdaptiveSL.DARK_LUMA_THRESHOLD)
        else 1f

        // S1.1: ALS multiplier — zero active sources in direct sunlight (useless outdoors)
        val alsMult = when {
            lastLux > 15000f -> 0f
            lastLux > 2000f  -> 0.5f
            else             -> 1f
        }

        // S2.2: DA2 luma confidence scalar — discount DA2 when sensor is noise-floored in dark
        val lumaScalar = (luma255 / 51f).coerceIn(0f, 1f)  // full weight at ≥51/255 ≈ 20%

        // S2.1: Luma-gated DA2 — zero in full dark, capped in dim conditions
        val da2RawSig = srcSignals[CrossChannelArbiter.CH_DA2]
        for (i in 0 until BLOCK_COUNT) {
            val scaled = da2RawSig[i] * lumaScalar
            da2GatedBlocks[i] = when {
                luma255 < 20f -> 0f
                luma255 < 64f -> scaled.coerceAtMost(0.65f)
                else          -> scaled
            }
        }

        // S2.1: Luma-gated SfM — zero in full dark (feature tracking degrades below noise floor)
        val dvelSig = srcSignals[CrossChannelArbiter.CH_DVEL]
        for (i in 0 until BLOCK_COUNT) {
            dvelGatedBlocks[i] = if (luma255 < 20f) 0f else dvelSig[i]
        }

        // S1.1: SL boosted by DRASL dark-room multiplier, gated by ALS outdoor suppression
        val slSig = srcSignals[CrossChannelArbiter.CH_SL]
        for (i in 0 until BLOCK_COUNT) slBoostedBlocks[i] = slSig[i] * draslMult * alsMult

        val pspBlocks = if (!psp.isStale && lastLux <= 15000f) srcSignals[CrossChannelArbiter.CH_PSP] else zeroBlocks

        // S3.1: Feed stereo depth blocks when available (CH_STEREO base weight = 0.01)
        val stereoBlocks = if (stereo.isAvailable && stereo.confidence > 0f) {
            stereo.depthBlocks?.also { updateSourceSignalBlocks(CrossChannelArbiter.CH_STEREO, it) }
            srcSignals[CrossChannelArbiter.CH_STEREO]
        } else zeroBlocks

        // RS-Stereo: feed the real computed signal instead of discarding it. Its
        // baseWeight prior is 0 (a handheld phone's natural tremor gives a sub-millimetre
        // baseline — negligible SNR in the common case) but during an active scan the
        // camera sweeps deliberately (see the "rotate your hand freely" freeform-scan
        // prompt), which is exactly when the baseline — and so this channel's actual
        // usefulness — is largest. Let the arbiter's per-block agreement gate decide its
        // contribution each frame rather than hard-zeroing it before that gate ever runs.
        val rsBlocks = rsStereo.lastDepthBlocks?.also {
            updateSourceSignalBlocks(CrossChannelArbiter.CH_RS, it)
        }?.let { srcSignals[CrossChannelArbiter.CH_RS] } ?: zeroBlocks

        arbiter.compute(
            base   = srcSignals[CrossChannelArbiter.CH_BASE],
            sl     = slBoostedBlocks,
            psp    = pspBlocks,
            dvel   = dvelGatedBlocks,
            da2    = da2GatedBlocks,
            lca    = srcSignals[CrossChannelArbiter.CH_LCA],
            xr     = srcSignals[CrossChannelArbiter.CH_XR],
            // POL (polarimetric depth) permanently zeroed — needs a polarization-filter
            // sensor phone cameras don't have. Not a gap to close in software.
            pol    = zeroBlocks,
            // FLARE/MOIRE are frame-level scalars (updateSourceSignal fills every
            // block with the same value) — see FlareDetector/MoireDetector.
            flare  = srcSignals[CrossChannelArbiter.CH_FLARE],
            moire  = srcSignals[CrossChannelArbiter.CH_MOIRE],
            rs     = rsBlocks,
            stereo = stereoBlocks
        ).copyInto(cachedWeights)
        arbiterWeightsTimestampMs = System.currentTimeMillis()
    }

    /**
     * Return the arbitrated confidence for a point in block [blockIdx] from channel [chIdx].
     * Uses the per-block weights cached by [recomputeArbiter].
     */
    private fun arbitratedConf(chIdx: Int, blockIdx: Int, rawConf: Float): Float {
        val w = cachedWeights[blockIdx * CrossChannelArbiter.CHANNEL_COUNT + chIdx].coerceIn(0f, 1f)
        return rawConf * w
    }

    /**
     * Map a world-space point (px, py, pz) to a block index in the S3.2 3-tier pyramid:
     *   Tier 0 (coarse 2×2, full frame):        idx  0–3   (dist ≥ 5 m)
     *   Tier 1 (medium 4×4, full frame):         idx  4–19  (2 m ≤ dist < 5 m, or out-of-center)
     *   Tier 2 (fine   8×4, center 60% width):  idx 20–51  (dist < 2 m, nx ∈ [0.20, 0.80])
     * Falls back to centre of Tier 1 (block 9) when camera position is unavailable.
     */
    private fun worldSpaceToBlockIdx(px: Float, py: Float, pz: Float): Int {
        val cx = arcore.lastCamX; val cy = arcore.lastCamY; val cz = arcore.lastCamZ
        if (cx.isNaN()) return 9  // centre of Tier 1 (4×4 grid, centre block = 4+1*4+1)
        val dist = sqrt((px-cx)*(px-cx) + (py-cy)*(py-cy) + (pz-cz)*(pz-cz)).coerceAtLeast(0.01f)
        val nx = ((px - cx) / dist / TAN_HALF_FOV * 0.5f + 0.5f).coerceIn(0f, 1f)
        val ny = ((py - cy) / dist / TAN_HALF_FOV * 0.5f + 0.5f).coerceIn(0f, 1f)
        return when {
            dist < 2f && nx >= 0.20f && nx <= 0.80f -> {
                // Tier 2: fine 8×4, center 60% band
                val cxFrac = (nx - 0.20f) / 0.60f
                val bx = (cxFrac * 8f).toInt().coerceIn(0, 7)
                val by = (ny * 4f).toInt().coerceIn(0, 3)
                20 + by * 8 + bx
            }
            dist < 5f -> {
                // Tier 1: medium 4×4, full frame
                val bx = (nx * 4f).toInt().coerceIn(0, 3)
                val by = (ny * 4f).toInt().coerceIn(0, 3)
                4 + by * 4 + bx
            }
            else -> {
                // Tier 0: coarse 2×2, full frame
                val bx = (nx * 2f).toInt().coerceIn(0, 1)
                val by = (ny * 2f).toInt().coerceIn(0, 1)
                by * 2 + bx
            }
        }
    }

    // Callback retained so setReconstructionActive() can (re)start photometric/stereo
    // on demand instead of only at the one-time start() call.
    private var savedCallback: DepthSourceCallback? = null

    /**
     * Whether the reconstruction-only sub-sources (photometric stereo, dual-camera
     * stereo, rolling-shutter stereo, phase-shifting profilometry) are active.
     *
     * These exist purely to build depth *reconstruction* quality for scanning —
     * unlike ARCore/SfM (always-on metric grounding for streaming/recording) or DA2
     * (feeds core hand-landmark Z correction), nothing outside a scan reads their
     * output. Photometric stereo in particular toggles the physical torch on/off
     * every frame while active, so running it unconditionally for the app's entire
     * lifetime — as this used to — is both a major performance cost and a visibly
     * flickering torch for users who are just tracking/streaming, not scanning.
     */
    @Volatile var reconstructionActive: Boolean = false
        private set

    override fun start(callback: DepthSourceCallback) {
        store.clear()
        voxelConf.clear()
        sfmScale = PX_TO_M_DEFAULT
        sfmScaleWarm = false
        sfmScaleSamples = 0
        rsStereo.reset()
        metricMode          = false
        sfmScaleFrozen      = false
        scaleHistIdx        = 0
        scaleHistFull       = false
        lastArcoreMeanDepth   = Float.NaN
        lastArcoreMeanDepthMs = 0L
        savedCallback       = callback

        // S1.1: Register ALS sensor — one reading per second is sufficient
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = sm
        val als = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (als != null) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) { lastLux = event.values[0] }
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }
            alsListener = listener
            sm.registerListener(listener, als, SensorManager.SENSOR_DELAY_NORMAL)
        }

        if (arcore.isAvailable()) {
            arcore.start(ArcoreCallback(callback))
        } else {
            callback.onStats(DepthSource.Stats(extraLabel = "ARCore unavailable — SfM+Photo only"))
        }

        sfm.start(SfmCallback(callback))
        // photometric + stereo are NOT started here — see setReconstructionActive().
    }

    /**
     * Start or stop the reconstruction-only sub-sources. Call with `true` when a scan
     * begins (posed or freeform) and `false` when it ends/cancels — see [reconstructionActive].
     */
    fun setReconstructionActive(active: Boolean) {
        if (active == reconstructionActive) return
        reconstructionActive = active
        val callback = savedCallback ?: return
        if (active) {
            photometric.start(PhotoCallback(callback))
            stereo.start()  // S3.1: probe and open second camera if available
        } else {
            photometric.stop()
            stereo.stop()
        }
    }

    /**
     * Release ARCore's hold on the rear camera before [com.arhand.camera.CameraController]
     * switches onto it, and reacquire once it switches away — see
     * [ArCoreDepthSource.pauseCameraHold] / ENGINE_ARCHITECTURE.md §4.9.
     */
    fun pauseArcoreCameraHold()  = arcore.pauseCameraHold()
    fun resumeArcoreCameraHold() = arcore.resumeCameraHold()

    override fun stop() {
        alsListener?.let { sensorManager?.unregisterListener(it) }
        alsListener = null
        arcore.stop()
        sfm.stop()
        photometric.stop()
        stereo.stop()
        reconstructionActive = false
        savedCallback = null
    }

    /**
     * Return the mean arbiter weight for each channel across all [BLOCK_COUNT] blocks.
     * Result length = [CrossChannelArbiter.CHANNEL_COUNT] (12). Indexed by CH_* constants.
     * Caller converts to [FusionWeights] — kept as FloatArray to avoid a cross-module dependency.
     */
    fun getMeanArbiterWeights(): FloatArray {
        val n = CrossChannelArbiter.CHANNEL_COUNT
        // Reuse pre-allocated buffer — caller must not hold onto the reference across frames
        for (c in 0 until n) {
            var sum = 0f
            for (b in 0 until BLOCK_COUNT) sum += cachedWeights[b * n + c]
            meanWeightsBuf[c] = sum / BLOCK_COUNT
        }
        return meanWeightsBuf
    }

    /**
     * S3.3 — Feed a far-plane metric distance to refine DA2 log-linear calibration.
     * Call when PlaneFitter detects a floor/wall/ceiling at > 3 m.
     * Requires [lastArcoreMeanDepth] as a near anchor reference.
     *
     * ENGINE_ARCHITECTURE.md §5.5 — [lastArcoreMeanDepth] is written only from ARCore's own
     * callback, which can fire at an unrelated moment relative to this call (driven by plane
     * detection on the camera-frame path). Skip if the near-anchor reading is older than
     * [MAX_ARCORE_CALLBACK_GAP_MS] rather than calibrating a fresh far plane against a stale
     * near depth — same shape and threshold as [updateScaleCalibration]'s §5.4 fix.
     */
    fun feedFarPlaneAnchor(planeDistM: Float) {
        if (planeDistM < 3f || lastArcoreMeanDepth.isNaN()) return
        if (System.currentTimeMillis() - lastArcoreMeanDepthMs > MAX_ARCORE_CALLBACK_GAP_MS) return
        val invNear = da2.sampleAtNormalized(0.5f, 0.5f)
        val invFar  = da2.sampleAtNormalized(0.5f, 0.75f)
        if (invNear > 1e-4f && invFar > 1e-4f && kotlin.math.abs(invNear - invFar) > 1e-4f) {
            da2.setDualAnchor(zNear = lastArcoreMeanDepth, invNear = invNear, zFar = planeDistM, invFar = invFar)
        }
    }

    fun close() {
        stop()
        arcore.close()
        da2.close()
        stereo.close()
    }

    /**
     * Process auxiliary v27 sources (DA2, RS-Stereo, DRASL, JBU) against the current
     * camera frame. Call once per frame from [SpatialFrameProducer] on the camera thread.
     *
     * @param bitmap  Current camera frame
     * @param slDepth 8×6 SL depth blocks for JBU upsampling, or null
     * @param scope   Coroutine scope for DA2 async inference
     * @param flow    SlamLite optical-flow reading for this exact [bitmap] (see
     *   [DepthAnythingSource.FlowSnapshot]) — from [SpatialLayer.processBitmap]'s return value.
     */
    fun processAuxSources(
        bitmap: Bitmap,
        slDepth: FloatArray?,
        scope: CoroutineScope,
        flow: DepthAnythingSource.FlowSnapshot
    ) {
        // Reconstruction-only sources: only useful while actually scanning — see
        // reconstructionActive's doc. Skipping them the rest of the time is most of
        // the fix for the app being unconditionally CPU/camera-pipeline heavy.
        if (reconstructionActive) {
            // DRASL — update luma; boost applied in recomputeArbiter
            drasl.analyse(bitmap)

            // RS-Stereo — feeds CH_RS in recomputeArbiter
            rsStereo.process(bitmap)

            // PSP — tick stale counter (capture is UI-triggered via psp.startCapture())
            psp.tick()

            // S3.1: Stereo depth — capture slave frame and compute disparity against main frame
            if (stereo.isAvailable && lastLux <= 15000f) {
                stereo.requestCapture(scope, bitmap)
            }

            // FLARE / MOIRE — frame-level artefact-confidence signals for CH_FLARE/CH_MOIRE
            updateSourceSignal(CrossChannelArbiter.CH_FLARE, flareDetector.analyse(bitmap))
            updateSourceSignal(CrossChannelArbiter.CH_MOIRE, moireDetector.analyse(bitmap))
        }

        // DA2 stays always-on: its dense depth map also feeds core hand-landmark Z
        // correction (SpatialFrameProducer.spatializeHand), not just reconstruction.
        if (da2.isAvailable) {
            da2.processAsync(bitmap, flow, scope)
            updateSourceSignal(CrossChannelArbiter.CH_DA2, da2.confidence)
            da2.depthBlocks?.let { updateSourceSignalBlocks(CrossChannelArbiter.CH_DA2, it) }
        }

        // JBU — upsample SL depth to 2× resolution using camera frame as guide
        if (slDepth != null) {
            jbuDepthBlocks = jbu.upsample(slDepth, 8, 6, bitmap)
        }

        da2.outdoorMode = lastLux > 5000f  // S3.4: outdoor scene context scaling

        // Recompute per-block arbiter weights for this frame
        recomputeArbiter()
    }

    // ─── GL thread hook (forward to ARCore) ───────────────────────────────────

    /**
     * Must be called from [android.opengl.GLSurfaceView.Renderer.onDrawFrame].
     * Required by ARCore's EGL context contract.
     */
    fun onDrawFrame() = arcore.onDrawFrame()

    fun setCameraTextureName(texId: Int) = arcore.setCameraTextureName(texId)

    // ─── Shim wiring (eliminates second camera session) ───────────────────────

    /**
     * Attach a [BitmapGrayscaleShim] to both SfM and Photometric sources.
     * Must be called before [start]. See [BitmapGrayscaleShim] for rationale.
     */
    fun attachShim(shim: BitmapGrayscaleShim) {
        sfm.attachShim(shim)
        photometric.attachShim(shim)
    }

    // ─── Scale calibration ────────────────────────────────────────────────────

    /**
     * Called when ARCore reports a new camera pose. Updates the dynamic SfM scale
     * by correlating ARCore metric displacement with SfM pixel displacement.
     *
     * @param camX  ARCore camera position X in world space (metres)
     * @param camY  ARCore camera position Y in world space (metres)
     * @param camZ  ARCore camera position Z in world space (metres)
     */
    private fun updateScaleCalibration(camX: Float, camY: Float, camZ: Float) {
        if (sfmScaleFrozen) return  // S1.3: scale converged and frozen — hold forever

        val prevX = arcoreCamX; val prevY = arcoreCamY; val prevZ = arcoreCamZ
        val prevCallbackMs = lastArcoreCallbackMs
        val now = System.currentTimeMillis()

        arcoreCamX = camX; arcoreCamY = camY; arcoreCamZ = camZ
        lastArcoreCallbackMs = now

        if (prevX.isNaN()) return  // first frame

        // ENGINE_ARCHITECTURE.md §5.4 — irregular ARCore callback timing under load could
        // otherwise compare a large-but-old displacement against fresh SfM pixel displacement.
        // Treat a gap this large the same as "first frame": update the position baseline
        // above, but skip this round's scale update rather than calibrating off a stale pair.
        if (now - prevCallbackMs > MAX_ARCORE_CALLBACK_GAP_MS) return

        val arcoreDisp = sqrt(
            (camX - prevX) * (camX - prevX) +
            (camY - prevY) * (camY - prevY) +
            (camZ - prevZ) * (camZ - prevZ)
        )

        val sfmDisp = sqrt(sfmCamPx * sfmCamPx + sfmCamPy * sfmCamPy)

        if (arcoreDisp < 0.001f || sfmDisp < 0.5f) return

        val newScale = arcoreDisp / sfmDisp
        if (newScale < PX_TO_M_DEFAULT * 0.1f || newScale > PX_TO_M_DEFAULT * 10f) return

        val alpha = if (sfmScaleWarm) SCALE_EMA_ALPHA else SCALE_EMA_ALPHA * 4f
        sfmScale = sfmScale * (1f - alpha) + newScale * alpha
        sfmScaleSamples++
        if (sfmScaleSamples >= SCALE_MIN_SAMPLES) sfmScaleWarm = true

        // S1.3: Track convergence — freeze once < 5% variance over 30 consecutive frames
        scaleHistory[scaleHistIdx % 30] = sfmScale
        scaleHistIdx++
        if (!scaleHistFull && scaleHistIdx >= 30) scaleHistFull = true
        if (scaleHistFull) {
            val mean = scaleHistory.average().toFloat()
            if (mean > 0f && scaleHistory.maxOf { kotlin.math.abs(it - mean) } / mean < 0.05f) {
                sfmScaleFrozen = true
            }
        }
    }

    // ─── Voxel deduplication ──────────────────────────────────────────────────

    /**
     * Accept a batch of (x,y,z,conf) points, deduplicate by voxel, and push survivors
     * into [store].
     *
     * Higher confidence wins at a contested voxel — this is the mechanism by which
     * ARCore naturally dominates over SfM which dominates over Photometric.
     *
     * [voxelConf] is periodically flushed (every 300 batches) to avoid unbounded growth
     * when the camera moves far from the initial position.
     */
    // Scratch buffer for acceptPoints output — resized lazily on first oversized batch
    private var acceptScratch = FloatArray(0)

    private fun acceptPoints(batch: FloatArray, len: Int) {
        val n = len / 4
        if (n == 0) return

        if (acceptScratch.size < len) acceptScratch = FloatArray(len)
        val out = acceptScratch
        var outIdx = 0

        for (i in 0 until n) {
            val x = batch[i * 4];     val y = batch[i * 4 + 1]
            val z = batch[i * 4 + 2]; val c = batch[i * 4 + 3]

            val key = ArCoreDepthSource.voxelKey(x, y, z, VOXEL_CELL)
            val existing = voxelConf[key] ?: -1f

            if (c > existing) {
                voxelConf[key] = c
                out[outIdx++] = x; out[outIdx++] = y
                out[outIdx++] = z; out[outIdx++] = c
            }
        }

        if (outIdx > 0) store.push(out, outIdx)

        // lowest-confidence half — these are typically SfM/Photometric peripheral points
        // that ARCore has since overwritten at higher confidence.
        if (++voxelFlushCounter > 60) {
            voxelFlushCounter = 0
            if (voxelConf.size > MAX_VOXELS) {
                val threshold = voxelConf.values.sorted().getOrElse(MAX_VOXELS / 2) { 0.5f }
                val iter = voxelConf.iterator()
                while (iter.hasNext()) {
                    if (iter.next().value < threshold) iter.remove()
                }
            }
        }
    }

    // ─── Source-specific callbacks ────────────────────────────────────────────

    // Per-callback scratch buffers — resized lazily when the batch grows
    private var arcoreArbScratch = FloatArray(0)
    private var sfmOutScratch    = FloatArray(0)
    private var photoOutScratch  = FloatArray(0)

    private inner class ArcoreCallback(val outer: DepthSourceCallback) : DepthSourceCallback {
        override fun onPoints(batch: FloatArray, len: Int) {
            val n = len / 4
            if (arcoreArbScratch.size < len) arcoreArbScratch = FloatArray(len)
            val arb = arcoreArbScratch
            for (i in 0 until n) {
                val wx = batch[i*4]; val wy = batch[i*4+1]; val wz = batch[i*4+2]
                arb[i*4] = wx; arb[i*4+1] = wy; arb[i*4+2] = wz
                val bi = worldSpaceToBlockIdx(wx, wy, wz)
                arb[i*4+3] = arbitratedConf(CrossChannelArbiter.CH_XR, bi, batch[i*4+3])
            }
            acceptPoints(arb, len)
            val cx = arcore.lastCamX
            if (!cx.isNaN()) updateScaleCalibration(cx, arcore.lastCamY, arcore.lastCamZ)
            metricMode = !arcore.lastCamX.isNaN()

            // Calibrate DA2 from ARCore point cloud.
            if (metricMode && n > 0 && da2.isAvailable) {
                val camX = arcore.lastCamX; val camY = arcore.lastCamY; val camZ = arcore.lastCamZ
                var sumDist = 0f
                var minDist = Float.MAX_VALUE; var maxDist = 0f
                var minWx = camX; var minWy = camY; var minWz = camZ
                var maxWx = camX; var maxWy = camY; var maxWz = camZ
                for (i in 0 until n) {
                    val dx = batch[i*4] - camX; val dy = batch[i*4+1] - camY; val dz = batch[i*4+2] - camZ
                    val dist = sqrt(dx*dx + dy*dy + dz*dz)
                    sumDist += dist
                    if (dist < minDist) { minDist = dist; minWx = batch[i*4]; minWy = batch[i*4+1]; minWz = batch[i*4+2] }
                    if (dist > maxDist) { maxDist = dist; maxWx = batch[i*4]; maxWy = batch[i*4+1]; maxWz = batch[i*4+2] }
                }
                val meanDepth = (sumDist / n).coerceIn(0.1f, 8f)
                lastArcoreMeanDepth   = meanDepth
                lastArcoreMeanDepthMs = System.currentTimeMillis()

                val da2Centre = da2.sampleAtNormalized(0.5f, 0.5f)
                if (da2Centre > 1e-4f) {
                    da2.setXrAnchor(scale = meanDepth * da2Centre, shift = 0f)
                }

                // S1.2: Dual-anchor log-linear calibration when point cloud spans > 1 m
                if (maxDist - minDist > 1f) {
                    fun worldToNorm(wx: Float, wy: Float, wz: Float): Pair<Float, Float> {
                        val dx = wx - camX; val dy = wy - camY; val dz = wz - camZ
                        val d = sqrt(dx*dx + dy*dy + dz*dz).coerceAtLeast(0.01f)
                        val nx = ((dx / d / TAN_HALF_FOV + 1f) / 2f).coerceIn(0.05f, 0.95f)
                        val ny = ((dy / d / TAN_HALF_FOV + 1f) / 2f).coerceIn(0.05f, 0.95f)
                        return Pair(nx, ny)
                    }
                    val (nearNx, nearNy) = worldToNorm(minWx, minWy, minWz)
                    val (farNx,  farNy)  = worldToNorm(maxWx, maxWy, maxWz)
                    val invNear = da2.sampleAtNormalized(nearNx, nearNy)
                    val invFar  = da2.sampleAtNormalized(farNx,  farNy)
                    if (invNear > 1e-4f && invFar > 1e-4f) {
                        da2.setDualAnchor(zNear = minDist, invNear = invNear, zFar = maxDist, invFar = invFar)
                    }
                }
            }
        }
        override fun onStats(stats: DepthSource.Stats) {
            updateSourceSignal(CrossChannelArbiter.CH_XR, stats.confidenceMean)
            outer.onStats(stats.copy(extraLabel = lowLightLabel("Fused/ARCore")))
        }
        override fun onError(msg: String) = outer.onError("ARCore: $msg")
    }

    private inner class SfmCallback(val outer: DepthSourceCallback) : DepthSourceCallback {
        override fun onPoints(batch: FloatArray, len: Int) {
            val n = len / 4
            if (n == 0) return
            val scaleFactor = if (sfmScaleWarm) sfmScale / PX_TO_M_DEFAULT else 1f
            if (sfmOutScratch.size < len) sfmOutScratch = FloatArray(len)
            val out = sfmOutScratch
            for (i in 0 until n) {
                val sx = batch[i*4] * scaleFactor; val sy = batch[i*4+1] * scaleFactor
                val sz = batch[i*4+2] * scaleFactor
                out[i*4] = sx; out[i*4+1] = sy; out[i*4+2] = sz
                val bi = worldSpaceToBlockIdx(sx, sy, sz)
                out[i*4+3] = arbitratedConf(CrossChannelArbiter.CH_DVEL, bi, batch[i*4+3] * CONF_SFM_FACTOR)
            }
            acceptPoints(out, len)
        }
        override fun onStats(stats: DepthSource.Stats) {
            updateSourceSignal(CrossChannelArbiter.CH_DVEL, stats.confidenceMean)
            sfmCamPx = stats.baselinePx
            sfmCamPy = 0f
            val sfmLabel = if (sfmScaleWarm) "Fused/SfM(calibrated)" else "Fused/SfM(raw)"
            outer.onStats(stats.copy(extraLabel = lowLightLabel(sfmLabel)))
        }
        override fun onError(msg: String) = outer.onError("SfM: $msg")
    }

    private inner class PhotoCallback(val outer: DepthSourceCallback) : DepthSourceCallback {
        override fun onPoints(batch: FloatArray, len: Int) {
            val stride = 7
            val n = len / stride
            if (n == 0) return
            val scaleFactor = if (sfmScaleWarm) sfmScale / SfMDepthSource.PX_TO_M else 1f
            if (photoOutScratch.size < n * 4) photoOutScratch = FloatArray(n * 4)
            val out = photoOutScratch
            for (i in 0 until n) {
                val base = i * stride
                val x = batch[base]     * scaleFactor
                val y = batch[base + 1] * scaleFactor
                val z = batch[base + 2] * scaleFactor
                val rawConf = batch[base + 3]
                val nz = batch[base + 6]
                val viewAlign = kotlin.math.abs(nz).coerceIn(0f, 1f)
                val bi = worldSpaceToBlockIdx(x, y, z)
                val conf = arbitratedConf(CrossChannelArbiter.CH_BASE, bi, rawConf * CONF_PHOTO_FACTOR * viewAlign)
                out[i*4] = x; out[i*4+1] = y; out[i*4+2] = z; out[i*4+3] = conf
            }
            acceptPoints(out, n * 4)
        }
        override fun onStats(stats: DepthSource.Stats) {
            updateSourceSignal(CrossChannelArbiter.CH_BASE, stats.confidenceMean)
            outer.onStats(stats.copy(extraLabel = lowLightLabel("Fused/Photo(normals)")))
        }
        override fun onError(msg: String) = outer.onError("Photometric: $msg")
    }

    companion object {
        // Voxel cell size for deduplication (metres)
        private const val VOXEL_CELL = 0.005f

        // S3.2: Spatial block count — 3-tier pyramid (2×2 + 4×4 + 8×4-center) = 52 blocks
        private const val BLOCK_COUNT = 52

        // SfM scale calibration
        private const val SCALE_EMA_ALPHA  = 0.05f   // slow EMA — stable over many frames
        private const val SCALE_MIN_SAMPLES = 5       // Warm-up: fast EMA alpha, converges in ~5 frames.
        private const val PX_TO_M_DEFAULT  = 0.001f  // SfMDepthSource hardcoded fallback
        // ENGINE_ARCHITECTURE.md §5.4 — ARCore callbacks normally arrive every camera frame
        // (well under 100ms); a gap this large means the "previous" pose is too old to treat
        // as adjacent to the current one for a displacement-based scale update.
        private const val MAX_ARCORE_CALLBACK_GAP_MS = 500L

        // Confidence weights per source — ARCore always wins at a contested voxel
        private const val CONF_ARCORE      = 1.0f
        private const val CONF_SFM_FACTOR  = 0.6f    // multiplied into SfM's internal conf
        private const val CONF_PHOTO_FACTOR = 0.35f  // multiplied into Photometric's conf

        // Approximate half-FOV tangent for rough image-space block projection
        private const val TAN_HALF_FOV = 0.577f  // tan(30°) ≈ 60° full horizontal FOV
    }
}
