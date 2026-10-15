package com.arhand.scanner

/**
 * D5 — Range-of-motion result for a single joint.
 *
 * @param label    Human-readable joint name (e.g. "Index PIP").
 * @param minDeg   Minimum (most-extended) angle observed, in degrees. [Float.NaN] if not measured.
 * @param maxDeg   Maximum (most-flexed) angle observed, in degrees. [Float.NaN] if not measured.
 * @param measured False if there were not enough reliable frames to compute ROM for this joint.
 */
data class JointRom(
    val label: String,
    val minDeg: Float,
    val maxDeg: Float,
    val measured: Boolean
) {
    /** Swept range in degrees. NaN if not measured. */
    val rangeDeg: Float get() = if (measured) maxDeg - minDeg else Float.NaN
}

/**
 * Full ROM result for one hand — one [JointRom] per tracked joint.
 * Order matches [RomAccumulator.JOINT_LABELS].
 */
data class JointRomData(val joints: List<JointRom>) {

    /** Convenience: ROM for a specific joint label, or null. */
    fun get(label: String): JointRom? = joints.firstOrNull { it.label == label }

    /** Average ROM across all measured joints, in degrees. */
    val averageRangeDeg: Float get() {
        val measured = joints.filter { it.measured }
        return if (measured.isEmpty()) Float.NaN
        else measured.map { it.rangeDeg }.average().toFloat()
    }

    /** Serialise to compact JSON string for optional persistence. */
    fun toJson(): String {
        val entries = joints.joinToString(",\n") { j ->
            """{"label":"${j.label}","min":${j.minDeg},"max":${j.maxDeg},"measured":${j.measured}}"""
        }
        return "[\n$entries\n]"
    }
}
