// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service.modrinth

/**
 * Modrinth API client interface — abstraction dla Task 15 ModrinthScreen.
 *
 * **Implementation:** `ModrinthClientImpl` (Task 27) — real Ktor HTTP client
 * do https://api.modrinth.com/v2.
 *
 * **API docs:** https://docs.modrinth.com/api/
 */
interface ModrinthClient {

    /**
     * Wyszukuje projekty (mody) na Modrinth.
     *
     * @param query tekst do wyszukania
     * @param facets 2D array filter (outer=AND, inner=OR). Np.
     *               `[["project_type:mod"],["versions:1.20.1"],["categories:fabric"]]`
     * @param limit max wyniki na stronę (domyślnie 20, max 100)
     * @param offset paginacja
     * @param sort "relevance" | "downloads" | "updated" | "newest"
     */
    suspend fun search(
        query: String,
        facets: List<List<String>> = emptyList(),
        limit: Int = 20,
        offset: Int = 0,
        sort: String = "relevance"
    ): Result<List<ModrinthSearchHit>>

    /**
     * Pobiera wersje konkretnego projektu z filtrem gameVersions + loaders.
     *
     * @param projectId slug lub project_id (np. "sodium", "AANobbMI")
     * @param gameVersions filter po MC version (np. ["1.20.1"])
     * @param loaders filter po loader (np. ["fabric", "forge"])
     */
    suspend fun getVersions(
        projectId: String,
        gameVersions: List<String> = emptyList(),
        loaders: List<String> = emptyList()
    ): Result<List<ModrinthVersion>>

    /**
     * Rzucone gdy API zwraca HTTP 429 (Too Many Requests).
     * Modrinth rate limit: 300 requests/min z `Retry-After` header.
     */
    class RateLimitException(val retryAfterSec: Int) :
        RuntimeException("Modrinth rate limit — retry after $retryAfterSec seconds")
}

/**
 * Search hit (wynik wyszukiwania) — project summary bez pełnych version details.
 */
data class ModrinthSearchHit(
    val projectId: String,
    val slug: String,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val downloads: Int,
    val categories: List<String>,
    val gameVersions: List<String>,
    val loaders: List<String>
)

/**
 * Version — konkretna wersja projektu z downloadable files.
 */
data class ModrinthVersion(
    val id: String,
    val projectId: String,
    val name: String,
    val versionNumber: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val files: List<ModrinthVersionFile>
)

/**
 * Plik do pobrania (jar mod/resource pack/shader).
 * `hashes` ma klucze "sha1" i "sha256" zazwyczaj.
 */
data class ModrinthVersionFile(
    val url: String,
    val filename: String,
    val size: Long,
    val hashes: Map<String, String>
)
