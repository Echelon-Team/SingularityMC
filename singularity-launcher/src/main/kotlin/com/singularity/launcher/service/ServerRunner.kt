package com.singularity.launcher.service

import com.singularity.launcher.service.java.JavaManager
import com.singularity.launcher.service.mojang.MojangVersionClient
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Uruchamia vanilla Minecraft server z Mojang piston-meta server.jar download + ProcessBuilder.
 *
 * **Scope (Sub 4):** vanilla server only. Enhanced server (agent attach) → Sub 5.
 *
 * **Flow:**
 * 1. Fetch Mojang version details dla `config.minecraftVersion`
 * 2. Download `downloads.server.url` do `<server>/server.jar` (idempotent, SHA1 verify)
 * 3. Generate server.properties z `ServerConfig` (port, motd, max-players)
 * 4. Accept EULA — write `eula=true\n` do `<server>/eula.txt`
 * 5. ProcessBuilder: `java -Xmx{ramMb}m -jar server.jar nogui`
 * 6. Return handle z Process + stdout channel dla log streaming
 */
class ServerRunner(
    private val mojangClient: MojangVersionClient,
    private val httpClient: HttpClient,
    private val javaManager: JavaManager
) {

    suspend fun start(server: ServerManager.Server): ServerRunnerHandle {
        val serverDir = server.rootDir
        Files.createDirectories(serverDir)

        // 1. Ensure server.jar downloaded
        val serverJar = serverDir.resolve("server.jar")
        if (!Files.exists(serverJar)) {
            val details = mojangClient.fetchVersionDetails(server.config.minecraftVersion)
                .getOrElse {
                    throw RuntimeException("Nie mozna pobrac Mojang version details dla ${server.config.minecraftVersion}: ${it.message}")
                }
            val serverDownload = details.downloads.server
                ?: throw RuntimeException("Version ${server.config.minecraftVersion} nie ma server download w Mojang manifest")

            downloadServerJar(serverDownload.url, serverJar, serverDownload.sha1)
        }

        // 2. Generate server.properties
        val propsFile = serverDir.resolve("server.properties")
        if (!Files.exists(propsFile)) {
            val props = buildString {
                appendLine("server-port=${server.config.port}")
                appendLine("motd=${server.config.motd}")
                appendLine("max-players=${server.config.maxPlayers}")
                appendLine("online-mode=false")  // offline accounts wymagają online-mode=false
                appendLine("enable-command-block=false")
                appendLine("spawn-protection=16")
                appendLine("gamemode=survival")
                appendLine("difficulty=normal")
                appendLine("pvp=true")
                appendLine("allow-flight=false")
                appendLine("view-distance=10")
            }
            Files.writeString(propsFile, props)
        }

        // 3. Accept EULA (user akceptuje implicit przez kliknięcie "Uruchom" w GUI)
        val eulaFile = serverDir.resolve("eula.txt")
        Files.writeString(eulaFile, "eula=true\n")

        // 4. Ensure Java for server MC version
        val javaPath = javaManager.ensureJava(server.config.minecraftVersion)

        // 5. ProcessBuilder
        val command = listOf(
            javaPath.toString(),
            "-Xmx${server.config.ramMb}m",
            "-Xms512m",
            "-jar",
            "server.jar",
            "nogui"
        )
        val pb = ProcessBuilder(command)
            .directory(serverDir.toFile())
            .redirectErrorStream(true)  // stderr → stdout
        val process = pb.start()

        // 6. Stdout capturing → Channel
        val logChannel = Channel<String>(Channel.UNLIMITED)
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val readerJob = ioScope.launch {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: break
                        logChannel.trySendBlocking(l).getOrNull()
                    }
                }
            } finally {
                logChannel.close()
            }
        }

        return ServerRunnerHandle(
            process = process,
            logFlow = logChannel.receiveAsFlow(),
            readerJob = readerJob
        )
    }

    /**
     * Download server.jar z SHA1 verification. Atomic write (tmp + move).
     */
    private suspend fun downloadServerJar(url: String, target: Path, expectedSha1: String) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        httpClient.prepareGet(url).execute { response ->
            if (response.status != HttpStatusCode.OK) {
                throw RuntimeException("Server.jar download failed HTTP ${response.status.value}: $url")
            }
            FileOutputStream(tmp.toFile()).use { out ->
                response.bodyAsChannel().toInputStream().use { input ->
                    input.copyTo(out)
                }
            }
        }
        // Verify SHA1
        val actualSha1 = computeSha1(tmp)
        if (!actualSha1.equals(expectedSha1, ignoreCase = true)) {
            Files.deleteIfExists(tmp)
            throw SecurityException("server.jar SHA1 mismatch. Expected $expectedSha1, actual $actualSha1")
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

/**
 * Handle do uruchomionego server process — zwrócone z ServerRunner.start().
 * Trzymane w ServerManagerImpl.activeProcesses Map<String, ServerRunnerHandle>.
 */
data class ServerRunnerHandle(
    val process: Process,
    val logFlow: Flow<String>,
    val readerJob: Job
)
