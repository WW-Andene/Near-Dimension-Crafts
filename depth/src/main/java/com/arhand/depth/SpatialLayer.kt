package com.arhand.depth

import android.content.Context
import com.arhand.camera.BitmapGrayscaleShim
import com.arhand.depth.fusion.FusedDepthSource
import com.arhand.util.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistent spatial sensing layer — the foundation of Handy as a LiDAR replacement.
 *
 * ## Purpose
 *
 * Previously, depth sensing (ARCore + SfM + Photometric) was gated behind a scan-mode
 * toggle. This meant streaming, recording, and VTubing sessions had no metric spatial
 * grounding — wrist positions were in camera-normalised space, not real-world metres.
 *
 * [SpatialLayer] makes metric grounding **always-on** from camera start via ARCore + SfM
 * only. It provides:
 *
 *   1. **Metric grounding** — when ARCore is tracking, [isGrounded] = true and
 *      [cameraWorldX/Y/Z] give the device's metric position. All downstream consumers
 *      (retargeter, OSC streamer, BVH recorder) can tag their output as metric.
 *
 *   2. **Live point cloud** — [fusedDepth].store is updated by ARCore + SfM always, and
 *      additionally by the reconstruction-only sources (photometric stereo, dual-camera
 *      stereo, RS-stereo, PSP) while [setReconstructionActive] is on — see that method's
 *      doc for why those specific sources are scan-gated rather than always-on (chiefly:
 *      photometric stereo drives the physical torch on/off every frame it runs).
 *
 *   3. **SfM scale calibration** — [metricScale] is the dynamically calibrated
 *      metres-per-SfM-unit factor. When ARCore is unavailable, SfM output is still
 *      self-consistent and approximately metric after warm-up.
 *
 *   4. **Degradation ladder** — on devices without ARCore depth hardware, SfM continues
 *      independently for grounding. [isGrounded] reflects ARCore availability specifically;
 *      [depthActive] reflects any depth source running.
 *
 * ## Lifecycle
 *
 * [start] is called once from [AppViewModel.initCamera], not from the scan flow.
 * [stop] is called from [AppViewModel.onCleared].
 *
 * The scan tools (posed [Scanner], [FreeformScanner]) accumulate frames into the
 * [fusedDepth].store and TSDF without owning the depth session lifecycle.
 *
 * ## State exposure
 *
 * [state] is a [StateFlow<SpatialState>] collected by the HUD and ViewModel.
 * It is updated every ARCore frame — at the GL thread's frame rate (~30–60fps) via
 * [onDrawFrame], which is already called from [ARRenderer.onDrawFrame].
 */
class SpatialLayer(private val context: Context) {

    data class SpatialState(
        /** True when ARCore has achieved TRACKING state and positions are metric. */
        val isGrounded:    Boolean = false,
        /** True when at least one depth source (ARCore, SfM, or Photometric) is active. */
        val depthActive:   Boolean = false,
        /** ARCore camera X in world space (metres). NaN when not grounded. */
        val cameraWorldX:  Float   = Float.NaN,
        /** ARCore camera Y in world space (metres). NaN when not grounded. */
        val cameraWorldY:  Float   = Float.NaN,
        /** ARCore camera Z in world space (metres). NaN when not grounded. */
        val cameraWorldZ:  Float   = Float.NaN,
        /**
         * Calibrated metric scale: metres per world-space unit used by SfM.
         * 0.001 = default (uncalibrated). Increases accuracy once ARCore warm-up completes.
         */
        val metricScale:   Float   = 0.001f,
        /**
         * Mean depth confidence in the current live point cloud (0–1).
         * 1.0 = all ARCore high-confidence points. Lower when SfM/Photometric dominate.
         */
        val depthConfidence: Float = 0f,
        /** Live point count in the shared PointCloudStore. */
        val pointCount:    Int     = 0,
        /** Human-readable active source label for HUD display. */
        val sourceLabel:   String  = "SPATIAL OFF"
    )

    private val _state = MutableStateFlow(SpatialState())
    val state: StateFlow<SpatialState> = _state

    val fusedDepth = FusedDepthSource(context)

    // ── v27 always-on sensing ─────────────────────────────────────────────────

    /** Visual SLAM — accumulates camera pose and per-frame delta each bitmap frame. */
    val slam    = SlamLite()
    /** Surface normals from depth gradients — used for plane fitting and AO. */
    val normals = SurfaceNormals()
    /** Ambient occlusion proxy from neighbour depth comparison. */
    val ao      = AmbientOcclusion()
    /** RANSAC floor/wall/ceiling plane detector. */
    val planes  = PlaneFitter()
    /**
     * Persistent, spatially-deduplicated room-scale point map — off by default, gated by
     * [setRoomMapActive]. Distinct from [fusedDepth]'s [PointCloudStore][com.arhand.util.PointCloudStore]
     * (`fusedDepth.store`), which is a capacity-bounded, age-ordered live buffer for the active
     * scan: this instead keeps one best-confidence sample per 5mm world-space cell, with no
     * age-based eviction, so walking around a room accumulates a stable map instead of a
     * recency-windowed stream. See [roomMapActive]/[setRoomMapActive]/[roomMapPoints].
     */
    val voxelGrid = VoxelGrid()

