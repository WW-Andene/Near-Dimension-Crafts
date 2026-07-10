package com.arhand.depth

import android.content.Context
import android.media.Image
import android.opengl.GLSurfaceView
import com.arhand.util.Vec3
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import kotlin.math.roundToInt

/**
 * Mode A — ARCore Depth API.
 *
 * Direct port of Handy's `ArDepthSession` (predecessor project; not present in this repo),
 * adapted to the [DepthSource] interface. The Session is driven from the GL thread via
 * [onDrawFrame] (called by the GLSurfaceView renderer) so that
 * `Session.update()` always runs with the correct EGL context current —
 * the requirement ARCore imposes when a shared texture name is set.
 *
 * [start]/[stop] are Activity lifecycle hooks.
 * The depth texture name is set via [setCameraTextureName] once after GL init.
 *
 * Availability gate: [isAvailable] checks ARCore install + Depth API hardware
 * support via a fast synchronous path (no Session creation needed at check time).
 */
class ArCoreDepthSource(private val context: Context) : DepthSource {

    override val mode = DepthSource.Mode.ARCORE

    private var session: Session? = null
    private var callback: DepthSourceCallback? = null
    private var started = false

    /** Last ARCore world-space camera position in metres. NaN until first tracking frame. */
    @Volatile var lastCamX: Float = Float.NaN
    @Volatile var lastCamY: Float = Float.NaN
    @Volatile var lastCamZ: Float = Float.NaN
    /** GAP-1 — ARCore camera-to-world rotation quaternion (x,y,z,w). NaN until first frame. */
    @Volatile var lastCamQX: Float = Float.NaN
    @Volatile var lastCamQY: Float = Float.NaN
    @Volatile var lastCamQZ: Float = Float.NaN
    @Volatile var lastCamQW: Float = Float.NaN

    // ─── DepthSource lifecycle ────────────────────────────────────────────

    override fun isAvailable(): Boolean {
        val avail = try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (_: Throwable) { return false }
        return avail == ArCoreApk.Availability.SUPPORTED_INSTALLED
        // Full depth-hw check requires a Session; we do it lazily in start().
    }

    override fun start(callback: DepthSourceCallback) {
        this.callback = callback
        if (!isAvailable()) { callback.onError("ARCore not installed"); return }
        val ok = tryCreate()
        if (!ok) { callback.onError("Depth API not supported on this device"); return }
        try { session?.resume() } catch (e: Throwable) { callback.onError(e.message ?: "resume failed") }
        started = true
    }

    override fun stop() {
        started = false
        try { session?.pause() } catch (_: Throwable) {}
    }

    fun close() {
        stop()
        session?.close(); session = null
    }

    /**
     * Release ARCore's hold on the physical (rear) camera without touching [started] or
     * [callback] bookkeeping, so [resumeCameraHold] can cleanly pick back up — unlike
     * [stop], which is a full lifecycle stop. This Session always targets the rear-facing
     * camera via its own independent Camera2 handle (no ARCore Shared-Camera integration
     * here), so it cannot be open at the same time [com.arhand.camera.CameraController]
     * also holds the rear camera — call this before that happens (ENGINE_ARCHITECTURE.md
     * §4.9), then [resumeCameraHold] once the rear camera is free again.
     */
    fun pauseCameraHold() {
        try { session?.pause() } catch (_: Throwable) {}
    }

    /** Reacquire the camera released by [pauseCameraHold]. No-op if never [start]ed. */
    fun resumeCameraHold() {
        if (!started) return
        try { session?.resume() } catch (_: Throwable) {}
    }

    // ─── GL thread hooks — call from your GLSurfaceView.Renderer ─────────

    fun setCameraTextureName(texId: Int) {
        session?.setCameraTextureName(texId)
    }

    /**
     * Call from [GLSurfaceView.Renderer.onDrawFrame].
     * Updates the ARCore session, extracts depth, pushes points.
     */
    fun onDrawFrame() {
        val s = session ?: return
        if (!started) return
        val cb = callback ?: return

        val frame = try { s.update() } catch (_: Throwable) { return }
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) return

