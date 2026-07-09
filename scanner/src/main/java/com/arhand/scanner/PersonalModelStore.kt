package com.arhand.scanner

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Persists the personal hand model to DataStore + local file.
 * Replaces localStorage from the HTML prototype.
 *
 * Stores:
 *   - Scan metadata (timestamp, point count, pose quality scores)
 *   - GLB export path (local file URI)
 *   - Per-pose quality map
 *   - Hand biometrics (palm width, finger lengths, span, knuckle breadth, wrist circumference)
 */

private val Context.modelDataStore: DataStore<Preferences> by preferencesDataStore(name = "handy_model")

class PersonalModelStore(private val context: Context) {

    companion object {
        /**
         * F3 — Schema version. Bump this whenever a new key is added or semantics change.
         * On version mismatch the store is reset gracefully so existing installs
         * don't end up with partially populated prefs.
         *
         * History:
         *   1 → initial schema
         *   2 → added KEY_BIOMETRICS (hand_biometrics_json)
         *   3 → added KEY_CALIBRATED_SCALE (D2 calibration card scale)
         *   4 → added KEY_BIOMETRIC_HISTORY (D4 biometric history JSON array)
         */
        const val CURRENT_SCHEMA_VERSION = 4

        val KEY_SCHEMA_VERSION = intPreferencesKey("schema_version")
        val KEY_TIMESTAMP    = stringPreferencesKey("scan_timestamp")
        val KEY_POINT_COUNT  = intPreferencesKey("point_count")
        val KEY_GLB_PATH     = stringPreferencesKey("glb_path")
        val KEY_POSE_SCORES  = stringPreferencesKey("pose_scores_json")
        val KEY_HAS_MODEL    = booleanPreferencesKey("has_model")

        /** JSON blob from [HandBiometrics.toJson]. Empty string = not yet computed. */
        val KEY_BIOMETRICS   = stringPreferencesKey("hand_biometrics_json")

        /**
         * D2 — Calibration scale factor (world-units → mm).
         * Derived from a credit-card calibration flow. Falls back to [HandBiometrics.WORLD_TO_MM]
         * (800f) when not set. Stored as a Float bits integer for DataStore compatibility.
         */
        val KEY_CALIBRATED_SCALE = floatPreferencesKey("calibrated_scale_world_to_mm")

        /**
         * D4 — Biometric history: a JSON array of up to [MAX_HISTORY_ENTRIES] objects.
         * Each entry is: { "ts": Long, "biometrics": {…HandBiometrics JSON…} }
         * Newest entries are at the front. Capped at [MAX_HISTORY_ENTRIES] on write.
         */
        val KEY_BIOMETRIC_HISTORY = stringPreferencesKey("biometric_history_json")

        /** D4 — Maximum number of history entries retained. */
        const val MAX_HISTORY_ENTRIES = 20
    }

