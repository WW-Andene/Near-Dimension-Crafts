package com.arhand.feature.asset

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arhand.mocap.AssetLoader
import com.arhand.mocap.AssetSlot
import com.arhand.mocap.BundledAssetGenerator
import com.arhand.mocap.LoadedAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Application.assetDataStore by preferencesDataStore("asset_settings")

/**
 * Owns the currently loaded 3D asset.
 *
 * Single responsibility: load, unload, and persist the active GLB asset.
 * Exposes [state] for the loaded asset name, slot, and loading indicator.
 * The actual [LoadedAsset] object is exposed separately for the renderer.
 */
class AssetManager(
    private val app:   Application,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(AssetState())
    val state: StateFlow<AssetState> = _state.asStateFlow()

    private val _loadedAsset = MutableStateFlow<LoadedAsset>(AssetLoader.DEFAULT_PUPPET)
    val loadedAsset: StateFlow<LoadedAsset> = _loadedAsset.asStateFlow()

    private val KEY_LAST_URI  = stringPreferencesKey("last_asset_uri")
    private val KEY_LAST_NAME = stringPreferencesKey("last_asset_name")

    init {
        scope.launch(Dispatchers.IO) {
            // Generate bundled assets
            val modelsDir = File(app.cacheDir, "models")
            BundledAssetGenerator.ensureAssets(app, modelsDir)

            // Restore last-used asset
            val prefs = app.assetDataStore.data.first()
            val uri  = prefs[KEY_LAST_URI]?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val name = prefs[KEY_LAST_NAME]
            if (uri != null && name != null) {
                loadFromUri(uri, name)
            }
        }
    }

    fun loadFromUri(uri: Uri, displayName: String) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val asset = withContext(Dispatchers.IO) {
                runCatching { AssetLoader.load(app, uri) }.getOrNull()
            }
            if (asset != null) {
                _loadedAsset.value = asset
                _state.value = _state.value.copy(
                    isLoading   = false,
                    name        = displayName,
                    slot        = asset.slot
                )
                withContext(Dispatchers.IO) {
                    app.assetDataStore.edit { prefs ->
                        prefs[KEY_LAST_URI]  = uri.toString()
                        prefs[KEY_LAST_NAME] = displayName
                    }
                }
            } else {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    fun loadFromPath(path: String, displayName: String) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val asset = withContext(Dispatchers.IO) {
                runCatching { AssetLoader.loadFromPath(path) }.getOrNull()
            }
            if (asset != null) {
                _loadedAsset.value = asset
                _state.value = _state.value.copy(
                    isLoading = false,
                    name      = displayName,
                    slot      = asset.slot
                )
            } else {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    fun loadBundled(filename: String, displayName: String) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val asset = withContext(Dispatchers.IO) {
                runCatching { AssetLoader.loadFromAssets(app, filename) }.getOrNull()
            }
            if (asset != null) {
                _loadedAsset.value = asset
                _state.value = _state.value.copy(
                    isLoading = false,
                    name      = displayName,
                    slot      = asset.slot
                )
            } else {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    fun remove() {
        _loadedAsset.value = AssetLoader.DEFAULT_PUPPET
        _state.value = AssetState()
        scope.launch(Dispatchers.IO) {
            app.assetDataStore.edit { it.clear() }
        }
    }
}

/**
 * Immutable asset state snapshot.
 */
data class AssetState(
    val isLoading: Boolean   = false,
    val name:      String?   = null,
    val slot:      AssetSlot = AssetSlot.HAND_PUPPET
)
