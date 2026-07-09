package com.arhand.feature.record

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arhand.mocap.LoadedAsset
import com.arhand.mocap.MotionRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** GAP-8 — DataStore for takes list persistence across process kill. */
private val Application.takesDataStore by preferencesDataStore(name = "recording_takes")
private val KEY_TAKES_JSON = stringPreferencesKey("takes_json")

/**
 * Owns the motion recording lifecycle and take list.
 *
 * Single responsibility: start/stop recording, manage take labels,
 * and accumulate the per-session take list.
 *
 * GAP-8: The takes list is persisted to DataStore after every append so it
 * survives process death (phone calls, low-memory kills). On cold start the
 * list is restored and any BVH/GLB files on disk are still accessible.
 */
class RecordingManager(
    private val app:            Application,
    private val motionRecorder: MotionRecorder,
    private val scope:          CoroutineScope
) {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    init {
        // GAP-8: Restore takes list from DataStore on cold start
        scope.launch(Dispatchers.IO) {
            runCatching {
                val prefs = app.takesDataStore.data.first()
                val json  = prefs[KEY_TAKES_JSON] ?: return@launch
                val arr   = JSONArray(json)
                val takes = (0 until arr.length()).mapNotNull { i ->
                    runCatching { TakeEntry.fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
                if (takes.isNotEmpty()) {
                    _state.value = _state.value.copy(takes = takes)
                }
            }.onFailure { android.util.Log.e("RecordingManager", "Failed to restore takes", it) }
        }
    }

    fun start() {
        motionRecorder.start()
        val autoLabel = _state.value.currentLabel.ifBlank {
            "Take ${_state.value.takes.size + 1}"
        }
        _state.value = _state.value.copy(isRecording = true, currentLabel = autoLabel)
    }

    fun stop(): Long {
        val startMs = _state.value.recordingStartMs
        motionRecorder.stop()
        _state.value = _state.value.copy(isRecording = false)
        return startMs
    }

    fun markStopped() {
        _state.value = _state.value.copy(isRecording = false)
    }

    /**
     * Stop recording, export BVH + animated GLB, and append a [TakeEntry].
     * Runs on the calling coroutine — caller dispatches to IO.
     */
    suspend fun exportAndAppend(
        outputDir:      java.io.File,
        scannedOffsets: Map<Int, com.arhand.util.Vec3>?,
        loadedAsset:    LoadedAsset
    ) {
        val recordingStart = _state.value.recordingStartMs
        motionRecorder.stop()
        _state.value = _state.value.copy(isRecording = false)

        outputDir.mkdirs()

        val snapshot = motionRecorder.frameSnapshot()
        val rawLabel = _state.value.currentLabel.ifBlank {
            "take_${System.currentTimeMillis() / 1000}"
        }
        val label = rawLabel.replace(' ', '_').replace(Regex("[^A-Za-z0-9_\\-]"), "")

        val bvhFile  = motionRecorder.exportBvh(outputDir, label, scannedOffsets)
        val gltfFile = com.arhand.export.GltfAnimationExporter.export(
            frames    = snapshot,
            asset     = loadedAsset,
            outputDir = outputDir,
            label     = "${label}_anim"
        )

        val durationMs = System.currentTimeMillis() - recordingStart
        appendTake(TakeEntry(
            label      = rawLabel,
            bvhPath    = bvhFile?.absolutePath,
            gltfPath   = gltfFile?.absolutePath,
            durationMs = durationMs,
            frameCount = snapshot.size
        ))
    }

    fun setLabel(label: String) {
        if (!_state.value.isRecording) {
            _state.value = _state.value.copy(currentLabel = label)
        }
    }

    /**
     * Append a take and persist the full list to DataStore.
     * GAP-8: persistence ensures the list survives process kill.
     */
    fun appendTake(take: TakeEntry) {
        val newTakes = _state.value.takes + take
        _state.value = _state.value.copy(takes = newTakes, currentLabel = "")
        persistTakes(newTakes)
    }

    /** GAP-4: Delete a take — remove from list, delete files, persist. */
    fun deleteTake(take: TakeEntry) {
        take.bvhPath?.let  { runCatching { java.io.File(it).delete() } }
        take.gltfPath?.let { runCatching { java.io.File(it).delete() } }
        val newTakes = _state.value.takes.filter { it !== take && it.takenAtMs != take.takenAtMs }
        _state.value = _state.value.copy(takes = newTakes)
        persistTakes(newTakes)
    }

    fun markRecordingStarted() {
        _state.value = _state.value.copy(recordingStartMs = System.currentTimeMillis())
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun persistTakes(takes: List<TakeEntry>) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                takes.forEach { arr.put(it.toJson()) }
                app.takesDataStore.edit { prefs ->
                    prefs[KEY_TAKES_JSON] = arr.toString()
                }
            }.onFailure { android.util.Log.e("RecordingManager", "Failed to persist takes", it) }
        }
    }
}

/**
 * Immutable recording state snapshot.
 */
data class RecordingState(
    val isRecording:      Boolean         = false,
    val currentLabel:     String          = "",
    val takes:            List<TakeEntry> = emptyList(),
    val exportedBvhPath:  String?         = null,
    val exportedGltfPath: String?         = null,
    val recordingStartMs: Long            = 0L
)