    /**
     * F3 — Migrate preferences to [CURRENT_SCHEMA_VERSION].
     * If the stored schema version is older, clear all data so the user rescans
     * rather than having the app crash or silently return garbage.
     * Called lazily before every read operation.
     */
    private suspend fun migrateIfNeeded() {
        val prefs = context.modelDataStore.data.first()
        val storedVersion = prefs[KEY_SCHEMA_VERSION] ?: 0
        if (storedVersion < CURRENT_SCHEMA_VERSION) {
            context.modelDataStore.edit { it.clear() }
            context.modelDataStore.edit { it[KEY_SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION }
        }
    }

    suspend fun saveModel(
        glbFile: File,
        pointCount: Int,
        poseScoresJson: String,
        biometrics: HandBiometrics? = null
    ) {
        migrateIfNeeded()
        context.modelDataStore.edit { prefs ->
            prefs[KEY_SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
            prefs[KEY_TIMESTAMP]   = System.currentTimeMillis().toString()
            prefs[KEY_POINT_COUNT] = pointCount
            prefs[KEY_GLB_PATH]    = glbFile.absolutePath
            prefs[KEY_POSE_SCORES] = poseScoresJson
            prefs[KEY_HAS_MODEL]   = true
            prefs[KEY_BIOMETRICS]  = biometrics?.toJson() ?: ""
        }
    }

    suspend fun loadModel(): ModelMeta? {
        migrateIfNeeded()
        val prefs = context.modelDataStore.data.first()
        if (prefs[KEY_HAS_MODEL] != true) return null
        return ModelMeta(
            timestamp   = prefs[KEY_TIMESTAMP]   ?: "",
            pointCount  = prefs[KEY_POINT_COUNT] ?: 0,
            glbPath     = prefs[KEY_GLB_PATH]    ?: "",
            poseScores  = prefs[KEY_POSE_SCORES] ?: "{}",
            biometrics  = HandBiometrics.fromJson(prefs[KEY_BIOMETRICS] ?: "")
        )
    }

    suspend fun clearModel() {
        context.modelDataStore.edit { it.clear() }
    }

    /**
     * D2 — Persist a user-derived world-to-mm scale factor computed from the
     * credit-card calibration flow. Pass null to revert to the built-in default.
     *
     * @param scale  Calibrated world-units → mm factor (e.g. 85.6 / measuredCardWidth)
     */
    suspend fun saveCalibration(scale: Float?) {
        context.modelDataStore.edit { prefs ->
            if (scale != null) {
                prefs[KEY_CALIBRATED_SCALE] = scale
            } else {
                prefs.remove(KEY_CALIBRATED_SCALE)
            }
        }
    }

    /**
     * D2 — Load the calibrated scale factor, or [HandBiometrics.WORLD_TO_MM] if not set.
     */
    suspend fun loadCalibratedScale(): Float {
        migrateIfNeeded()
        val prefs = context.modelDataStore.data.first()
        return prefs[KEY_CALIBRATED_SCALE] ?: HandBiometrics.WORLD_TO_MM
    }

    /**
     * D4 — Append a new biometric measurement to the history list.
     *
     * History is a JSON array stored as a single DataStore string. On write, we
     * prepend the new entry and truncate to [MAX_HISTORY_ENTRIES]. A timestamp is
     * recorded automatically.
     *
     * @param biometrics  The newly computed [HandBiometrics] to record.
     */
    suspend fun saveToHistory(biometrics: HandBiometrics) {
        val entry = buildHistoryEntry(System.currentTimeMillis(), biometrics)
        context.modelDataStore.edit { prefs ->
            val existing = prefs[KEY_BIOMETRIC_HISTORY] ?: "[]"
            // Keep the history as raw JSON strings throughout — avoids the type mismatch
            // that occurred when mixing parseHistoryEntries (→ List<BiometricHistoryEntry>)
            // with entriesToJson (takes List<String>).
            val rawEntries = splitTopLevelObjects(
                existing.trim().removePrefix("[").removeSuffix("]").trim()
            ).toMutableList()
            rawEntries.add(0, entry)
            if (rawEntries.size > MAX_HISTORY_ENTRIES)
                rawEntries.subList(MAX_HISTORY_ENTRIES, rawEntries.size).clear()
            prefs[KEY_BIOMETRIC_HISTORY] = entriesToJson(rawEntries)
        }
    }

    /**
     * D4 — Load the biometric history list, newest-first.
     * Returns an empty list when no history exists or on any parse error.
     */
    suspend fun loadHistory(): List<BiometricHistoryEntry> {
        migrateIfNeeded()
        val prefs = context.modelDataStore.data.first()
        return parseHistoryEntries(prefs[KEY_BIOMETRIC_HISTORY] ?: "[]")
    }

    // ─── D4 history serialisation helpers ────────────────────────────────────

    private fun buildHistoryEntry(ts: Long, b: HandBiometrics): String =
        """{"ts":$ts,"biometrics":${b.toJson()}}"""

    private fun parseHistoryEntries(json: String): List<BiometricHistoryEntry> {
        return try {
            val stripped = json.trim().removePrefix("[").removeSuffix("]").trim()
            if (stripped.isEmpty()) return emptyList()

            val rawEntries = splitTopLevelObjects(stripped)
            rawEntries.mapNotNull { raw ->
                val obj = raw.trim().let { if (it.startsWith("{")) it else "{$it" }
                                    .let { if (it.endsWith("}")) it else "$it}" }
                val ts = extractLong(obj, "ts") ?: return@mapNotNull null
                // Fixed: use properly escaped ASCII double-quotes.
                // The previous code used Unicode smart-quotes (U+201C/U+201D) which
                // never match stored JSON, so every history entry parsed as null.
                val bmStart = obj.indexOf("\"biometrics\"")
                if (bmStart < 0) return@mapNotNull null
                val braceIdx = obj.indexOf("{", bmStart)
                if (braceIdx < 0) return@mapNotNull null
                var depth = 0
                var end   = braceIdx
                for (i in braceIdx until obj.length) {
                    if (obj[i] == '{') depth++
                    if (obj[i] == '}') { depth--; if (depth == 0) { end = i; break } }
                }
                val bmJson = obj.substring(braceIdx, end + 1)
                val bm     = HandBiometrics.fromJson(bmJson) ?: return@mapNotNull null
                BiometricHistoryEntry(ts, bm)
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Split a JSON array body (without outer brackets) into individual object strings.
     * Handles nested objects by tracking brace depth.
     */
    private fun splitTopLevelObjects(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in s.indices) {
            when (s[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { result.add(s.substring(start, i + 1)); start = -1 } }
            }
        }
        return result
    }

    private fun extractLong(json: String, key: String): Long? =
        Regex(""""$key"\s*:\s*([\d]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun entriesToJson(entries: List<String>): String =
        entries.joinToString(",", prefix = "[", postfix = "]")

    suspend fun hasModel(): Boolean {
        migrateIfNeeded()
        return context.modelDataStore.data.map { it[KEY_HAS_MODEL] == true }.first()
    }
}

data class ModelMeta(
    val timestamp: String,
    val pointCount: Int,
    val glbPath: String,
    val poseScores: String,
    /** Null when the scan pre-dates biometrics support or computation failed. */
    val biometrics: HandBiometrics? = null
)

/**
 * D4 — A single entry in the biometric history.
 *
 * @param timestamp   Unix epoch milliseconds when this measurement was taken.
 * @param biometrics  The measured [HandBiometrics] at that point in time.
 */
data class BiometricHistoryEntry(
    val timestamp: Long,
    val biometrics: HandBiometrics
)
