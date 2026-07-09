package com.arhand.depth

/**
 * Common contract for all three depth-sensing modes.
 *
 * Each implementation runs on its own background thread and pushes batches
 * of (x, y, z, confidence) floats into a shared [com.arhand.util.PointCloudStore]
 * via [DepthSourceCallback.onPoints].
 *
 * Lifecycle (called from Activity):
 *   [start]  → allocate resources, begin producing points
 *   [stop]   → release resources, stop background work
 *   [isAvailable] → checked before offering mode in the UI
 */
interface DepthSource {

    enum class Mode {
        /** ARCore Depth API — fused RGB-D from dedicated depth hw / stereo algorithm. */
        ARCORE,
        /** Photometric stereo — torch on/off frame pairs → Lambertian normal → depth. */
        PHOTOMETRIC,
        /** Structure from Motion — sliding-window Harris/LK triangulation (lidar.html port). */
        SFM
    }

    val mode: Mode

    /**
     * Returns true if this mode can function on the current device.
     * Fast check — called on the main thread. No Session creation.
     */
    fun isAvailable(): Boolean

    fun start(callback: DepthSourceCallback)
    fun stop()

    /** Stats snapshot for the HUD — implementations fill what they can. */
    data class Stats(
        val pointsThisFrame: Int = 0,
        val featuresActive: Int  = 0,
        val baselinePx: Float   = 0f,
        val confidenceMean: Float = 0f,
        val extraLabel: String  = ""
    )
}

interface DepthSourceCallback {
    /** Called from any thread. batch is (x,y,z,conf)×n. len = valid floats (multiple of 4). */
    fun onPoints(batch: FloatArray, len: Int)
    fun onStats(stats: DepthSource.Stats)
    fun onError(msg: String)
}