    private var started = false

    /** True while incoming depth points are also being accumulated into [voxelGrid]. */
    @Volatile var roomMapActive: Boolean = false
        private set

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start all depth sub-sources. Call once after camera init, not per-scan.
     *
     * @param shim    Bitmap shim that routes the running camera frames to SfM/Photometric
     *                without opening a second camera session.
     * @param texId   GL texture ID for ARCore's camera feed (from the GL surface).
     *                Pass 0 if the GL surface hasn't been created yet — call
     *                [setCameraTextureName] once it is.
     */
    fun start(shim: BitmapGrayscaleShim, texId: Int = 0) {
        if (started) return
        started = true

        fusedDepth.attachShim(shim)
        if (texId != 0) fusedDepth.setCameraTextureName(texId)

        fusedDepth.start(object : DepthSourceCallback {
            override fun onPoints(batch: FloatArray, len: Int) {
                // Points go directly into fusedDepth.store — no extra routing needed.
                // Scan tools (TSDF, FreeformScanner) read from store on their own cadence.
                // Additionally fed into voxelGrid, but only while roomMapActive — unlike
                // fusedDepth.store, this isn't part of the always-on baseline cost; it's an
                // opt-in feature (see setRoomMapActive), same shape as setReconstructionActive.
                if (roomMapActive) voxelGrid.update(batch, len)
            }
            override fun onStats(stats: DepthSource.Stats) {
                val arcoreCamX = fusedDepth.arcore.lastCamX
                val grounded   = !arcoreCamX.isNaN()
                _state.value = _state.value.copy(
                    isGrounded      = grounded,
                    depthActive     = true,
                    cameraWorldX    = arcoreCamX,
                    cameraWorldY    = fusedDepth.arcore.lastCamY,
                    cameraWorldZ    = fusedDepth.arcore.lastCamZ,
                    metricScale     = fusedDepth.sfmScale,
                    depthConfidence = stats.confidenceMean,
                    pointCount      = fusedDepth.store.pointCount,
                    sourceLabel     = stats.extraLabel.ifBlank { "SPATIAL" }
                )
            }
            override fun onError(msg: String) {
                android.util.Log.w("SpatialLayer", "Depth error: $msg")
            }
        })

        _state.value = _state.value.copy(depthActive = true, sourceLabel = "STARTING…")
    }

    /**
     * Start or stop [FusedDepthSource]'s reconstruction-only sub-sources (photometric
     * stereo, dual-camera stereo, rolling-shutter stereo, phase-shifting profilometry).
     * Call with `true` when a scan starts (posed or freeform) and `false` when it
     * ends/cancels. ARCore/SfM metric grounding and SLAM stay always-on regardless — only
     * the scan-reconstruction-specific sources are gated.
     */
    fun setReconstructionActive(active: Boolean) = fusedDepth.setReconstructionActive(active)

    /**
     * Start or stop accumulating incoming depth points into [voxelGrid] (see its class doc).
     * Off by default — this is an opt-in room-mapping feature, not part of the always-on
     * baseline, so there's no accumulation cost unless a caller actually wants the map.
     * Turning off does not clear already-accumulated points; call [clearRoomMap] for that.
     */
    fun setRoomMapActive(active: Boolean) {
        roomMapActive = active
    }

    /** Current room-map points as (x, y, z) triples, dropping confidence — e.g. for export. */
    fun roomMapPoints(): List<Vec3> {
        val flat = voxelGrid.exportAsArray()
        val out = ArrayList<Vec3>(flat.size / 4)
        var i = 0
        while (i < flat.size) {
            out.add(Vec3(flat[i], flat[i + 1], flat[i + 2]))
            i += 4
        }
        return out
    }

    /** Discard all accumulated room-map points without affecting [roomMapActive]. */
    fun clearRoomMap() = voxelGrid.reset()

    /**
     * Release ARCore's hold on the rear camera before [com.arhand.camera.CameraController]
     * switches onto it, and reacquire once it switches away again — call around
     * [com.arhand.camera.CameraController.switchCamera] (ENGINE_ARCHITECTURE.md §4.9).
     */
    fun pauseArcoreCameraHold()  = fusedDepth.pauseArcoreCameraHold()
    fun resumeArcoreCameraHold() = fusedDepth.resumeArcoreCameraHold()

