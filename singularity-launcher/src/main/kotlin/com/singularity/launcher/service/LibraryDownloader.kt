package com.singularity.launcher.service

import com.singularity.launcher.service.mojang.Library
import com.singularity.launcher.service.mojang.VersionDetails
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Downloader dla MC libraries + client JAR. Zapisuje do `<sharedLibrariesDir>/<path>` gdzie
 * path to `com/mojang/brigadier/1.1.8/brigadier-1.1.8.jar`.
 *
 * **Idempotent**: jeśli plik już istnieje z matching SHA1, pomija download.
 *
 * **OS filter**: libraries z `rules` są filtrowane per current OS (linux/windows/osx).
 */
class LibraryDownloader(
    private val httpClient: HttpClient,
    private val librariesDir: Path,
    private val versionsDir: Path
) {

    sealed class DownloadProgress {
        data class Item(val current: Int, val total: Int, val name: String) : DownloadProgress()
        data class Error(val message: String) : DownloadProgress()
        object Done : DownloadProgress()
    }

    data class ClasspathResult(
        val classpath: List<Path>,
        val clientJar: Path
    )

    /**
     * Download wszystkie libraries + client.jar dla version details.
     * Returns list of Paths do library JARs (dla classpath assembly).
     */
    suspend fun downloadAll(
        details: VersionDetails,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ClasspathResult> = try {
        Files.createDirectories(librariesDir)
        Files.createDirectories(versionsDir)

        val applicableLibs = details.libraries.filter { isApplicable(it) }
        val totalItems = applicableLibs.size + 1  // +1 dla client.jar
        var current = 0

        val classpath = mutableListOf<Path>()

        for (lib in applicableLibs) {
            current++
            onProgress(DownloadProgress.Item(current, totalItems, lib.name))
            val artifact = lib.downloads.artifact ?: continue
            val target = librariesDir.resolve(artifact.path)
            downloadFileIfNeeded(artifact.url, target, artifact.sha1)
            classpath.add(target)
        }

        // Client JAR
        current++
        onProgress(DownloadProgress.Item(current, totalItems, "minecraft-${details.id}.jar"))
        val clientJar = versionsDir.resolve(details.id).resolve("${details.id}.jar")
        Files.createDirectories(clientJar.parent)
        downloadFileIfNeeded(details.downloads.client.url, clientJar, details.downloads.client.sha1)
        classpath.add(clientJar)

        onProgress(DownloadProgress.Done)
        Result.success(ClasspathResult(classpath = classpath, clientJar = clientJar))
    } catch (e: Exception) {
        onProgress(DownloadProgress.Error(e.message ?: "Unknown error"))
        Result.failure(e)
    }

    /**
     * Check if library applies to current OS based on rules.
     * "allow" + matching OS → true, "disallow" + matching OS → false.
     * Brak rules → zawsze applicable.
     */
    internal fun isApplicable(lib: Library): Boolean {
        val rules = lib.rules ?: return true
        val currentOs = detectOs()

        // Default: disallowed if any rule exists, allowed if matching allow rule found
        var applicable = false
        for (rule in rules) {
            val osMatches = rule.os?.name == null || rule.os.name == currentOs
            if (osMatches) {
                applicable = when (rule.action) {
                    "allow" -> true
                    "disallow" -> false
                    else -> applicable
                }
            } else if (rule.os?.name != null && rule.action == "allow") {
                // Rule says allow on specific OS, current OS doesn't match → disallow unless earlier rule allows
                applicable = false
            }
        }
        return applicable
    }

    private fun detectOs(): String {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("win") -> "windows"
            osName.contains("mac") -> "osx"
            else -> "linux"
        }
    }

    /**
     * Download file if missing or SHA1 mismatch. Atomic write (tmp + move).
     */
    private suspend fun downloadFileIfNeeded(url: String, target: Path, expectedSha1: String) {
        if (Files.exists(target)) {
            val actualSha1 = computeSha1(target)
            if (actualSha1.equals(expectedSha1, ignoreCase = true)) return
        }
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.tmp")

        val response = httpClient.get(url)
        if (response.status != HttpStatusCode.OK) {
            throw RuntimeException("Download failed ${response.status.value}: $url")
        }
        val inputStream = response.bodyAsChannel().toInputStream()
        Files.newOutputStream(tmp).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = inputStream.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
        }

        // Verify SHA1
        val actualSha1 = computeSha1(tmp)
        if (!actualSha1.equals(expectedSha1, ignoreCase = true)) {
            Files.deleteIfExists(tmp)
            throw SecurityException("SHA1 mismatch for $url. Expected $expectedSha1, actual $actualSha1")
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun computeSha1(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
