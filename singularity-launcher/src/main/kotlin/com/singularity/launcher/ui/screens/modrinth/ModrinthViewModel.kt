package com.singularity.launcher.ui.screens.modrinth

import com.singularity.common.model.LoaderType
import com.singularity.launcher.service.modrinth.ModrinthClient
import com.singularity.launcher.service.modrinth.ModrinthError
import com.singularity.launcher.service.modrinth.ModrinthSearchHit
import com.singularity.launcher.service.modrinth.ModrinthVersion
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * Install dialog state — open przy kliku "Instaluj" w ModrinthCard, zamknięty domyślnie.
 */
data class InstallDialogState(
    val projectId: String,
    val title: String,
    val versions: List<ModrinthVersion>
)

data class ModrinthScreenState(
    val query: String = "",
    val results: List<ModrinthSearchHit> = emptyList(),
    val lastSuccessResults: List<ModrinthSearchHit> = emptyList(),  // #1 edge-case — keep during filter change
    val isLoading: Boolean = false,
    val error: ModrinthError? = ModrinthError.NoQuery,
    val gameVersion: String = "1.20.1",
    val loader: String? = null,  // null = all
    val category: String? = null,
    val sortMode: String = "relevance",  // relevance/downloads/updated/newest
    val showAllLoaders: Boolean = false,
    val installDialog: InstallDialogState? = null
)

/**
 * ModrinthViewModel — live search z debounce (500ms) + cancel-in-flight previous job.
 *
 * **#2 edge-case CRITICAL:** debounce 500ms + `searchJob: Job?` cancellation przed
 * uruchomieniem nowego search. Zapobiega wysyłaniu zbędnych requestów.
 *
 * **#3 edge-case:** maps exceptions do ModrinthError sealed class dla user-friendly
 * messages:
 * - RateLimitException -> ModrinthError.RateLimit
 * - UnknownHostException -> ModrinthError.Offline
 * - IOException -> ModrinthError.Network
 * - pozostałe -> ModrinthError.Network(generic)
 *
 * **#5 edge-case:** `lockedLoader` param — gdy user otwiera z InstancePanel, loader
 * jest zablokowany (żeby searchable mody matchowały instance loader). Toggle
 * `showAllLoaders` może odblokować.
 */
class ModrinthViewModel(
    private val client: ModrinthClient,
    private val lockedLoader: LoaderType? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<ModrinthScreenState>(
    ModrinthScreenState(
        loader = lockedLoader?.name?.lowercase()
    ),
    dispatcher
) {

    private var searchJob: Job? = null

    fun setQuery(query: String) {
        updateState { it.copy(query = query) }
        scheduleSearch()
    }

    fun setGameVersion(version: String) {
        updateState { it.copy(gameVersion = version) }
        scheduleSearch()
    }

    fun setLoader(loader: String?) {
        updateState { it.copy(loader = loader) }
        scheduleSearch()
    }

    fun setCategory(category: String?) {
        updateState { it.copy(category = category) }
        scheduleSearch()
    }

    fun setSortMode(mode: String) {
        updateState { it.copy(sortMode = mode) }
        scheduleSearch()
    }

    fun showAllLoaders(show: Boolean) {
        updateState { it.copy(showAllLoaders = show) }
        scheduleSearch()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)  // debounce
            performSearch()
        }
    }

    private suspend fun performSearch() {
        val currentState = state.value

        if (currentState.query.isBlank()) {
            updateState { it.copy(results = emptyList(), error = ModrinthError.NoQuery, isLoading = false) }
            return
        }

        updateState { it.copy(isLoading = true, error = null) }

        // Build facets based on filters
        val facets = mutableListOf<List<String>>()
        facets.add(listOf("project_type:mod"))
        facets.add(listOf("versions:${currentState.gameVersion}"))

        val effectiveLoader = if (currentState.showAllLoaders) null else currentState.loader
        if (effectiveLoader != null) {
            facets.add(listOf("categories:$effectiveLoader"))
        }
        if (currentState.category != null) {
            facets.add(listOf("categories:${currentState.category}"))
        }

        val result = client.search(
            query = currentState.query,
            facets = facets,
            limit = 20,
            offset = 0,
            sort = currentState.sortMode
        )

        result.fold(
            onSuccess = { hits ->
                val error = if (hits.isEmpty()) ModrinthError.EmptyResults else null
                updateState {
                    it.copy(
                        results = hits,
                        lastSuccessResults = if (hits.isNotEmpty()) hits else it.lastSuccessResults,
                        isLoading = false,
                        error = error
                    )
                }
            },
            onFailure = { throwable ->
                val error = mapException(throwable)
                updateState { it.copy(isLoading = false, error = error) }
            }
        )
    }

    private fun mapException(throwable: Throwable): ModrinthError = when {
        throwable is ModrinthClient.RateLimitException -> ModrinthError.RateLimit(throwable.retryAfterSec)
        throwable is java.net.UnknownHostException -> ModrinthError.Offline
        throwable is java.io.IOException -> ModrinthError.Network(throwable.message ?: "Connection error")
        throwable.cause is java.net.UnknownHostException -> ModrinthError.Offline
        throwable.cause is java.io.IOException -> ModrinthError.Network(throwable.message ?: "Connection error")
        else -> ModrinthError.Network(throwable.message ?: "Unknown error")
    }

    fun openInstallDialog(hit: ModrinthSearchHit) {
        viewModelScope.launch {
            val result = client.getVersions(
                projectId = hit.projectId,
                gameVersions = listOf(state.value.gameVersion),
                loaders = state.value.loader?.let { listOf(it) } ?: emptyList()
            )
            result.fold(
                onSuccess = { versions ->
                    updateState {
                        it.copy(installDialog = InstallDialogState(
                            projectId = hit.projectId,
                            title = hit.title,
                            versions = versions
                        ))
                    }
                },
                onFailure = { throwable ->
                    updateState { it.copy(error = mapException(throwable)) }
                }
            )
        }
    }

    fun closeInstallDialog() {
        updateState { it.copy(installDialog = null) }
    }

    override fun onCleared() {
        searchJob?.cancel()
        super.onCleared()
    }
}
