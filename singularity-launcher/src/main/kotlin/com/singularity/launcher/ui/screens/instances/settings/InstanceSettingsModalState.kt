// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances.settings

import com.singularity.launcher.config.InstanceRuntimeSettings
import com.singularity.launcher.config.ManualThreadConfig

/**
 * 4 zakładki modalu ustawień instancji. `CHUNK_GEN` tab jest nazwany "Generowanie terenu"
 * (PN1 v1 fix — NIE "C2ME / Generowanie"), bo C2ME jest implementacją a "Generowanie terenu"
 * jest user-facing określeniem.
 */
enum class InstanceSettingsTab(val i18nKey: String) {
    GENERAL("settings.tab.general"),
    RESOURCES("settings.tab.resources"),
    THREADING("settings.tab.threading"),
    CHUNK_GEN("settings.tab.chunk_gen")
}

/**
 * Stan modalu ustawień instancji. Trzyma `originalSettings` (snapshot po load)
 * i `workingSettings` (aktualnie edytowane przez user). `isDirty` jest computed z różnicy.
 *
 * **Revert:** przywraca `workingSettings = originalSettings`.
 * **Commit (save):** ustawia `originalSettings = workingSettings` i zapisuje na dysk.
 */
data class InstanceSettingsModalState(
    val currentTab: InstanceSettingsTab = InstanceSettingsTab.GENERAL,
    val originalSettings: InstanceRuntimeSettings,
    val workingSettings: InstanceRuntimeSettings,
    val isSaving: Boolean = false,
    val saveError: String? = null
) {
    val isDirty: Boolean get() = originalSettings != workingSettings
}

/**
 * Pure logic dla modalu ustawień — testowalne bez Compose.
 */
object InstanceSettingsModalLogic {

    val allowedRegionSizes: List<Int> = listOf(4, 6, 8, 12, 16)

    fun revert(state: InstanceSettingsModalState): InstanceSettingsModalState =
        state.copy(workingSettings = state.originalSettings)

    fun commit(state: InstanceSettingsModalState): InstanceSettingsModalState =
        state.copy(originalSettings = state.workingSettings)

    fun switchTab(state: InstanceSettingsModalState, tab: InstanceSettingsTab): InstanceSettingsModalState =
        state.copy(currentTab = tab)

    fun updateRegionSize(state: InstanceSettingsModalState, newSize: Int): InstanceSettingsModalState {
        val coerced = if (newSize in allowedRegionSizes) newSize else state.workingSettings.regionSize
        return state.copy(workingSettings = state.workingSettings.copy(regionSize = coerced))
    }

    fun updateGpuAcceleration(state: InstanceSettingsModalState, enabled: Boolean): InstanceSettingsModalState =
        state.copy(workingSettings = state.workingSettings.copy(gpuAcceleration = enabled))

    fun updateUnloadDelay(state: InstanceSettingsModalState, sec: Int): InstanceSettingsModalState {
        val coerced = sec.coerceIn(60, 3600)
        return state.copy(workingSettings = state.workingSettings.copy(unloadDelaySec = coerced))
    }

    fun updateMemoryThreshold(state: InstanceSettingsModalState, percent: Int): InstanceSettingsModalState {
        val coerced = percent.coerceIn(50, 95)
        return state.copy(workingSettings = state.workingSettings.copy(memoryThresholdPercent = coerced))
    }

    fun toggleManualThreadMode(state: InstanceSettingsModalState, enabled: Boolean): InstanceSettingsModalState {
        val newConfig = if (enabled) ManualThreadConfig() else null
        return state.copy(workingSettings = state.workingSettings.copy(manualThreadConfig = newConfig))
    }

    fun updateManualThreads(
        state: InstanceSettingsModalState,
        transform: (ManualThreadConfig) -> ManualThreadConfig
    ): InstanceSettingsModalState {
        val current = state.workingSettings.manualThreadConfig ?: return state
        val updated = transform(current)
        return state.copy(workingSettings = state.workingSettings.copy(manualThreadConfig = updated))
    }
}
