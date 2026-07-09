package com.arhand.tracking

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps MediaPipe PoseLandmarker (Tasks SDK) and applies the same
 * OneEuroFilter smoothing used by [HandPipeline].
 *
 * GAP-10 — IMU world-frame pre-rotation:
 *
 * MediaPipe body landmarks are in camera-normalised space. When the device
 * is held at an angle (typical for phone mocap), all body joints are
 * systematically wrong because the spine bind direction Vec3(0,1,0) no longer
 * matches the world-up direction.
 *
 * Fix: register a [SensorManager.TYPE_ROTATION_VECTOR] listener on init.
 * Each [onResult] call pre-rotates the raw landmarks from camera frame to a
 * gravity-aligned world frame using the device's live rotation matrix.
 *
 * The rotation is applied only to the XYZ position of each landmark (not to
 * visibility, which is camera-relative). This makes spine/limb bind directions
 * valid regardless of device tilt — exactly what BodyRetargeter needs.
 *
 * Thread safety: [detect] and sensor callbacks run on different threads.
 * [rotationMatrix] is volatile-read and updated atomically.
 */
class BodyPipeline {

    companion object {
        const val MODEL_ASSET = "pose_landmarker_lite.task"

        // OneEuroFilter parameters — tuned for body joints (lower beta than hands
        // since body motion is slower and smoother)
        const val OEF_MIN_CUTOFF_XY = 0.5f
        const val OEF_BETA_XY       = 0.8f
        const val OEF_MIN_CUTOFF_Z  = 0.7f
        const val OEF_BETA_Z        = 1.0f

        // Grace period: keep last pose for this many frames after detection drops
        const val GRACE_FRAMES = 10
    }

    private var landmarker: PoseLandmarker? = null

    private val oef = OneEuroFilter(OEF_MIN_CUTOFF_XY, OEF_BETA_XY, OEF_MIN_CUTOFF_Z, OEF_BETA_Z)
    private var graceCounts = 0
    private var lastAccepted: PoseLandmarks? = null

    private val _processed = MutableStateFlow<PoseLandmarks?>(null)
    val processed: StateFlow<PoseLandmarks?> = _processed

    // GAP-10 — IMU world-frame rotation fields
    @Volatile private var rotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }  // identity
    @Volatile private var imuWarm = false
    private var sensorManager: SensorManager? = null
    private val imuListener = object : SensorEventListener {
        private val rotVec   = FloatArray(16)
        private val rotMat4  = FloatArray(16)
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotMat4, event.values)
            // Extract 3×3 upper-left from the 4×4 column-major matrix
            rotationMatrix = floatArrayOf(
                rotMat4[0], rotMat4[1], rotMat4[2],
                rotMat4[4], rotMat4[5], rotMat4[6],
                rotMat4[8], rotMat4[9], rotMat4[10]
            )
            imuWarm = true
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Initialize the PoseLandmarker. Call once, on any thread, before [detect].
     * Uses GPU delegate; falls back to CPU if unavailable.
     * Also registers the IMU rotation vector sensor (GAP-10).
     */
    fun init(context: Context) {
        // GAP-10: Register IMU — rotation vector gives camera-to-world matrix
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = sm
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sm.registerListener(imuListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }

        val delegate = listOf(Delegate.GPU, Delegate.CPU).firstNotNullOfOrNull { d ->
            try {
                val opts = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(d).build()
                PoseLandmarker.createFromOptions(
                    context,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(opts)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumPoses(1)
                        .setMinPoseDetectionConfidence(0.5f)
                        .setMinPosePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setResultListener { result, _ -> onResult(result) }
                        .build()
                ).also { landmarker = it }
                d
            } catch (_: Exception) { null }
        }
        if (delegate == null) {
            android.util.Log.e("BodyPipeline", "All delegates failed — body tracking unavailable")
        }
    }

    /** Submit a frame for async detection. Safe to call from camera callback thread. */
    fun detect(bitmap: Bitmap, timestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker?.detectAsync(mpImage, timestampMs)
    }

    /** Release the MediaPipe landmarker, IMU listener, and reset filter state. */
    fun close() {
        sensorManager?.unregisterListener(imuListener)
        sensorManager = null
        imuWarm = false
        landmarker?.close()
        landmarker = null
        oef.reset()
        lastAccepted = null
        graceCounts = 0
        _processed.value = null
    }

    // ─── Result handling ──────────────────────────────────────────────────────

    private fun onResult(result: PoseLandmarkerResult) {
        val rawList = result.landmarks()

        if (rawList.isEmpty()) {
            graceCounts++
            if (graceCounts >= GRACE_FRAMES) {
                oef.reset()
                lastAccepted = null
                _processed.value = null
            } else {
                _processed.value = lastAccepted
            }
            return
        }

        graceCounts = 0
        val now = System.currentTimeMillis()

        // Convert MediaPipe NormalizedLandmark → our Landmark type
        val raw: PoseLandmarks = rawList[0].map { lm ->
            Landmark(
                x          = lm.x(),
                y          = lm.y(),
                z          = lm.z(),
                visibility = lm.visibility().orElse(1f)
            )
        }

        // GAP-10 — Pre-rotate landmarks from camera frame to gravity-aligned world frame.
        // Applies only when IMU has warmed up (≥1 sensor event received).
        // The rotation converts camera-normalised landmark positions so that world-up
        // always points to Vec3(0,1,0) regardless of how the device is tilted.
        // BodyRetargeter's bind directions (spine = +Y, etc.) are then correct for
        // any device orientation — seated, angled, or frontal.
        val aligned: PoseLandmarks = if (imuWarm) {
            val rm = rotationMatrix  // volatile snapshot
            raw.map { lm ->
                val rx = rm[0]*lm.x + rm[1]*lm.y + rm[2]*lm.z
                val ry = rm[3]*lm.x + rm[4]*lm.y + rm[5]*lm.z
                val rz = rm[6]*lm.x + rm[7]*lm.y + rm[8]*lm.z
                lm.copy(x = rx, y = ry, z = rz)
            }
        } else raw

        val smoothed = oef.smooth(aligned, now)
        lastAccepted = smoothed
        _processed.value = smoothed
    }
}
