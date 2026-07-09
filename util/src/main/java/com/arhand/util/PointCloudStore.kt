package com.arhand.util

/**
 * Persistent point cloud ring buffer.
 *
 * Each point is stored as 4 floats: (x, y, z, confidence).
 * When capacity is reached the oldest 40 % of the cloud is dropped — matching
 * lidar.html's `pushBatch` overflow strategy so the viewer never goes stale.
 *
 * EMA centroid (CENTROID_EMA = 0.002) biases the view towards recent geometry;
 * PointCloudRenderer uses it as the orbit centre.
 *
 * Thread-safety: [push] is called from the camera/SfM background thread;
 * [snapshot] is called from the GL thread. Internal synchronisation via a
 * lock on the data array ensures both paths are safe without copies.
 */
class PointCloudStore(private val capacity: Int = 800_000) {

    companion object {
        private const val CENTROID_EMA = 0.002f
        /** Outlier rejection: drop points more than this many metres from centroid. */
        private const val OUTLIER_DIST = 6f
    }

    // Flat array: [x0,y0,z0,c0, x1,y1,z1,c1, …]
    private val data = FloatArray(capacity * 4)
    private var count = 0       // number of valid points
    private var ccInit = false
    var centroidX = 0f; private set
    var centroidY = 0f; private set
    var centroidZ = 0f; private set

    val pointCount: Int get() = count

    /** Append a batch of (x,y,z,conf) floats. Caller owns the array slice [0..len). */
    @Synchronized
    fun push(batch: FloatArray, len: Int = batch.size) {
        val n = len / 4
        if (n == 0) return

        // Overflow: keep newest 60 %
        if (count + n > capacity) {
            val keep = (capacity * 0.6f).toInt()
            val drop = count - keep
            data.copyInto(data, 0, drop * 4, count * 4)
            count = keep
        }

        var written = 0
        for (i in 0 until n) {
            val x = batch[i * 4]
            val y = batch[i * 4 + 1]
            val z = batch[i * 4 + 2]
            val c = batch[i * 4 + 3]

            // Outlier gate (skip until centroid is initialised)
            if (ccInit) {
                val dx = x - centroidX; val dy = y - centroidY; val dz = z - centroidZ
                if (dx * dx + dy * dy + dz * dz > OUTLIER_DIST * OUTLIER_DIST) continue
            }

            data[(count + written) * 4]     = x
            data[(count + written) * 4 + 1] = y
            data[(count + written) * 4 + 2] = z
            data[(count + written) * 4 + 3] = c

            // EMA centroid update
            if (!ccInit) { centroidX = x; centroidY = y; centroidZ = z; ccInit = true }
            else {
                centroidX += (x - centroidX) * CENTROID_EMA
                centroidY += (y - centroidY) * CENTROID_EMA
                centroidZ += (z - centroidZ) * CENTROID_EMA
            }
            written++
        }
        count += written
    }

    /**
     * Copy the current cloud into [dest], starting at [offset].
     * Returns the number of floats written.
     * If [dest] is null or too small, returns count*4 so callers can size accordingly.
     */
    @Synchronized
    fun snapshot(dest: FloatArray? = null, offset: Int = 0): Int {
        val needed = count * 4
        if (dest == null || dest.size - offset < needed) return needed
        data.copyInto(dest, offset, 0, needed)
        return needed
    }

    @Synchronized
    fun clear() {
        count = 0; ccInit = false
        centroidX = 0f; centroidY = 0f; centroidZ = 0f
    }
}