        var depthImage: Image? = null
        var confImage:  Image? = null
        try {
            depthImage = try { frame.acquireDepthImage16Bits() }
                         catch (_: NotYetAvailableException) { return }
                         catch (_: SessionPausedException)   { return }

            confImage  = try { frame.acquireRawDepthConfidenceImage() } catch (_: Throwable) { null }

            val width  = depthImage!!.width
            val height = depthImage!!.height
            val depthBuf   = depthImage!!.planes[0].buffer.asShortBuffer()
            val depthStride= depthImage!!.planes[0].rowStride
            val confBuf    = confImage?.planes?.get(0)?.buffer
            val confStride = confImage?.planes?.get(0)?.rowStride ?: 0

            val intrinsics = cam.imageIntrinsics
            val sx = width.toFloat()  / intrinsics.imageDimensions[0].toFloat()
            val sy = height.toFloat() / intrinsics.imageDimensions[1].toFloat()
            val fx = intrinsics.focalLength[0]  * sx
            val fy = intrinsics.focalLength[1]  * sy
            val cx = intrinsics.principalPoint[0] * sx
            val cy = intrinsics.principalPoint[1] * sy
            val pose = cam.pose
            // Expose camera position for FusedDepthSource scale calibration (Bug 25 fix)
            val t = pose.translation
            lastCamX = t[0]; lastCamY = t[1]; lastCamZ = t[2]
            // GAP-1: Expose camera-to-world rotation quaternion for SpatialLayer.toWorldSpace
            val q = pose.rotationQuaternion
            lastCamQX = q[0]; lastCamQY = q[1]; lastCamQZ = q[2]; lastCamQW = q[3]

            val stride      = 4
            val minConf     = 200
            val batchSize   = (width / stride) * (height / stride) * 4
            val batch       = FloatArray(batchSize)
            var bIdx        = 0
            var confSum     = 0f
            var confCount   = 0

            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val dIdx = (y * depthStride) / 2 + x
                    val rawMm = depthBuf.get(dIdx).toInt() and 0xFFFF
                    if (rawMm > 0) {
                        val conf = if (confBuf != null) {
                            val cIdx = y * confStride + x
                            if (cIdx < confBuf.capacity()) confBuf.get(cIdx).toInt() and 0xFF else 255
                        } else 255
                        if (conf >= minConf) {
                            val dm = rawMm / 1000f
                            val lx = (x - cx) * dm / fx
                            val ly = (y - cy) * dm / fy
                            val lz = -dm
                            val wp = FloatArray(3)
                            pose.transformPoint(floatArrayOf(lx, ly, lz), 0, wp, 0)
                            if (bIdx + 3 < batch.size) {
                                batch[bIdx++] = wp[0]; batch[bIdx++] = wp[1]
                                batch[bIdx++] = wp[2]; batch[bIdx++] = conf / 255f
                                confSum += conf / 255f; confCount++
                            }
                        }
                    }
                    x += stride
                }
                y += stride
            }

            if (bIdx > 0) {
                cb.onPoints(batch, bIdx)
                cb.onStats(DepthSource.Stats(
                    pointsThisFrame = bIdx / 4,
                    confidenceMean  = if (confCount > 0) confSum / confCount else 0f,
                    extraLabel = "ARCore"
                ))
            }
        } finally {
            depthImage?.close()
            confImage?.close()
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────

    private fun tryCreate(): Boolean {
        if (session != null) return true
        return try {
            val s = Session(context)
            val cfg = Config(s).apply {
                depthMode = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                    Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                focusMode = Config.FocusMode.AUTO
                planeFindingMode     = Config.PlaneFindingMode.DISABLED
                lightEstimationMode  = Config.LightEstimationMode.DISABLED
            }
            if (cfg.depthMode == Config.DepthMode.DISABLED) { s.close(); return false }
            s.configure(cfg)
            session = s
            true
        } catch (_: Throwable) {
            session?.close(); session = null
            false
        }
    }

    companion object {
        /** Pack (x,y,z) into a voxel key for deduplication — lifted from Handy's `ArDepthSession`. */
        fun voxelKey(x: Float, y: Float, z: Float, cellSize: Float): Long {
            val qx = (x / cellSize).roundToInt()
            val qy = (y / cellSize).roundToInt()
            val qz = (z / cellSize).roundToInt()
            val mask = (1L shl 21) - 1
            return ((qx.toLong() and mask) shl 42) or
                   ((qy.toLong() and mask) shl 21) or
                   (qz.toLong() and mask)
        }
    }
}
