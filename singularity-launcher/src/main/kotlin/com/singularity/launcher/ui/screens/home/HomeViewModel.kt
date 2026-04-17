package com.singularity.launcher.ui.screens.home

import com.singularity.common.model.InstanceType
import com.singularity.launcher.config.OfflineMode
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.service.news.NewsCache
import com.singularity.launcher.service.news.NewsRepository
import com.singularity.launcher.service.news.ReleaseInfo
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.slf4j.LoggerFactory

/**
 * Sum type for the GitHub-Releases-driven Aktualności feed per spec 4.12.
 *
 * Replaces a prior stringly-typed `releasesError: String?` + separate `isLoadingReleases`
 * + `releases: List<...>` triple whose cardinality admitted invalid combinations (e.g.
 * loading AND offline simultaneously). Each state now rendered by exactly one HomeScreen
 * branch; exhaustive `when` over the sealed interface catches new variants at compile time.
 */
sealed interface ReleasesState {
    /** Initial state or active fetch in flight. */
    data object Loading : ReleasesState

    /** User launched with `--offline` — feed intentionally disabled. */
    data object Offline : ReleasesState

    /** Repo/cache dependencies not wired (production bug or legacy ctor used). */
    data object Unavailable : ReleasesState

    /** Network / API / parse failure; user sees retry-implicit error message. */
    data object FetchFailed : ReleasesState

    /** Successful fetch — `releases` may be empty if repo has no stable releases yet. */
    data class Loaded(val releases: List<ReleaseInfo>) : ReleasesState
}

/**
 * State dla HomeScreen.
 *
 * `releasesState` encodes the five mutually-exclusive Aktualności display states — see
 * [ReleasesState] kdoc.
 */
data class HomeState(
    val lastPlayedInstance: LastPlayedInfo? = null,
    // Default is Unavailable (production-safe sentinel for "no wiring yet"). loadReleases()
    // explicitly sets Loading before launching the fetch coroutine, so real fetch flow is
    // unaffected; this default prevents tests / direct HomeState() instantiation from
    // appearing to have an infinite loading spinner.
    val releasesState: ReleasesState = ReleasesState.Unavailable,
)

/**
 * Info o ostatnio granej instancji dla home-continue row display.
 */
data class LastPlayedInfo(
    val instanceId: String,
    val instanceName: String,
    val iconPath: String?,
    val lastPlayedTimestamp: Long,
    val minecraftVersion: String,
    val type: InstanceType
)

/**
 * Format "Survival World — MC 1.20.1 Enhanced — grane 2h temu".
 *
 * NOTE: time-ago strings are intentionally Polish-only for MVP (matches hardcoded
 * `polishMonths` in HomeScreen.kt); i18n-aware rephrasing is a future refactor when EN
 * users are on the roadmap.
 */
fun formatLastPlayedSubtitle(
    name: String,
    version: String,
    type: InstanceType,
    lastPlayedMs: Long,
    now: Long = System.currentTimeMillis()
): String {
    val typeStr = when (type) {
        InstanceType.ENHANCED -> "Enhanced"
        InstanceType.VANILLA -> "Vanilla"
    }
    val diffMs = now - lastPlayedMs
    val diffMin = diffMs / 60_000
    val diffH = diffMin / 60
    val diffD = diffH / 24
    val timeAgo = when {
        diffMin < 1 -> "przed chwilą"
        diffMin < 60 -> "${diffMin}min temu"
        diffH < 24 -> "${diffH}h temu"
        diffD < 7 -> "${diffD}d temu"
        else -> "dawno temu"
    }
    return "$name — MC $version $typeStr — grane $timeAgo"
}

class HomeViewModel(
    private val instanceManager: InstanceManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing,
    private val newsRepository: NewsRepository? = null,
    private val newsCache: NewsCache? = null,
) : BaseViewModel<HomeState>(HomeState(), dispatcher) {

    init {
        loadLastPlayed()
        loadReleases()
    }

    private fun loadLastPlayed() {
        viewModelScope.launch {
            try {
                val lastPlayed = instanceManager.getLastPlayed()
                val info = lastPlayed?.let {
                    LastPlayedInfo(
                        instanceId = it.id,
                        instanceName = it.config.name,
                        iconPath = null,
                        lastPlayedTimestamp = it.lastPlayedAt ?: 0L,
                        minecraftVersion = it.config.minecraftVersion,
                        type = it.config.type
                    )
                }
                updateState { it.copy(lastPlayedInstance = info) }
            } catch (e: CancellationException) {
                throw e  // cooperate with structured concurrency — scope cancel must propagate
            } catch (e: Exception) {
                logger.warn("Failed to load last played instance", e)
                updateState { it.copy(lastPlayedInstance = null) }
            }
        }
    }

    /**
     * Fetch latest stable releases from GitHub Releases API per spec 4.12.
     *
     * Strategy:
     * 1. [OfflineMode] enabled → [ReleasesState.Offline].
     * 2. [newsRepository] or [newsCache] absent (null) → [ReleasesState.Unavailable].
     *    Production wiring bug OR legacy 2-arg ctor; logged as warn.
     * 3. Cache hit → [ReleasesState.Loaded] with cached value.
     * 4. Cache miss → fetch via repository (with defense try/catch), populate cache on
     *    non-empty success. Empty fetch NOT cached (allows retry on next call) and reports
     *    [ReleasesState.FetchFailed].
     *
     * Private by design — called from [init] only. UI-driven refresh not yet in scope.
     */
    private fun loadReleases() {
        if (OfflineMode.isEnabled()) {
            updateState { it.copy(releasesState = ReleasesState.Offline) }
            return
        }
        if (newsRepository == null || newsCache == null) {
            logger.warn(
                "HomeViewModel: news wiring missing (newsRepository={}, newsCache={}) — releases disabled",
                newsRepository != null,
                newsCache != null,
            )
            updateState { it.copy(releasesState = ReleasesState.Unavailable) }
            return
        }

        newsCache.get()?.let { cached ->
            updateState { it.copy(releasesState = ReleasesState.Loaded(cached)) }
            return
        }

        updateState { it.copy(releasesState = ReleasesState.Loading) }
        viewModelScope.launch {
            // Defense-in-depth: NewsRepository contract guarantees empty-on-failure and
            // rethrows CancellationException, but guard against future refactor bugs.
            val fetched = try {
                newsRepository.fetchLatestReleases(limit = 3)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Unexpected exception from NewsRepository", e)
                emptyList()
            }
            if (fetched.isNotEmpty()) {
                newsCache.put(fetched)  // don't cache empty — next load retries
                updateState { it.copy(releasesState = ReleasesState.Loaded(fetched)) }
            } else {
                updateState { it.copy(releasesState = ReleasesState.FetchFailed) }
            }
        }
    }

    fun onContinueClick(onLaunch: (String) -> Unit) {
        val last = state.value.lastPlayedInstance ?: return
        onLaunch(last.instanceId)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HomeViewModel::class.java)
    }
}
