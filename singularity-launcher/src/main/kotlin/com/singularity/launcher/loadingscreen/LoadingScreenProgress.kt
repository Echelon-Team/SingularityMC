package com.singularity.launcher.loadingscreen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Progress loading screen w launcherze — pokazuje "Ladowanie... (45%)"
 * w headerze instancji. Dane z IPC (agent broadcastuje progress).
 */
data class LoadingStatus(
    val progress: Int,
    val currentStage: String,
    val isLoading: Boolean
)

class LoadingScreenProgress {
    private val _status = MutableStateFlow(LoadingStatus(0, "", false))
    val status: StateFlow<LoadingStatus> = _status.asStateFlow()

    fun update(progress: Int, stage: String) {
        _status.value = LoadingStatus(progress, stage, progress < 100)
    }

    fun finish() {
        _status.value = LoadingStatus(100, "Gotowe", false)
    }

    fun reset() {
        _status.value = LoadingStatus(0, "", isLoading = false)
    }

    val isActive: Boolean get() = _status.value.isLoading
}
