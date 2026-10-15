package com.arhand.feature.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the enabled/disabled state of the three tracking subsystems.
 *
 * Responsibilities (and nothing else):
 *  - Body pose tracking toggle
 *  - Face expression tracking toggle
 *  - Biomechanical constraint filter toggle
 *
 * Consumed by [com.arhand.ui.AppViewModel] to gate pipeline calls and
 * by [com.arhand.ui.SettingsScreen] for the toggle UI.
 */
class TrackingManager {

    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    fun setBodyTracking(enabled: Boolean) {
        _state.value = _state.value.copy(bodyEnabled = enabled)
    }

    fun setFaceTracking(enabled: Boolean) {
        _state.value = _state.value.copy(faceEnabled = enabled)
    }

    fun setConstraint(enabled: Boolean) {
        _state.value = _state.value.copy(constraintEnabled = enabled)
    }

    fun toggleBody()       = setBodyTracking(!_state.value.bodyEnabled)
    fun toggleFace()       = setFaceTracking(!_state.value.faceEnabled)
    fun toggleConstraint() = setConstraint(!_state.value.constraintEnabled)
}

/**
 * Immutable state snapshot for the tracking subsystem.
 *
 * @param bodyEnabled        True when [com.arhand.tracking.BodyPipeline] is active.
 * @param faceEnabled        True when [com.arhand.tracking.FacePipeline] is active.
 * @param constraintEnabled  True when [com.arhand.mocap.BiomechanicalConstraintFilter] runs.
 */
data class TrackingState(
    val bodyEnabled:       Boolean = false,
    val faceEnabled:       Boolean = false,
    val constraintEnabled: Boolean = false
)
