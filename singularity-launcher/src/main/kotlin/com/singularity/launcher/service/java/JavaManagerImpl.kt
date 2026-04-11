package com.singularity.launcher.service.java

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Implementation JavaManager z Adoptium API v3.
 *
 * **Storage:** `<javaRoot>/java-<version>/bin/java` — np. `~/.singularitymc/java/java-17/bin/java`.
 *
 * **getJavaFor exact match (NO fallback higher):**
 * MC 1.8.9-1.16.5 → Java 8 | MC 1.17-1.20.4 → Java 17 | MC 1.20.5+ → Java 21.
 * Fallback wyższy jest niebezpieczny — MC 1.16.5 na Java 17 crashuje bo nashorn removed.
 *
 * **SHA256 verification CRITICAL** — `.zip`/`.tar.gz` musi match checksum z Adoptium JSON.
 * Mismatch → SecurityException. Zapobiega MITM + cache poisoning.
 */
class JavaManagerImpl(
    private val javaRoot: Path,
    private val httpClient: HttpClient
) : JavaManager {

    companion object {
        const val ADOPTIUM_API = "https://api.adoptium.net/v3"

        /**
         * Exact MC → Java mapping. NO fallback to higher version.
         */
        private val MC_TO_JAVA = mapOf(
            8 to listOf("1.8", "1.9", "1.10", "1.11", "1.12", "1.13", "1.14", "1.15", "1.16"),
            17 to listOf("1.17", "1.18", "1.19", "1.20.1", "1.20.2", "1.20.3", "1.20.4"),
            21 to listOf("1.20.5", "1.20.6", "1.21")
        )

        /**
         * Creates default HttpClient with JSON content negotiation + redirects follow.
         * Used przez Task 32 App.kt DI.
         */
        fun createDefaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 600_000  // 10 min dla download
                connectTimeoutMillis = 30_000
            }
        }
    }

    init {
        Files.createDirectories(javaRoot)
    }

    @Serializable
    private data class AdoptiumRelease(
        val binaries: List<AdoptiumBinary> = emptyList()
    )

    @Serializable
    private data class AdoptiumBinary(
        // UWAGA: Adoptium API schema ma polaczony "package" (nie package_info) — pole JSON to "package"
        @SerialName("package")
        val packageInfo: AdoptiumPackage? = null
    )

    @Serializable
    private data class AdoptiumPackage(
        val link: String,
        val checksum: String,
        val size: Long,
        val name: String
    )

    override fun getJavaFor(mcVersion: String): Int {
        // Check exact match z mapy
        for ((javaVer, mcList) in MC_TO_JAVA) {
            if (mcList.any { prefix -> mcVersion.startsWith(prefix) }) {
                return javaVer
            }
        }
        throw IllegalArgumentException("Unknown MC version: $mcVersion")
    }

    override fun isInstalled(javaMajorVersion: Int): Boolean {
        val javaExe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return Files.exists(javaRoot.resolve("java-$javaMajorVersion").resolve("bin").resolve(javaExe))
    }

    override fun listInstalledVersions(): List<Int> {
        if (!Files.exists(javaRoot)) return emptyList()
        val result = mutableListOf<Int>()
        Files.list(javaRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                val name = dir.fileName.toString()
                if (name.startsWith("java-")) {
                    name.removePrefix("java-").toIntOrNull()?.let { ver ->
                        if (isInstalled(ver)) result.add(ver)
                    }
                }
            }
        }
        return result
    }

    override suspend fun ensureJava(mcVersion: String, onProgress: (Int) -> Unit): Path {
        val javaMajor = getJavaFor(mcVersion)
        if (isInstalled(javaMajor)) {
            return javaExecutablePath(javaMajor)
        }

        onProgress(0)
        val pkg = fetchAdoptiumPackage(javaMajor)
        onProgress(10)

        val tmpFile = javaRoot.resolve("java-$javaMajor.tmp")
        downloadFile(pkg.link, tmpFile) { bytesRead, totalBytes ->
            val percent = 10 + ((bytesRead.toDouble() / totalBytes.toDouble()) * 60).toInt()
            onProgress(percent.coerceIn(10, 70))
        }
        onProgress(70)

        // Verify SHA256 checksum (CRITICAL)
        val actualHash = computeSha256(tmpFile)
        if (!actualHash.equals(pkg.checksum, ignoreCase = true)) {
            Files.deleteIfExists(tmpFile)
            throw SecurityException(
                "SHA256 mismatch for Java $javaMajor download. " +
                "Expected: ${pkg.checksum}, actual: $actualHash"
            )
        }
        onProgress(80)

        // Extract archive
        val targetDir = javaRoot.resolve("java-$javaMajor")
        Files.createDirectories(targetDir)
        when {
            pkg.name.endsWith(".zip") -> extractZip(tmpFile, targetDir)
            pkg.name.endsWith(".tar.gz") -> extractTarGz(tmpFile, targetDir)
            else -> throw RuntimeException("Unknown archive format: ${pkg.name}")
        }
        onProgress(95)

        // Flatten directory structure (Adoptium zawiera jdk-17.0.x+y/ wrapper folder)
        flattenSingleWrapper(targetDir)

        Files.deleteIfExists(tmpFile)
        onProgress(100)

        return javaExecutablePath(javaMajor)
    }

    private fun javaExecutablePath(javaMajor: Int): Path {
        val javaExe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return javaRoot.resolve("java-$javaMajor").resolve("bin").resolve(javaExe)
    }

    private suspend fun fetchAdoptiumPackage(javaMajor: Int): AdoptiumPackage {
        val os = detectOs()
        val arch = detectArch()
        val url = "$ADOPTIUM_API/assets/latest/$javaMajor/hotspot?architecture=$arch&image_type=jre&os=$os&vendor=eclipse"
        val response = httpClient.get(url)
        if (response.status != HttpStatusCode.OK) {
            throw RuntimeException("Adoptium API error: ${response.status}")
        }
        val releases = response.body<List<AdoptiumRelease>>()
        return releases.firstOrNull()?.binaries?.firstOrNull()?.packageInfo
            ?: throw RuntimeException("No Adoptium binary found for $os/$arch/JRE$javaMajor (pusta response albo brak 'binaries' w JSON)")
    }

    private fun detectOs(): String {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("win") -> "windows"
            osName.contains("mac") -> "mac"
            else -> "linux"
        }
    }

    private fun detectArch(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            arch.contains("amd64") || arch.contains("x86_64") -> "x64"
            arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
            else -> "x64"
        }
    }

    private suspend fun downloadFile(url: String, target: Path, onProgress: (Long, Long) -> Unit) {
        val response = httpClient.get(url)
        val total = response.contentLength() ?: -1L
        val inputStream = response.bodyAsChannel().toInputStream()
        var downloaded = 0L

        Files.newOutputStream(target).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = inputStream.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
                downloaded += n
                if (total > 0) onProgress(downloaded, total)
            }
        }
    }

    private fun computeSha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractZip(zip: Path, target: Path) {
        Files.newInputStream(zip).use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryPath = target.resolve(entry.name)
                    if (entry.isDirectory) {
                        Files.createDirectories(entryPath)
                    } else {
                        Files.createDirectories(entryPath.parent)
                        Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING)
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun extractTarGz(tarGz: Path, target: Path) {
        Files.newInputStream(tarGz).use { input ->
            GzipCompressorInputStream(input).use { gzIn ->
                TarArchiveInputStream(gzIn).use { tarIn ->
                    var entry = tarIn.nextEntry
                    while (entry != null) {
                        val entryPath = target.resolve(entry.name)
                        if (entry.isDirectory) {
                            Files.createDirectories(entryPath)
                        } else {
                            Files.createDirectories(entryPath.parent)
                            Files.copy(tarIn, entryPath, StandardCopyOption.REPLACE_EXISTING)
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
        }
    }

    /**
     * Adoptium archives zawierają wrapper folder `jdk-17.0.x+y/` — spłaszczamy do target root.
     */
    private fun flattenSingleWrapper(target: Path) {
        val children = Files.list(target).use { it.toList() }
        if (children.size == 1 && Files.isDirectory(children[0])) {
            val wrapper = children[0]
            Files.list(wrapper).use { stream ->
                stream.forEach { child ->
                    Files.move(child, target.resolve(wrapper.relativize(child).toString()))
                }
            }
            Files.delete(wrapper)
        }
    }
}