    /**
     * Process [bitmap] through the always-on v27 sensing pipeline.
     * Call once per camera frame from [SpatialFrameProducer.processBitmap].
     *
     * @return This frame's SlamLite optical-flow reading, for the caller to pass into
     *   [com.arhand.depth.fusion.FusedDepthSource.processAuxSources] alongside the same
     *   [bitmap] — see [DepthAnythingSource.FlowSnapshot] for why this is a return value
     *   rather than a field DA2 reads whenever it gets around to running (§5.2).
     */
    fun processBitmap(bitmap: android.graphics.Bitmap): DepthAnythingSource.FlowSnapshot {
        slam.process(bitmap)
        return DepthAnythingSource.FlowSnapshot(slam.meanFlowMag, slam.medianFlowNX, slam.medianFlowNY)
    }

    fun stop() {
        if (!started) return
        started = false
        fusedDepth.stop()
        slam.reset()
        roomMapActive = false
        voxelGrid.reset()
        _state.value = SpatialState()
    }

    fun close() {
        stop()
        fusedDepth.close()
    }

    /** S3.3 — Feed a far-plane metric distance for DA2 log-linear recalibration. */
    fun feedFarPlaneAnchor(planeDistM: Float) = fusedDepth.feedFarPlaneAnchor(planeDistM)

    /** Must be called from the GL thread after surface creation. */
    fun setCameraTextureName(texId: Int) = fusedDepth.setCameraTextureName(texId)

    /** Must be called from [ARRenderer.onDrawFrame] — drives ARCore's session.update(). */
    fun onDrawFrame() = fusedDepth.onDrawFrame()

    /** Feed a bitmap frame to SfM and Photometric sub-sources. Call every camera frame. */
    fun onBitmap(bitmap: android.graphics.Bitmap, timestampMs: Long) =
        fusedDepth.arcore.let {
            // The shim already routes bitmaps — this call is for external callers
            // who want to explicitly push a frame (e.g. from a separate camera source).
        }

    // ── SL phase-gradient landmark z-correction ───────────────────────────────

    /**
     * Fused-sl-v7 port: sample the SL depth map at a landmark's normalised screen
     * position using bilinear interpolation, then apply the phase-gradient tilt
     * correction to get a sub-block-accurate depth estimate.
     *
     * Mirrors `bilinearDepthWithNormal()` from fused-sl-v7.html:
     *
     *   z_bilinear = bilinear(depthMap, nx, ny)
     *   dz = (gradX * (fx - 0.5) + gradY * (fy - 0.5)) * GRAD_SCALE
     *   z_corrected = clamp(z_bilinear + dz, 0, 1)
     *
     * where (fx, fy) is the sub-block fractional position of the landmark.
     *
     * @param slSource   The [StructuredLightDepthSource] (may be null when SL not active)
     * @param nx         Landmark normalised x [0..1]
     * @param ny         Landmark normalised y [0..1]
     * @param depthMap   Per-block fused depth (from [StructuredLightDepthSource.getLastResult])
     * @return           Gradient-corrected normalised depth, or null if SL not calibrated
     */
    fun sampleSLDepthAtLandmark(
        slSource: StructuredLightDepthSource?,
        nx:       Float,
        ny:       Float,
        depthMap: FloatArray?
    ): Float? {
        val result = slSource?.getLastResult() ?: return null
        if (!slSource.isCalibrated || depthMap == null) return null

        val bW = result.bW; val bH = result.bH
        val bxf = nx * bW; val byf = ny * bH
        val bxi = bxf.toInt().coerceIn(0, bW - 2)
        val byi = byf.toInt().coerceIn(0, bH - 2)
        val fx  = bxf - bxi; val fy = byf - byi

        val i00 = byi * bW + bxi;       val i10 = byi * bW + (bxi + 1)
        val i01 = (byi + 1) * bW + bxi; val i11 = (byi + 1) * bW + (bxi + 1)

        // Bilinear interpolation of depth
        val z = depthMap[i00] * (1f - fx) * (1f - fy) +
                depthMap[i10] * fx         * (1f - fy) +
                depthMap[i01] * (1f - fx)  * fy        +
                depthMap[i11] * fx         * fy

        // Bilinear interpolation of phase gradient
        val gradX = result.gradX
        val gradY = result.gradY
        val gx = gradX[i00] * (1f - fx) * (1f - fy) +
                 gradX[i10] * fx         * (1f - fy) +
                 gradX[i01] * (1f - fx)  * fy        +
                 gradX[i11] * fx         * fy
        val gy = gradY[i00] * (1f - fx) * (1f - fy) +
                 gradY[i10] * fx         * (1f - fy) +
                 gradY[i01] * (1f - fx)  * fy        +
                 gradY[i11] * fx         * fy

        return slSource.gradientCorrectZ(z, gx, gy, fx, fy)
    }

