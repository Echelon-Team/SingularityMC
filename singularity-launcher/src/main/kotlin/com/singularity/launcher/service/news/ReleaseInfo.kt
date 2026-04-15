package com.singularity.launcher.service.news

import com.singularity.launcher.config.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Minimal subset of GitHub Release API response used by the launcher's Aktualności feed.
 *
 * Only fields actually consumed by UI are modeled; `ignoreUnknownKeys = true` in the HttpClient
 * config drops the rest (assets, author, zipball_url, etc.) silently.
 *
 * Upstream API docs: https://docs.github.com/en/rest/releases/releases
 *
 * Kotlin-side names are camelCase; `@SerialName` bridges to GitHub's snake_case wire format.
 * `body` field from API is renamed `changelog` in Kotlin for clarity — it IS markdown changelog.
 */
@Serializable
data class ReleaseInfo(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String,
    @SerialName("body") val changelog: String,
    @SerialName("prerelease") val isPrerelease: Boolean,
    @SerialName("published_at")
    @Serializable(with = InstantSerializer::class)
    val publishedAt: Instant,
    @SerialName("html_url") val htmlUrl: String,
) {
    /**
     * Display version without leading "v" prefix (e.g. "v1.2.3" → "1.2.3").
     * Only strips "v" when followed by a digit to avoid mangling tags like "vanilla-1.0".
     */
    val displayVersion: String
        get() = if (tagName.matches(Regex("^v\\d.*"))) tagName.removePrefix("v") else tagName

    /** Local date in system default timezone for UI "15 kwietnia 2026" formatting. */
    val publishedLocalDate: LocalDate
        get() = publishedAt.atZone(ZoneId.systemDefault()).toLocalDate()
}
