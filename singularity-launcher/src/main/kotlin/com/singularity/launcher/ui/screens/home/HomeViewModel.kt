package com.singularity.launcher.ui.screens.home

import com.singularity.common.model.InstanceType
import com.singularity.launcher.config.OfflineMode
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.service.news.NewsCache
import com.singularity.launcher.service.news.NewsRepository
import com.singularity.launcher.service.news.ReleaseInfo
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * State dla HomeScreen.
 *
 * **Legacy news/NewsItem fields** (`news`, `isLoadingNews`, `newsError`, `NewsItem`) are
 * scheduled for removal in **Task 1.9** — HomeScreen refactor will consume `releases`
 * directly. Marked @Deprecated below to generate IDE warnings until removal.
 *
 * **releases / releasesError:** GitHub Releases feed per spec 4.12 — 3 latest stable
 * releases fetched via [NewsRepository]. `releasesError` distinguishes failure modes:
 * - null = releases valid (empty or populated)
 * - "offline" = user launched with --offline flag
 * - "unavailable" = repo/cache wiring missing (dev/test)
 * - "fetch-failed" = network/API error (repo returned empty via contract)
 */
data class HomeState(
    val lastPlayedInstance: LastPlayedInfo? = null,
    @Deprecated("Removed in Task 1.9 — use releases instead")
    val news: List<NewsItem> = emptyList(),
    @Deprecated("Removed in Task 1.9 — use isLoadingReleases")
    val isLoadingNews: Boolean = false,
    @Deprecated("Removed in Task 1.9 — use releasesError")
    val newsError: String? = null,
    val releases: List<ReleaseInfo> = emptyList(),
    val isLoadingReleases: Boolean = false,
    val releasesError: String? = null,
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

@Deprecated("Removed in Task 1.9 — replaced by ReleaseInfo from GitHub")
@Serializable
data class NewsItem(
    val id: String,
    val title: String,
    val description: String,
    val imageUrl: String? = null,
    val publishedAt: String,
    val url: String? = null
)

/**
 * Format "Survival World — MC 1.20.1 Enhanced — grane 2h temu"
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
        @Suppress("DEPRECATION")
        loadNews()
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
            } catch (e: Exception) {
                updateState { it.copy(lastPlayedInstance = null) }
            }
        }
    }

    @Deprecated("Removed in Task 1.9 — replaced by GitHub Releases via loadReleases")
    private fun loadNews() {
        updateState { it.copy(isLoadingNews = true, newsError = null) }
        viewModelScope.launch {
            try {
                val newsList = loadBundledNews()
                updateState { it.copy(news = newsList, isLoadingNews = false) }
            } catch (e: Exception) {
                updateState { it.copy(isLoadingNews = false, newsError = e.message) }
            }
        }
    }

    private fun loadBundledNews(): List<NewsItem> {
        val stream = javaClass.getResourceAsStream("/news/news.json")
            ?: return emptyList()
        val content = stream.bufferedReader().use { it.readText() }
        return Json { ignoreUnknownKeys = true }.decodeFromString(content)
    }

    /**
     * Fetch latest stable releases from GitHub Releases API per spec 4.12.
     *
     * Strategy:
     * 1. [OfflineMode] enabled → `releasesError = "offline"`, empty list.
     * 2. [newsRepository] or [newsCache] absent (null) → `releasesError = "unavailable"`,
     *    empty list. Production wiring bug OR legacy 2-arg ctor; logged as warn.
     * 3. Cache hit → use cached value, clear error.
     * 4. Cache miss → fetch via repository (with defense try/catch), populate cache on
     *    non-empty success. Empty fetch NOT cached (allows retry on next call).
     *
     * Private by design — called from [init] only. UI-driven refresh not yet in scope.
     */
    private fun loadReleases() {
        if (OfflineMode.isEnabled()) {
            updateState { it.copy(releases = emptyList(), isLoadingReleases = false, releasesError = "offline") }
            return
        }
        if (newsRepository == null || newsCache == null) {
            logger.warn(
                "HomeViewModel: news wiring missing (newsRepository={}, newsCache={}) — releases disabled",
                newsRepository != null,
                newsCache != null,
            )
            updateState { it.copy(releases = emptyList(), isLoadingReleases = false, releasesError = "unavailable") }
            return
        }

        newsCache.get()?.let { cached ->
            updateState { it.copy(releases = cached, isLoadingReleases = false, releasesError = null) }
            return
        }

        updateState { it.copy(isLoadingReleases = true, releasesError = null) }
        viewModelScope.launch {
            // Defense-in-depth: NewsRepository contract guarantees empty-on-failure, but guard
            // against future refactor that could leak exceptions.
            val fetched = try {
                newsRepository.fetchLatestReleases(limit = 3)
            } catch (e: Exception) {
                logger.warn("Unexpected exception from NewsRepository: {}", e.message)
                emptyList()
            }
            if (fetched.isNotEmpty()) {
                newsCache.put(fetched)  // don't cache empty — next load retries
                updateState { it.copy(releases = fetched, isLoadingReleases = false, releasesError = null) }
            } else {
                updateState { it.copy(releases = emptyList(), isLoadingReleases = false, releasesError = "fetch-failed") }
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
