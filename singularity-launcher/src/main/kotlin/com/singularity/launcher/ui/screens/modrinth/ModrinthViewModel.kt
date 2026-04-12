package com.singularity.launcher.ui.screens.modrinth

import com.singularity.common.model.LoaderType
import com.singularity.launcher.security.MalwareScanner
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.service.modrinth.ModrinthClient
import com.singularity.launcher.service.modrinth.ModrinthError
import com.singularity.launcher.service.modrinth.ModrinthSearchHit
import com.singularity.launcher.service.modrinth.ModrinthVersion
import com.singularity.launcher.service.modrinth.ModrinthVersionFile
import com.singularity.launcher.viewmodel.BaseViewModel
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import java.nio.file.Path

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
    val lastSuccessResults: List<ModrinthSearchHit> = emptyList(),
    val isLoading: Boolean = false,
    val error: ModrinthError? = ModrinthError.NoQuery,
    val gameVersion: String = "1.20.1",
    val loader: String? = null,
    val category: String? = null,
    val sortMode: String = "relevance",
    val showAllLoaders: Boolean = false,
    val installDialog: InstallDialogState? = null,
    val availableInstances: List<Pair<String, String>> = emptyList(), // id to name
    val selectedInstanceId: String? = null,
    val installProgress: InstallProgress? = null
)

data class InstallProgress(
    val modName: String,
    val status: InstallStatus,
    val message: String = ""
)

enum class InstallStatus { DOWNLOADING, SCANNING, DONE, ERROR }

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
    private val instanceManager: InstanceManager? = null,
    private val httpClient: HttpClient? = null,
    private val lockedLoader: LoaderType? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<ModrinthScreenState>(
    ModrinthScreenState(
        loader = lockedLoader?.name?.lowercase()
    ),
    dispatcher
) {

    private var searchJob: Job? = null
    private val malwareScanner = MalwareScanner()

    init {
        loadInstances()
    }

    private fun loadInstances() {
        if (instanceManager == null) return
        viewModelScope.launch {
            try {
                val instances = instanceManager.getAll().map { it.id to it.config.name }
                updateState {
                    it.copy(
                        availableInstances = instances,
                        selectedInstanceId = instances.firstOrNull()?.first
                    )
                }
            } catch (_: Exception) {}
        }
    }

    fun selectInstance(instanceId: String) {
        updateState { it.copy(selectedInstanceId = instanceId) }
    }

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

    fun installMod(version: ModrinthVersion) {
        val instanceId = state.value.selectedInstanceId
        if (instanceId == null || instanceManager == null || httpClient == null) {
            updateState { it.copy(error = ModrinthError.Network("Nie wybrano instancji")) }
            closeInstallDialog()
            return
        }

        val file = version.files.firstOrNull() ?: run {
            updateState { it.copy(error = ModrinthError.Network("Brak pliku do pobrania")) }
            closeInstallDialog()
            return
        }

        closeInstallDialog()
        updateState { it.copy(installProgress = InstallProgress(version.name, InstallStatus.DOWNLOADING)) }

        viewModelScope.launch {
            try {
                // 1. Find instance mods dir
                val instance = instanceManager.getById(instanceId) ?: throw Exception("Instancja nie znaleziona")
                val modsDir = instance.rootDir.resolve("mods")
                Files.createDirectories(modsDir)
                val targetFile = modsDir.resolve(file.filename)

                // 2. Download JAR
                updateState { it.copy(installProgress = InstallProgress(version.name, InstallStatus.DOWNLOADING, file.filename)) }
                val response = httpClient.get(file.url)
                val bytes = response.readRawBytes()
                Files.write(targetFile, bytes)

                // 3. Malware scan
                updateState { it.copy(installProgress = InstallProgress(version.name, InstallStatus.SCANNING)) }
                val scanResult = malwareScanner.scan(targetFile)

                if (scanResult.verdict == MalwareScanner.ScanResult.MALICIOUS) {
                    Files.deleteIfExists(targetFile)
                    updateState { it.copy(
                        installProgress = InstallProgress(version.name, InstallStatus.ERROR,
                            "MALWARE WYKRYTY — plik usunięty: ${scanResult.findings.firstOrNull()}")
                    ) }
                    return@launch
                }

                val warning = if (scanResult.verdict == MalwareScanner.ScanResult.SUSPICIOUS) {
                    " (Ostrzeżenie: ${scanResult.findings.size} podejrzanych wzorców)"
                } else ""

                // 4. Done
                updateState { it.copy(
                    installProgress = InstallProgress(version.name, InstallStatus.DONE,
                        "${file.filename} zainstalowany do ${instance.config.name}$warning")
                ) }

                // Clear progress after 3s
                delay(3000)
                updateState { it.copy(installProgress = null) }

            } catch (e: Exception) {
                updateState { it.copy(
                    installProgress = InstallProgress(version.name, InstallStatus.ERROR, e.message ?: "Błąd instalacji")
                ) }
            }
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
