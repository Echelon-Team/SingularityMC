// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.screenshots

import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

data class ScreenshotsScreenState(
    val allEntries: List<ScreenshotEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** null = all instances, non-null = filter by instance id */
    val instanceFilter: String? = null,
    val availableInstances: List<Pair<String, String>> = emptyList(),  // id → name
    val previewEntry: ScreenshotEntry? = null
) {
    val filteredEntries: List<ScreenshotEntry>
        get() = if (instanceFilter == null) allEntries
                else allEntries.filter { it.instanceId == instanceFilter }
}

/**
 * ViewModel dla ScreenshotsScreen. Periodic scan co 10s (#24 edge-case — detect file changes).
 * Trzyma `ScreenshotLruCache` jako singleton-per-vm dla thumbnails.
 */
class ScreenshotsViewModel(
    private val instanceManager: InstanceManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<ScreenshotsScreenState>(
    ScreenshotsScreenState(),
    dispatcher
) {

    companion object {
        const val SCAN_INTERVAL_MS = 10_000L
    }

    val thumbnailCache = ScreenshotLruCache(maxSize = 100)

    private var scanJob: Job? = null

    init {
        loadScreenshots()
        startPeriodicScan()
    }

    private fun loadScreenshots() {
        updateState { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = ScreenshotsScanner.scan(instanceManager)
                val instances = instanceManager.getAll().map { it.id to it.config.name }
                updateState {
                    it.copy(
                        allEntries = entries,
                        availableInstances = instances,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun startPeriodicScan() {
        scanJob = viewModelScope.launch {
            while (true) {
                delay(SCAN_INTERVAL_MS)
                try {
                    val entries = ScreenshotsScanner.scan(instanceManager)
                    // Invalidate cache dla deleted files
                    val currentPaths = state.value.allEntries.map { it.path }
                    val newPaths = entries.map { it.path }.toSet()
                    currentPaths.forEach { path ->
                        if (path !in newPaths) thumbnailCache.invalidate(path)
                    }
                    updateState { it.copy(allEntries = entries) }
                } catch (e: Exception) {
                    // Ignore periodic failures
                }
            }
        }
    }

    fun setInstanceFilter(instanceId: String?) {
        updateState { it.copy(instanceFilter = instanceId) }
    }

    fun openPreview(entry: ScreenshotEntry) {
        updateState { it.copy(previewEntry = entry) }
    }

    fun closePreview() {
        updateState { it.copy(previewEntry = null) }
    }

    fun refresh() = loadScreenshots()

    override fun onCleared() {
        scanJob?.cancel()
        thumbnailCache.clear()
        super.onCleared()
    }
}
