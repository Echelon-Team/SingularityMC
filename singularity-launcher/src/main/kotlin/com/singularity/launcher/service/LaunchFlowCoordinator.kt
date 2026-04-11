package com.singularity.launcher.service

import com.singularity.common.model.InstanceConfig
import com.singularity.common.model.InstanceType
import com.singularity.launcher.service.auth.AuthManager
import com.singularity.launcher.service.java.JavaManager
import com.singularity.launcher.service.mojang.MojangVersionClient
import com.singularity.launcher.service.runner.LaunchContext
import com.singularity.launcher.service.runner.McRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Coordinates REAL launch flow: auth → java → Mojang version download → libraries → classpath
 * → agent jar resolution → runner.
 *
 * **Zero placeholders (Fix p1 Mateusz 2026-04-11):** klasa faktycznie assembleje classpath z
 * Mojang piston-meta library list + resolves agent jar z launcher resources.
 *
 * **Emits progress updates** jako Flow<LaunchProgress> — UI (InstancePanel Task 12) subscribes.
 */
class LaunchFlowCoordinator(
    private val authManager: AuthManager,
    private val javaManager: JavaManager,
    private val mojangClient: MojangVersionClient,
    private val libraryDownloader: LibraryDownloader,
    private val sharedAssetsDir: Path,
    private val sharedLibrariesDir: Path,
    private val sharedVersionsDir: Path,
    private val agentJarResolver: AgentJarResolver
) {

    sealed class LaunchProgress {
        data class Progress(val percent: Int, val message: String) : LaunchProgress()
        data class Success(val process: Process) : LaunchProgress()
        data class Error(val message: String) : LaunchProgress()
    }

    fun launch(
        instance: InstanceManager.Instance,
        config: InstanceConfig
    ): Flow<LaunchProgress> = flow {
        try {
            // 1. Auth
            emit(LaunchProgress.Progress(0, "Pobieranie konta..."))
            val account = authManager.getActiveAccount()
                ?: throw IllegalStateException("Brak aktywnego konta — dodaj konto w ustawieniach")

            // 2. Java
            emit(LaunchProgress.Progress(5, "Sprawdzanie Java..."))
            val javaPath = javaManager.ensureJava(config.minecraftVersion)
            emit(LaunchProgress.Progress(20, "Java gotowa"))

            // 3. Mojang version manifest + details
            emit(LaunchProgress.Progress(25, "Pobieranie manifest Mojang..."))
            val manifestResult = mojangClient.fetchManifest()
            val manifest = manifestResult.getOrElse {
                throw RuntimeException("Nie można pobrać Mojang version manifest: ${it.message}")
            }
            val mv = manifest.versions.firstOrNull { it.id == config.minecraftVersion }
                ?: throw RuntimeException("Wersja ${config.minecraftVersion} nie istnieje w Mojang manifest")

            emit(LaunchProgress.Progress(30, "Pobieranie szczegółów ${config.minecraftVersion}..."))
            val detailsResult = mojangClient.fetchVersionDetails(mv)
            val details = detailsResult.getOrElse {
                throw RuntimeException("Nie można pobrać version details: ${it.message}")
            }

            // 4. Library + client JAR download + classpath assembly
            emit(LaunchProgress.Progress(35, "Pobieranie bibliotek..."))
            val cpResult = libraryDownloader.downloadAll(details) { /* progress callback non-suspend */ }
            val classpath = cpResult.getOrElse {
                throw RuntimeException("Błąd pobierania bibliotek: ${it.message}")
            }

            emit(LaunchProgress.Progress(75, "Biblioteki gotowe (${classpath.classpath.size} JARs)"))

            // 5. Asset index resolution — używamy tylko ID, MC sam ściąga assets z URL indexu
            val assetIndex = details.assetIndex.id

            // 6. Agent jar resolution (Enhanced only)
            val agentJarPath = if (config.type == InstanceType.ENHANCED) {
                emit(LaunchProgress.Progress(80, "Rozpakowywanie singularity-agent.jar..."))
                agentJarResolver.resolveAgentJar()
            } else {
                null
            }

            // 7. Launch
            emit(LaunchProgress.Progress(90, "Uruchamianie Minecraft..."))
            val context = LaunchContext(
                config = config,
                instanceDir = instance.rootDir,
                account = account,
                javaPath = javaPath,
                gameDir = instance.rootDir.resolve("minecraft"),
                assetsDir = sharedAssetsDir,
                assetIndex = assetIndex,
                agentJarPath = agentJarPath,
                classpath = classpath.classpath
            )

            val process = McRunner.launch(context, ramMb = config.ramMb)
            emit(LaunchProgress.Progress(100, "Uruchomione"))
            emit(LaunchProgress.Success(process))
        } catch (e: Exception) {
            emit(LaunchProgress.Error(e.message ?: "Nieznany błąd uruchamiania"))
        }
    }
}

/**
 * Resolves path do singularity-agent.jar. Sub 4: jar jest spakowany w launcher resources
 * (`resources/agent/singularity-agent.jar`) — copy to `<sharedJarsDir>/singularity-agent.jar`
 * przy pierwszym launchu Enhanced instance.
 *
 * Build dependency: `singularity-launcher/build.gradle.kts` ma task `copyAgentJar` który
 * kopiuje wynik build `singularity-agent/build/libs/singularity-agent.jar` do
 * `singularity-launcher/src/main/resources/agent/singularity-agent.jar` przy build.
 */
class AgentJarResolver(private val cacheDir: Path) {

    fun resolveAgentJar(): Path {
        Files.createDirectories(cacheDir)
        val target = cacheDir.resolve("singularity-agent.jar")
        if (!Files.exists(target)) {
            // Extract z launcher resources
            val stream = javaClass.getResourceAsStream("/agent/singularity-agent.jar")
                ?: throw IllegalStateException(
                    "singularity-agent.jar nie znaleziony w launcher resources — " +
                    "sprawdź czy build.gradle.kts task copyAgentJar został wywołany"
                )
            stream.use { input ->
                val tmp = target.resolveSibling("${target.fileName}.tmp")
                Files.newOutputStream(tmp).use { output ->
                    input.copyTo(output)
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        return target
    }
}
