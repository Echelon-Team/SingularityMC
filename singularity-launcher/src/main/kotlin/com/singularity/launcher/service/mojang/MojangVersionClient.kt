package com.singularity.launcher.service.mojang

import com.singularity.launcher.config.OfflineMode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode

/**
 * Client dla Mojang piston-meta API — version manifest + per-version details.
 *
 * **Endpoints:**
 * - `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` — lista wszystkich versions
 * - Per-version details URL z `ManifestVersion.url`
 *
 * **Zero auth**, public API, no rate limit (within reason).
 */
class MojangVersionClient(private val httpClient: HttpClient) {

    companion object {
        const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }

    /**
     * Fetch full version manifest (release + snapshot + old).
     *
     * Offline mode (spec §4.11): zwraca pusty manifest zamiast uderzać
     * piston-meta.mojang.com. UI Create-Instance wykryje pustą listę +
     * pokaże banner "Tryb offline: lista wersji Minecraft niedostępna
     * — nie można utworzyć nowej instancji". Istniejące instancje
     * działają normalnie bo już mają cached JARy w `versions/`.
     */
    suspend fun fetchManifest(): Result<VersionManifest> {
        if (OfflineMode.isEnabled()) {
            return Result.success(
                VersionManifest(
                    latest = LatestVersions(release = "", snapshot = ""),
                    versions = emptyList(),
                ),
            )
        }
        return try {
            val response = httpClient.get(MANIFEST_URL)
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<VersionManifest>())
            } else {
                Result.failure(RuntimeException("Mojang manifest HTTP ${response.status.value}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch per-version details z URL podanego w `ManifestVersion.url`.
     */
    suspend fun fetchVersionDetails(manifestVersion: ManifestVersion): Result<VersionDetails> = try {
        val response = httpClient.get(manifestVersion.url)
        if (response.status == HttpStatusCode.OK) {
            Result.success(response.body<VersionDetails>())
        } else {
            Result.failure(RuntimeException("Mojang version details HTTP ${response.status.value}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Convenience: fetch manifest + find version by ID + fetch details.
     */
    suspend fun fetchVersionDetails(versionId: String): Result<VersionDetails> {
        val manifestResult = fetchManifest()
        val manifest = manifestResult.getOrElse { return Result.failure(it) }
        val manifestVersion = manifest.versions.firstOrNull { it.id == versionId }
            ?: return Result.failure(RuntimeException("Version $versionId not found in Mojang manifest"))
        return fetchVersionDetails(manifestVersion)
    }

    /**
     * Convenience: fetch release versions only (filter z manifest).
     */
    suspend fun fetchReleaseVersions(): Result<List<ManifestVersion>> {
        val manifestResult = fetchManifest()
        val manifest = manifestResult.getOrElse { return Result.failure(it) }
        return Result.success(manifest.versions.filter { it.type == "release" })
    }
}
