// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances

import com.singularity.common.model.InstanceConfig
import com.singularity.common.model.InstanceType
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * Sort order dla listy instancji. `MC_VERSION` używa semver-aware `MinecraftVersionComparator`
 * — regression dla S8 v2 (string sort bug: "1.9" > "1.20.1").
 */
enum class InstanceSortMode(val i18nKey: String) {
    NAME("instances.sort.name"),
    LAST_PLAYED("instances.sort.last_played"),
    MC_VERSION("instances.sort.mc_version")
}

/**
 * Filter po type instancji. `ALL` = bez filtra. `ENHANCED` / `VANILLA` — pattern matching.
 */
enum class InstanceFilter(val i18nKey: String) {
    ALL("instances.filter.all"),
    ENHANCED("instances.filter.enhanced"),
    VANILLA("instances.filter.vanilla")
}

/**
 * Tryb wyświetlania listy instancji. Toggle przez SegmentedButton w toolbar.
 */
enum class InstanceViewMode { GRID, LIST }

/**
 * Stan ekranu instancji. `filteredInstances` jest computed property wywołujący filtrowanie →
 * wyszukiwanie → sortowanie w jednym pass. Zawsze świeże względem state (nie cachowane) —
 * StateFlow emituje cały state, Compose tylko recompose'uje jeśli filteredInstances realnie
 * się zmieniło.
 */
data class InstancesState(
    val instances: List<InstanceManager.Instance> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val sortMode: InstanceSortMode = InstanceSortMode.LAST_PLAYED,
    val filter: InstanceFilter = InstanceFilter.ALL,
    val viewMode: InstanceViewMode = InstanceViewMode.GRID,
    val isWizardOpen: Boolean = false
) {
    val filteredInstances: List<InstanceManager.Instance>
        get() {
            var result = instances

            // 1. Filter po type
            result = when (filter) {
                InstanceFilter.ALL -> result
                InstanceFilter.ENHANCED -> result.filter { it.config.type == InstanceType.ENHANCED }
                InstanceFilter.VANILLA -> result.filter { it.config.type == InstanceType.VANILLA }
            }

            // 2. Search po nazwie (case-insensitive)
            if (searchQuery.isNotBlank()) {
                val query = searchQuery.lowercase()
                result = result.filter { it.config.name.lowercase().contains(query) }
            }

            // 3. Sort
            result = when (sortMode) {
                InstanceSortMode.NAME -> result.sortedBy { it.config.name.lowercase() }
                InstanceSortMode.LAST_PLAYED -> result.sortedByDescending { it.lastPlayedAt ?: 0L }
                InstanceSortMode.MC_VERSION -> {
                    val cmp = MinecraftVersionComparator()
                    result.sortedWith(compareByDescending(cmp) { it.config.minecraftVersion })
                }
            }

            return result
        }
}

/**
 * ViewModel dla InstancesScreen. Ładuje instancje przez `InstanceManager.getAll()` w init.
 * Eksponuje mutable handlery dla toolbar (search/sort/filter/view toggle/wizard open).
 *
 * Constructor wymaga `InstanceManager` (DI z Task 32). `dispatcher` param dla testowalności
 * (default Swing, test pass UnconfinedTestDispatcher).
 */
class InstancesViewModel(
    private val instanceManager: InstanceManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<InstancesState>(InstancesState(), dispatcher) {

    init {
        loadInstances()
    }

    private fun loadInstances() {
        updateState { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val all = instanceManager.getAll()
                updateState { it.copy(instances = all, isLoading = false) }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun setSearchQuery(query: String) = updateState { it.copy(searchQuery = query) }
    fun setSortMode(mode: InstanceSortMode) = updateState { it.copy(sortMode = mode) }
    fun setFilter(filter: InstanceFilter) = updateState { it.copy(filter = filter) }
    fun setViewMode(mode: InstanceViewMode) = updateState { it.copy(viewMode = mode) }
    fun openWizard() = updateState { it.copy(isWizardOpen = true) }
    fun closeWizard() = updateState { it.copy(isWizardOpen = false) }

    fun refresh() = loadInstances()

    fun createInstance(config: InstanceConfig) {
        viewModelScope.launch {
            try {
                instanceManager.create(config)
                loadInstances()
            } catch (e: Exception) {
                updateState { it.copy(error = "Nie udało się utworzyć instancji: ${e.message}") }
            }
        }
    }
}
