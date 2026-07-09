package com.arhand.depth

import android.content.Context
import android.media.Image
import com.arhand.util.Vec3
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import kotlin.math.roundToInt

/**
 * B6 — ARCore Depth API integration (Phase 2).
 *
 * Wraps a [Session] configured for `DepthMode.AUTOMATIC`, and on each frame extracts
 * a fused, confidence-filtered, world-space point cloud from `Frame.acquireDepthImage16Bits()`.
 *
 * Lifecycle mirrors `GLSurfaceView.Renderer` calls:
 *   - [resume] on `onResume` (after permissions granted)
 *   - [setCameraTextureName] once a GL texture is available — required by ARCore's
 *     camera feed even though Handy renders its own MediaPipe-driven passthrough
 *   - [update] once per `onDrawFrame`
 *   - [pause] on `onPause`
 *   - [close] on `onDestroy`
 *
 * Manual [Session] management is used (not `ArFragment`) per B6 step 2 — Handy already
 * owns its `GLSurfaceView` and Camera2 pipeline via [com.arhand.camera.CameraController].
 */
class ArDepthSession(private val context: Context) {

    internal var session: Session? = null

    /** Latest fused world-space depth points, replaced each [update]. Read-only snapshot. */
    @Volatile
    var depthPointCloud: List<Vec3> = emptyList()
        private set

    /** B6 — DepthScannerPanel.depthConfidence: mean confidence (0..1) of the latest cloud. */
    @Volatile
    var depthConfidence: Float = 0f
        private set

    @Volatile
    var trackingState: TrackingState = TrackingState.STOPPED
        private set

    /**
     * Create and configure the session. Must be called once ARCore availability has
     * been confirmed via [ArDepthAvailability.isDepthApiSupported].
     *
     * @return true if the session was created successfully.
     */
    fun tryCreate(): Boolean {
        if (session != null) return true
        return try {
            val s = Session(context)
            val config = Config(s).apply {
                depthMode = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
                // We don't need ARCore's own camera image — Handy's CameraController
                // and MediaPipe pipeline already handle the passthrough feed and
                // hand tracking. ARCore is used purely for depth + pose.
                focusMode = Config.FocusMode.AUTO
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            if (config.depthMode == Config.DepthMode.DISABLED) {
                s.close()
                return false
            }
            s.configure(config)
            session = s
            true
        } catch (_: Throwable) {
            session?.close()
            session = null
            false
        }
    }

    /** Must be called with a valid GL texture id before the first [update]. */
    fun setCameraTextureName(textureId: Int) {
        session?.setCameraTextureName(textureId)
    }

    fun resume() {
        try {
            session?.resume()
        } catch (_: Throwable) {
            // CameraNotAvailableException etc. — leave depth mode inactive this frame.
        }
    }

    fun pause() {
        try {
            session?.pause()
        } catch (_: Throwable) {
        }
    }

    fun close() {
        session?.close()
        session = null
        depthPointCloud = emptyList()
    }

    /**
     * B6 step 3 — acquire `Frame.acquireDepthImage16Bits()` and project valid pixels
     * into world space using camera intrinsics + the frame's pose.
     *
     * Replaces [DepthCarver]'s `capturedFrames: List<List<Vec3>>` input (B6 step 4)
     * when depth mode is active: the returned points are appended directly to the
     * scan's accumulated cloud and fed to [DepthApiCarver].
     *
     * Subsamples every [stride]th pixel for performance — a 240x180 depth image at
     * stride 4 yields ~2,700 points/frame, plenty for marching cubes at GRID_N=48.
     */
    fun update(frame: Frame, stride: Int = 4, minConfidence: Int = 200): List<Vec3> {
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) {
            trackingState = cam.trackingState
            depthPointCloud = emptyList()
            depthConfidence = 0f
            return emptyList()
        }
        trackingState = TrackingState.TRACKING

        var depthImage: Image? = null
        var confidenceImage: Image? = null
        try {
            depthImage = frame.acquireDepthImage16Bits()
        } catch (_: NotYetAvailableException) {
            return depthPointCloud
        } catch (_: SessionPausedException) {
            return depthPointCloud
        } catch (_: Throwable) {
            return depthPointCloud
        }

        try {
            confidenceImage = try {
                frame.acquireRawDepthConfidenceImage()
            } catch (_: Throwable) {
                null
            }

            val width = depthImage.width
            val height = depthImage.height
            val depthBuffer = depthImage.planes[0].buffer.asShortBuffer()
            val depthRowStride = depthImage.planes[0].rowStride
            val confBuffer = confidenceImage?.planes?.get(0)?.buffer
            val confRowStride = confidenceImage?.planes?.get(0)?.rowStride ?: 0

            // Camera intrinsics scaled to the depth image resolution.
            val intrinsics = cam.imageIntrinsics
            val focal = intrinsics.focalLength       // [fx, fy] at full camera resolution
            val principal = intrinsics.principalPoint // [cx, cy] at full camera resolution
            val imgDims = intrinsics.imageDimensions   // [width, height] at full resolution

            val sx = width.toFloat() / imgDims[0].toFloat()
            val sy = height.toFloat() / imgDims[1].toFloat()
            val fx = focal[0] * sx
            val fy = focal[1] * sy
            val cx = principal[0] * sx
            val cy = principal[1] * sy

            // Pose: depth-image-space (camera local) -> world space.
            val cameraPose = cam.pose

            val points = ArrayList<Vec3>((width / stride) * (height / stride))
            var confSum = 0f
            var confCount = 0

            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val depthIdx = (y * depthRowStride) / 2 + x // /2: 16-bit samples
                    val rawDepthMm = depthBuffer.get(depthIdx).toInt() and 0xFFFF
                    if (rawDepthMm > 0) {
                        val confidence: Int = if (confBuffer != null) {
                            val cIdx = y * confRowStride + x
                            if (cIdx < confBuffer.capacity()) confBuffer.get(cIdx).toInt() and 0xFF else 255
                        } else 255

                        if (confidence >= minConfidence) {
                            val depthM = rawDepthMm / 1000f

                            // Unproject pixel (x, y) at depth depthM into camera-local space.
                            // Camera-local: +x right, +y down, -z forward.
                            val localX = (x - cx) * depthM / fx
                            val localY = (y - cy) * depthM / fy
                            val localZ = -depthM

                            val worldPoint = FloatArray(3)
                            cameraPose.transformPoint(floatArrayOf(localX, localY, localZ), 0, worldPoint, 0)

                            points.add(Vec3(worldPoint[0], worldPoint[1], worldPoint[2]))
                            confSum += confidence / 255f
                            confCount++
                        }
                    }
                    x += stride
                }
                y += stride
            }

            depthPointCloud = points
            depthConfidence = if (confCount > 0) confSum / confCount else 0f
            return points
        } catch (_: Throwable) {
            return depthPointCloud
        } finally {
            depthImage.close()
            confidenceImage?.close()
        }
    }

    companion object {
        /**
         * B6 — round a world-space coordinate to a voxel-grid key for deduplication
         * when fusing point clouds across frames.
         */
        fun voxelKey(p: Vec3, cellSize: Float): Long {
            val qx = (p.x / cellSize).roundToInt()
            val qy = (p.y / cellSize).roundToInt()
            val qz = (p.z / cellSize).roundToInt()
            val mask = (1L shl 21) - 1
            return ((qx.toLong() and mask) shl 42) or
                   ((qy.toLong() and mask) shl 21) or
                   (qz.toLong() and mask)
        }
    }
}