    /**
     * Sample the DA2 dense depth map at a landmark's normalised screen position using
     * bilinear interpolation. Prefer this over [sampleSLDepthAtLandmark] when
     * [DepthAnythingSource.denseDepth] is non-null — the 128×96 dense map has 16× the
     * spatial resolution of the legacy 8×6 SL block grid.
     *
     * @param nx       Landmark normalised X [0..1]
     * @param ny       Landmark normalised Y [0..1]
     * @param denseMap Dense depth map from [DepthAnythingSource.denseDepth]
     * @param denseW   Map width (default [DepthAnythingSource.DENSE_W])
     * @param denseH   Map height (default [DepthAnythingSource.DENSE_H])
     * @return Bilinear-interpolated depth at (nx, ny)
     */
    fun sampleDenseDepthAtLandmark(
        nx: Float,
        ny: Float,
        denseMap: FloatArray,
        denseW: Int = DepthAnythingSource.DENSE_W,
        denseH: Int = DepthAnythingSource.DENSE_H
    ): Float {
        val srcX = (nx * (denseW - 1)).coerceIn(0f, (denseW - 1).toFloat())
        val srcY = (ny * (denseH - 1)).coerceIn(0f, (denseH - 1).toFloat())
        val x0 = srcX.toInt().coerceIn(0, denseW - 2)
        val y0 = srcY.toInt().coerceIn(0, denseH - 2)
        val fx = srcX - x0; val fy = srcY - y0
        return denseMap[y0 * denseW + x0]         * (1-fx) * (1-fy) +
               denseMap[y0 * denseW + x0 + 1]     * fx     * (1-fy) +
               denseMap[(y0+1) * denseW + x0]     * (1-fx) * fy     +
               denseMap[(y0+1) * denseW + x0 + 1] * fx     * fy
    }

    // ── World-frame anchor ────────────────────────────────────────────────────

    /**
     * ENGINE_ARCHITECTURE.md §4.11 — correct per-landmark world position.
     *
     * This replaces the old GAP-1 `toWorldSpace(mediaPipeWorldX/Y/Z)` (deleted): that method
     * rotated MediaPipe's *world* landmark (metres, but re-centred to the hand's own
     * geometric centroid every frame — see `HandTracker.kt`'s own doc comment) by the
     * camera's rotation and added it to the camera's world position, treating a
     * hand-relative offset as if it were a camera-relative one. It had zero callers and,
     * per §4.11's analysis, could not have worked: MediaPipe discards the hand's actual
     * distance from the camera when it re-centres world landmarks, so there was no
     * translation left to recover by composing a pose onto them.
     *
     * The correct fix goes back to *before* that re-centring: it takes the landmark's own
     * normalised 2D pixel position plus a real measured camera-space depth at that pixel
     * (DA2's XR-calibrated dense map — the same source [sampleDenseDepthAtLandmark] reads,
     * but only trusted here once [DepthAnythingSource.isMetricCalibrated] is true), unprojects
     * through the camera's real intrinsics into camera space, then transforms by the camera's
     * live ARCore pose — exactly the same unprojection [ArCoreDepthSource.onDrawFrame] already
     * performs per-pixel for its own depth cloud, just evaluated at one landmark's pixel
     * instead of a dense grid. That keeps this landmark's result in the *same* coordinate
     * frame [com.arhand.util.PointCloudStore]'s ARCore/SfM cloud is already in, which is what
     * callers like [HandSegmentationMask.buildHullMetric] actually need to cross-reference
     * against it.
     *
     * @return world-space position in metres (ARCore's world frame), or null when metric
     *         depth isn't currently available (not tracking, or DA2 hasn't XR-calibrated
     *         yet) — callers must have a fallback for that case (e.g. skip hull filtering
     *         rather than silently using a wrong frame).
     */
    fun unprojectLandmarkToWorld(normX: Float, normY: Float): Vec3? {
        val arcore = fusedDepth.arcore
        val pose   = arcore.lastPose ?: return null
        if (arcore.lastFx.isNaN() || arcore.lastImgW <= 0) return null

        val da2 = fusedDepth.da2
        if (!da2.isMetricCalibrated) return null
        val denseMap = da2.denseDepth ?: return null

        val depthM = sampleDenseDepthAtLandmark(normX, normY, denseMap)
        if (depthM.isNaN() || depthM <= 0f) return null

        val px = normX * arcore.lastImgW
        val py = normY * arcore.lastImgH
        val lx =  (px - arcore.lastCx) * depthM / arcore.lastFx
        val ly =  (py - arcore.lastCy) * depthM / arcore.lastFy
        val lz = -depthM

        val wp = FloatArray(3)
        pose.transformPoint(floatArrayOf(lx, ly, lz), 0, wp, 0)
        return Vec3(wp[0], wp[1], wp[2])
    }
}
