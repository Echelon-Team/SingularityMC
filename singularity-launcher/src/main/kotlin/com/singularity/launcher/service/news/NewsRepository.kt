// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service.news

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Fetches stable releases from GitHub for the launcher's Home → Aktualności feed.
 *
 * **HttpClient requirements (caller-provided):**
 * - Must have `ContentNegotiation` plugin installed with kotlinx-serialization JSON.
 * - `ignoreUnknownKeys = true` is required (GitHub sends many fields we don't model).
 *
 * The HttpClient is passed from outside (shared with ModrinthClient / AutoUpdater to avoid
 * resource duplication). Caller owns the client lifecycle.
 *
 * **Contract:**
 * - Returns up to [limit] most recent STABLE releases (filter `prerelease:false`).
 * - On any failure (HTTP error, network down, rate limit, parse error): returns empty list
 *   and logs a warning. UI gets "no news available" state cleanly; no exceptions propagated.
 * - [CancellationException] is rethrown (structured concurrency: UI navigates away → cancel).
 * - Does NOT check [com.singularity.launcher.config.OfflineMode] internally; caller
 *   (typically `HomeViewModel`) is responsible for skipping the fetch in offline mode.
 *
 * **Rate limits:** GitHub API permits 60 unauthenticated requests/hour per IP. Callers should
 * cache responses ([NewsCache] — Task 1.7) to avoid hammering on every ViewModel recomposition.
 *
 * **API versioning:** pinned to `X-GitHub-Api-Version: 2022-11-28` to protect against future
 * default-version bumps on GitHub's side.
 */
open class NewsRepository(
    private val httpClient: HttpClient,
    private val repoOwner: String = "Echelon-Team",
    private val repoName: String = "SingularityMC",
) {
    private val logger = LoggerFactory.getLogger(NewsRepository::class.java)

    /**
     * @param limit number of stable releases to return (after filtering out pre-releases).
     *              Fetches up to 20 releases from GitHub, filters, then takes [limit].
     *
     * Declared `open` for test double substitution; Ktor's internal dispatcher doesn't
     * respect `runTest` schedulers reliably, so some integration tests override this method.
     */
    open suspend fun fetchLatestReleases(limit: Int = 3): List<ReleaseInfo> {
        return try {
            val url = "https://api.github.com/repos/$repoOwner/$repoName/releases?per_page=20"
            val response: HttpResponse = httpClient.get(url) {
                header("User-Agent", "SingularityMC-Launcher")
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
            if (!response.status.isSuccess()) {
                logger.warn("GitHub Releases API returned HTTP {} for {}", response.status.value, url)
                return emptyList()
            }
            val all: List<ReleaseInfo> = response.body()
            // Defensive dedup: GitHub does NOT actually guarantee tag_name uniqueness
            // across concurrent drafts and published releases (see goreleaser#3148,
            // community/51299 — real-world collisions exist). UI uses tagName as
            // LazyVerticalGrid key — a duplicate here crashes the news card list with
            // IllegalStateException. `distinctBy` is correctness, not paranoia; it's a
            // no-op when there are no duplicates (~zero cost).
            val stable = all.filter { !it.isPrerelease }
            val deduped = stable.distinctBy { it.tagName }
            if (deduped.size != stable.size) {
                logger.warn(
                    "GitHub Releases API returned duplicate tag names — deduped {} → {} entries",
                    stable.size,
                    deduped.size,
                )
            }
            deduped.take(limit)
        } catch (e: CancellationException) {
            throw e  // cooperate with structured concurrency (e.g. UI nav away cancels)
        } catch (e: Exception) {
            logger.warn("Failed to fetch releases from GitHub", e)
            emptyList()
        }
    }
}
