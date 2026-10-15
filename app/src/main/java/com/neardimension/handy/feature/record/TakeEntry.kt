package com.arhand.feature.record

import org.json.JSONObject

/**
 * A single completed recording take.
 *
 * @param label      Human-readable name set by the user before recording.
 * @param bvhPath    Absolute path to the exported BVH file, or null if export failed.
 * @param gltfPath   Absolute path to the exported animated GLB, or null.
 * @param durationMs Recording duration in milliseconds.
 * @param frameCount Number of frames captured.
 * @param takenAtMs  Epoch-ms when the take was completed.
 */
data class TakeEntry(
    val label:      String,
    val bvhPath:    String?,
    val gltfPath:   String?,
    val durationMs: Long,
    val frameCount: Int,
    val takenAtMs:  Long = System.currentTimeMillis()
) {
    /** GAP-8: Serialize to JSON for DataStore persistence. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("label",      label)
        put("bvhPath",    bvhPath  ?: JSONObject.NULL)
        put("gltfPath",   gltfPath ?: JSONObject.NULL)
        put("durationMs", durationMs)
        put("frameCount", frameCount)
        put("takenAtMs",  takenAtMs)
    }

    companion object {
        /** GAP-8: Deserialize from DataStore JSON. Throws on malformed input. */
        fun fromJson(obj: JSONObject): TakeEntry = TakeEntry(
            label      = obj.getString("label"),
            bvhPath    = if (obj.isNull("bvhPath"))  null else obj.getString("bvhPath"),
            gltfPath   = if (obj.isNull("gltfPath")) null else obj.getString("gltfPath"),
            durationMs = obj.getLong("durationMs"),
            frameCount = obj.getInt("frameCount"),
            takenAtMs  = obj.getLong("takenAtMs")
        )
    }
}
