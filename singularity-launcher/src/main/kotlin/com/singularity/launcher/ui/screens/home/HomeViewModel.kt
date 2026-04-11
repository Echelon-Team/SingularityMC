package com.singularity.launcher.ui.screens.home

import com.singularity.common.model.InstanceType
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * State dla HomeScreen.
 */
data class HomeState(
    val lastPlayedInstance: LastPlayedInfo? = null,
    val news: List<NewsItem> = emptyList(),
    val isLoadingNews: Boolean = false,
    val newsError: String? = null
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

@Serializable
data class NewsItem(
    val id: String,
    val title: String,
    val description: String,
    val imageUrl: String? = null,
    val publishedAt: String,  // ISO-8601
    val url: String? = null
)

/**
 * Format "Survival World — MC 1.20.1 Enhanced — grane 2h temu"
 * Prototyp index.html:2014 — extracted helper dla testowalności.
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
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<HomeState>(HomeState(), dispatcher) {

    init {
        loadLastPlayed()
        loadNews()
    }

    private fun loadLastPlayed() {
        viewModelScope.launch {
            try {
                val lastPlayed = instanceManager.getLastPlayed()
                val info = lastPlayed?.let {
                    LastPlayedInfo(
                        instanceId = it.id,
                        instanceName = it.config.name,
                        iconPath = null,  // Custom instance icons = Sub 5 (wymaga InstanceConfig.iconPath field w singularity-common)
                        lastPlayedTimestamp = it.lastPlayedAt ?: 0L,
                        minecraftVersion = it.config.minecraftVersion,
                        type = it.config.type
                    )
                }
                updateState { it.copy(lastPlayedInstance = info) }
            } catch (e: Exception) {
                // Fail soft: no lastPlayed, user widzi empty state
                updateState { it.copy(lastPlayedInstance = null) }
            }
        }
    }

    private fun loadNews() {
        updateState { it.copy(isLoadingNews = true, newsError = null) }
        viewModelScope.launch {
            try {
                // Bundled news z resources/news/news.json — prosty local loader
                val newsList = loadBundledNews()
                updateState { it.copy(news = newsList, isLoadingNews = false) }
            } catch (e: Exception) {
                updateState { it.copy(isLoadingNews = false, newsError = e.message) }
            }
        }
    }

    /**
     * Ładuje bundled news z `resources/news/news.json`. W przyszłości: HTTP fetch
     * z SingularityMC GitHub releases / blog feed (post Sub 5 patch).
     */
    private fun loadBundledNews(): List<NewsItem> {
        val stream = javaClass.getResourceAsStream("/news/news.json")
            ?: return emptyList()
        val content = stream.bufferedReader().use { it.readText() }
        return Json { ignoreUnknownKeys = true }.decodeFromString(content)
    }

    fun onContinueClick(onLaunch: (String) -> Unit) {
        val last = state.value.lastPlayedInstance ?: return
        onLaunch(last.instanceId)
    }
}
