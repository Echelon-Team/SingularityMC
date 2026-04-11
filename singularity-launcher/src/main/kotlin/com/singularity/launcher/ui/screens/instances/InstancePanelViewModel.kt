package com.singularity.launcher.ui.screens.instances

import com.singularity.launcher.config.InstanceRuntimeSettings
import com.singularity.launcher.config.InstanceRuntimeSettingsStore
import com.singularity.launcher.config.PreGenPreset
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * Stan drill-down screen dla pojedynczej instancji. Trzyma zarówno dane instancji (z
 * `InstanceManager`) jak i runtime launch state (`isLaunching`, `launchProgress`) —
 * launchProgress jest StateFlow-backed żeby przetrwać recomposition (S3 v1 fix).
 */
data class InstancePanelState(
    val instance: InstanceManager.Instance? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLaunching: Boolean = false,
    /** 0..100 podczas ładowania gry, null gdy nie launching. */
    val launchProgress: Int? = null,
    val runtimeSettings: InstanceRuntimeSettings = InstanceRuntimeSettings()
)

/**
 * ViewModel dla `InstancePanel` drill-down screen.
 */
class InstancePanelViewModel(
    private val instanceManager: InstanceManager,
    private val instanceId: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<InstancePanelState>(
    InstancePanelState(),
    dispatcher
) {

    init {
        loadInstance()
    }

    private fun loadInstance() {
        updateState { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val instance = instanceManager.getById(instanceId)
                if (instance == null) {
                    updateState { it.copy(isLoading = false, error = "Instance not found: $instanceId") }
                    return@launch
                }
                val settings = InstanceRuntimeSettingsStore.load(instance.rootDir)
                updateState {
                    it.copy(
                        instance = instance,
                        runtimeSettings = settings,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun startLaunch() = updateState { it.copy(isLaunching = true, launchProgress = 0, error = null) }

    fun updateLaunchProgress(progress: Int) = updateState {
        it.copy(launchProgress = progress.coerceIn(0, 100))
    }

    fun finishLaunch() = updateState { it.copy(isLaunching = false, launchProgress = null) }

    fun failLaunch(errorMessage: String) = updateState {
        it.copy(isLaunching = false, launchProgress = null, error = errorMessage)
    }

    fun updatePreGenRadius(radius: Int) {
        val coerced = radius.coerceIn(16, 256)
        updateState { it.copy(runtimeSettings = it.runtimeSettings.copy(preGenRadius = coerced)) }
        saveSettings()
    }

    fun applyPreGenPreset(preset: PreGenPreset) {
        updateState {
            it.copy(runtimeSettings = it.runtimeSettings.copy(
                preGenPreset = preset,
                preGenRadius = preset.defaultRadius
            ))
        }
        saveSettings()
    }

    private fun saveSettings() {
        val instance = state.value.instance ?: return
        val settings = state.value.runtimeSettings
        viewModelScope.launch {
            try {
                InstanceRuntimeSettingsStore.save(instance.rootDir, settings)
            } catch (e: Exception) {
                updateState { it.copy(error = "Failed to save settings: ${e.message}") }
            }
        }
    }
}
