// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances.settings

import com.singularity.launcher.config.InstanceRuntimeSettings
import com.singularity.launcher.config.InstanceRuntimeSettingsStore
import com.singularity.launcher.config.ManualThreadConfig
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.nio.file.Path

/**
 * ViewModel dla `InstanceSettingsModal`. Ładuje aktualne settings instancji przez
 * `InstanceRuntimeSettingsStore.load(instanceDir)` na init. Zapisuje przez `save` na commit.
 *
 * **Lifecycle:** Modal short-lived — tworzony na open, `onCleared` wywołany na close
 * przez DisposableEffect w InstanceSettingsModal composable.
 */
class InstanceSettingsModalViewModel(
    private val instanceDir: Path,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<InstanceSettingsModalState>(
    InstanceSettingsModalState(
        originalSettings = InstanceRuntimeSettings(),
        workingSettings = InstanceRuntimeSettings()
    ),
    dispatcher
) {

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val loaded = InstanceRuntimeSettingsStore.load(instanceDir)
            updateState {
                it.copy(
                    originalSettings = loaded,
                    workingSettings = loaded
                )
            }
        }
    }

    fun switchTab(tab: InstanceSettingsTab) {
        updateState { InstanceSettingsModalLogic.switchTab(it, tab) }
    }

    fun updateRegionSize(size: Int) {
        updateState { InstanceSettingsModalLogic.updateRegionSize(it, size) }
    }

    fun updateGpuAcceleration(enabled: Boolean) {
        updateState { InstanceSettingsModalLogic.updateGpuAcceleration(it, enabled) }
    }

    fun updateUnloadDelay(sec: Int) {
        updateState { InstanceSettingsModalLogic.updateUnloadDelay(it, sec) }
    }

    fun updateMemoryThreshold(percent: Int) {
        updateState { InstanceSettingsModalLogic.updateMemoryThreshold(it, percent) }
    }

    fun toggleManualThreadMode(enabled: Boolean) {
        updateState { InstanceSettingsModalLogic.toggleManualThreadMode(it, enabled) }
    }

    fun updateManualThreads(transform: (ManualThreadConfig) -> ManualThreadConfig) {
        updateState { InstanceSettingsModalLogic.updateManualThreads(it, transform) }
    }

    fun revert() {
        updateState { InstanceSettingsModalLogic.revert(it) }
    }

    fun save(onDone: () -> Unit) {
        val current = state.value
        if (!current.isDirty) {
            onDone()
            return
        }
        updateState { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            try {
                InstanceRuntimeSettingsStore.save(instanceDir, current.workingSettings)
                updateState {
                    it.copy(
                        originalSettings = it.workingSettings,
                        isSaving = false
                    )
                }
                onDone()
            } catch (e: Exception) {
                updateState { it.copy(isSaving = false, saveError = e.message) }
            }
        }
    }
}
