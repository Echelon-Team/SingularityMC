package com.singularity.launcher.service.modrinth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.singularity.launcher.config.OfflineMode
import java.net.URLEncoder

/**
 * Real implementation ModrinthClient z Ktor HTTP client.
 *
 * **Base URL:** https://api.modrinth.com/v2
 * **User-Agent required:** SingularityMC/<version> (contact@singularitymc.dev)
 * **Rate limit handling:** 429 → RateLimitException z Retry-After parsed
 *
 * **Constructor w testach:** akceptuje `HttpClient` (z MockEngine) — ułatwia unit testy.
 * Produkcja używa `createDefault()` który konfiguruje pełny stack (CIO + plugins).
 */
class ModrinthClientImpl(
    private val httpClient: HttpClient
) : ModrinthClient {

    companion object {
        const val BASE_URL = "https://api.modrinth.com/v2"
        const val USER_AGENT = "SingularityMC/0.1 (contact@singularitymc.dev)"

        /**
         * Create default HttpClient z wszystkimi pluginami (production).
         * Sub 4 Task 32 wire uses this w App.kt DI.
         *
         * Uwaga: User-Agent + Accept są ustawiane per request explicite w search/getVersions
         * (nie używamy DefaultRequest plugin — prostszy API w Ktor 3.4).
         */
        fun createDefault(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
            // Fix 27.2 web-researcher: install(Logging) żeby ktor-client-logging nie był
            // nieużywaną dep. Pomaga debugować Modrinth 429/502 w live testach.
            install(Logging) {
                level = LogLevel.INFO
            }
        }
    }

    // Fix 27.1 web-researcher: Modrinth 300/min jest time-windowed, Semaphore(300) był concurrent-limiter
    // (permity nigdy nie zwalniane po czasie). Launcher nie robi 300 parallel requestów, więc semaphore
    // jest bezużyteczny. Polegamy na reactive 429 handling + Retry-After backoff caller-side.

    override suspend fun search(
        query: String,
        facets: List<List<String>>,
        limit: Int,
        offset: Int,
        sort: String
    ): Result<List<ModrinthSearchHit>> {
        // Offline mode: spec §4.11 wymaga że Modrinth integration jest
        // wyłączone gdy launcher uruchomiony z `--offline`. Zwracamy
        // pustą listę zamiast uderzać api.modrinth.com → UI pokazuje
        // banner "Tryb offline: Modrinth niedostępny". Failure byłoby
        // wrong semantic (no network error, intentional gate).
        if (OfflineMode.isEnabled()) {
            return Result.success(emptyList())
        }
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val facetsJson = buildFacetsJson(facets)
            val encodedFacets = URLEncoder.encode(facetsJson, "UTF-8")

            val url = buildString {
                append("$BASE_URL/search?query=$encodedQuery")
                append("&limit=$limit&offset=$offset&index=$sort")
                if (facets.isNotEmpty()) append("&facets=$encodedFacets")
            }

            val response = httpClient.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    val body = response.body<SearchResponse>()
                    Result.success(body.hits.map { it.toModrinthSearchHit() })
                }
                HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers["Retry-After"]?.toIntOrNull() ?: 60
                    Result.failure(ModrinthClient.RateLimitException(retryAfter))
                }
                else -> Result.failure(RuntimeException("HTTP ${response.status.value}: ${response.bodyAsText()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getVersions(
        projectId: String,
        gameVersions: List<String>,
        loaders: List<String>
    ): Result<List<ModrinthVersion>> {
        // Same offline gate jako search() — see comment there.
        if (OfflineMode.isEnabled()) {
            return Result.success(emptyList())
        }
        return try {
            val encodedProjectId = URLEncoder.encode(projectId, "UTF-8")
            val url = buildString {
                append("$BASE_URL/project/$encodedProjectId/version")
                val params = mutableListOf<String>()
                if (gameVersions.isNotEmpty()) {
                    val gvJson = gameVersions.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
                    params.add("game_versions=${URLEncoder.encode(gvJson, "UTF-8")}")
                }
                if (loaders.isNotEmpty()) {
                    val ldJson = loaders.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
                    params.add("loaders=${URLEncoder.encode(ldJson, "UTF-8")}")
                }
                if (params.isNotEmpty()) append("?" + params.joinToString("&"))
            }

            val response = httpClient.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    val versions = response.body<List<VersionResponse>>()
                    Result.success(versions.map { it.toModrinthVersion() })
                }
                HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers["Retry-After"]?.toIntOrNull() ?: 60
                    Result.failure(ModrinthClient.RateLimitException(retryAfter))
                }
                else -> Result.failure(RuntimeException("HTTP ${response.status.value}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Build facets JSON string w 2D array format (BUG FIX from web-researcher):
     * `[["project_type:mod"],["versions:1.20.1"],["categories:fabric"]]`
     *
     * Każda inner lista to OR group (any must match), outer lista to AND (all must match).
     */
    private fun buildFacetsJson(facets: List<List<String>>): String {
        if (facets.isEmpty()) return "[]"
        return facets.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { group ->
            group.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
        }
    }

    @Serializable
    private data class SearchResponse(
        val hits: List<SearchHitDto>,
        val offset: Int = 0,
        val limit: Int = 0,
        val total_hits: Int = 0
    )

    @Serializable
    private data class SearchHitDto(
        val project_id: String,
        val slug: String,
        val title: String,
        val description: String = "",
        val icon_url: String? = null,
        val downloads: Int = 0,
        val categories: List<String> = emptyList(),
        val versions: List<String> = emptyList(),
        val loaders: List<String> = emptyList()
    ) {
        fun toModrinthSearchHit() = ModrinthSearchHit(
            projectId = project_id,
            slug = slug,
            title = title,
            description = description,
            iconUrl = icon_url,
            downloads = downloads,
            categories = categories,
            gameVersions = versions,
            loaders = loaders
        )
    }

    @Serializable
    private data class VersionResponse(
        val id: String,
        val project_id: String,
        val name: String,
        val version_number: String,
        val game_versions: List<String>,
        val loaders: List<String>,
        val files: List<VersionFileDto>
    ) {
        fun toModrinthVersion() = ModrinthVersion(
            id = id,
            projectId = project_id,
            name = name,
            versionNumber = version_number,
            gameVersions = game_versions,
            loaders = loaders,
            files = files.map { ModrinthVersionFile(it.url, it.filename, it.size, it.hashes) }
        )
    }

    @Serializable
    private data class VersionFileDto(
        val url: String,
        val filename: String,
        val size: Long,
        val hashes: Map<String, String> = emptyMap()
    )
}
