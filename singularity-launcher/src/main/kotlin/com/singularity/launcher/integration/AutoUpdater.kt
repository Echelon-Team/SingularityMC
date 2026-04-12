package com.singularity.launcher.integration

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Auto-updater sprawdzający GitHub Releases.
 *
 * Przy starcie sprawdza najnowszą wersję. Jeśli dostępna → powiadomienie
 * z changelogiem. Kanały: Stable (domyślny) / Beta.
 *
 * httpClient passed from outside — shared with ModrinthClient, JavaManager.
 * Prevents resource leak (no internal HttpClient to forget to close).
 */
class AutoUpdater(
    private val httpClient: HttpClient,
    private val currentVersion: String,
    private val channel: String = "STABLE",
    private val repoOwner: String = "Echelon-Team",
    private val repoName: String = "SingularityMC"
) {
    private val logger = LoggerFactory.getLogger(AutoUpdater::class.java)

    @Serializable
    data class GithubRelease(
        val tag_name: String,
        val name: String,
        val body: String,
        val prerelease: Boolean,
        val html_url: String,
        val assets: List<ReleaseAsset> = emptyList()
    )

    @Serializable
    data class ReleaseAsset(
        val name: String,
        val browser_download_url: String,
        val size: Long
    )

    data class UpdateAvailable(
        val version: String,
        val changelog: String,
        val downloadUrl: String?,
        val releaseUrl: String
    )

    suspend fun checkForUpdates(): UpdateAvailable? {
        return try {
            val releases: List<GithubRelease> = httpClient.get(
                "https://api.github.com/repos/$repoOwner/$repoName/releases"
            ) {
                header("User-Agent", "SingularityMC/$currentVersion")
                header("Accept", "application/vnd.github+json")
            }.body()

            val filteredReleases = releases.filter { release ->
                when (channel) {
                    "BETA" -> true
                    else -> !release.prerelease
                }
            }

            val latest = filteredReleases.firstOrNull() ?: return null

            val latestVersion = latest.tag_name.removePrefix("v")
            if (compareVersions(latestVersion, currentVersion) > 0) {
                val os = detectOs()
                val asset = latest.assets.firstOrNull { it.name.lowercase().contains(os) }

                UpdateAvailable(
                    version = latest.tag_name,
                    changelog = latest.body,
                    downloadUrl = asset?.browser_download_url,
                    releaseUrl = latest.html_url
                )
            } else null

        } catch (e: Exception) {
            logger.warn("Failed to check for updates: {}", e.message)
            null
        }
    }

    internal fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").mapNotNull { it.split("-").first().toIntOrNull() }
        val bParts = b.split(".").mapNotNull { it.split("-").first().toIntOrNull() }

        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val aVal = aParts.getOrNull(i) ?: 0
            val bVal = bParts.getOrNull(i) ?: 0
            if (aVal > bVal) return 1
            if (aVal < bVal) return -1
        }
        return 0
    }

    private fun detectOs(): String {
        val name = System.getProperty("os.name").lowercase()
        return when {
            name.contains("win") -> "windows"
            name.contains("mac") -> "mac"
            else -> "linux"
        }
    }
}
