package com.arhand.feature.stream

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arhand.mocap.OscMode
import com.arhand.mocap.OscSchema
import com.arhand.mocap.OscStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Application.oscDataStore by preferencesDataStore("osc_settings")

/**
 * Owns the complete OSC streaming lifecycle.
 *
 * Single responsibility: start/stop streaming, manage schema/host/port,
 * and persist settings between sessions via DataStore.
 *
 * [AppViewModel] delegates all OSC operations here.
 * UI observes [state] directly — no AppUiState round-trip.
 */
class OscManager(
    private val app:        Application,
    private val oscStreamer: OscStreamer,
    private val scope:      CoroutineScope
) {
    private val _state = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = _state.asStateFlow()

    /** Exposes OscStreamer health directly — no duplication. */
    val health get() = oscStreamer.health

    private val KEY_HOST   = stringPreferencesKey("osc_host")
    private val KEY_PORT   = intPreferencesKey("osc_port")
    private val KEY_SCHEMA = stringPreferencesKey("osc_schema")

    init {
        // Restore persisted settings on construction
        scope.launch(Dispatchers.IO) {
            val prefs = app.oscDataStore.data.first()
            _state.value = _state.value.copy(
                host   = prefs[KEY_HOST]   ?: OscStreamer.DEFAULT_HOST,
                port   = prefs[KEY_PORT]   ?: OscStreamer.DEFAULT_PORT,
                schema = prefs[KEY_SCHEMA]?.let { s ->
                    OscSchema.entries.firstOrNull { it.name == s }
                } ?: OscSchema.HANDY_DEFAULT
            )
        }
    }

    fun start(
        host:   String    = _state.value.host,
        port:   Int       = _state.value.port,
        schema: OscSchema = _state.value.schema
    ) {
        oscStreamer.start(host, schema, port)
        _state.value = _state.value.copy(isStreaming = true, host = host, port = port, schema = schema)
        scope.launch(Dispatchers.IO) {
            app.oscDataStore.edit { prefs ->
                prefs[KEY_HOST]   = host
                prefs[KEY_PORT]   = port
                prefs[KEY_SCHEMA] = schema.name
            }
        }
    }

    fun stop() {
        oscStreamer.stop()
        _state.value = _state.value.copy(isStreaming = false)
    }

    fun setSchema(schema: OscSchema) {
        val newPort = if (oscStreamer.isStreaming) _state.value.port
                      else OscStreamer.defaultPortFor(schema)
        _state.value = _state.value.copy(schema = schema, port = newPort)
        oscStreamer.schema = schema
    }

    fun setMode(mode: OscMode) {
        _state.value = _state.value.copy(
            mode        = mode,
            isReceiving = mode == OscMode.RECEIVE
        )
    }

    fun setHost(host: String) {
        _state.value = _state.value.copy(host = host)
    }

    fun setPort(port: Int) {
        _state.value = _state.value.copy(port = port)
    }

    fun setReceivePort(port: Int) {
        _state.value = _state.value.copy(receivePort = port)
    }
}

/**
 * Immutable OSC streaming state snapshot.
 */
data class StreamState(
    val isStreaming:    Boolean   = false,
    val host:          String    = OscStreamer.DEFAULT_HOST,
    val port:          Int       = OscStreamer.DEFAULT_PORT,
    val schema:        OscSchema = OscSchema.HANDY_DEFAULT,
    val mode:          OscMode   = OscMode.SEND,
    val isReceiving:   Boolean   = false,
    val receivePort:   Int       = 9000  // matches OscReceiver.DEFAULT_PORT
)
