package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Skanuje folder mods/ instancji i identyfikuje loadery modów.
 *
 * Dla każdego .jar sprawdza obecność plików metadanych:
 * - fabric.mod.json → Fabric
 * - META-INF/mods.toml → Forge/NeoForge
 * - Oba → Multi-loader
 * - Żaden → Library (jeśli znana grupa Maven) lub Unknown
 *
 * Referencja: design spec sekcja 5A.1.
 */
object ModDiscovery {

    private val logger = LoggerFactory.getLogger(ModDiscovery::class.java)

    /**
     * Znane prefiksy grup Maven — JARy z tymi grupami to biblioteki, nie mody.
     * Lista zawiera tylko najczęstsze dependency'sy; nieznane grupy → UNKNOWN
     * (konserwatywnie — lepiej nie sklasyfikować niż sklasyfikować mod jako bibliotekę).
     */
    private val KNOWN_LIBRARY_GROUPS = setOf(
        "com.google", "org.apache", "io.netty", "it.unimi.dsi",
        "com.mojang", "org.lwjgl", "org.slf4j", "ch.qos.logback",
        "org.ow2.asm", "com.electronwill", "org.jetbrains",
        "commons-", "javax.", "jakarta."
    )

    /**
     * Skanuje katalog i zwraca listę odkrytych modów/bibliotek.
     *
     * @param modsDir ścieżka do folderu mods/ instancji
     * @return lista DiscoveredMod — po jednym na każdy .jar
     */
    fun scanDirectory(modsDir: Path): List<DiscoveredMod> {
        if (!modsDir.exists() || !modsDir.isDirectory()) {
            logger.debug("Mods directory does not exist or is not a directory: {}", modsDir)
            return emptyList()
        }

        // Try/catch wokół Files.list — edge case: katalog może zniknąć między exists() a list()
        // (user usunął w trakcie launcha). Bez catch crash na NoSuchFileException propaguje
        // do AgentMain.bootstrap() → JVM exit.
        val jars = try {
            Files.list(modsDir).use { stream ->
                stream.toList().filter { path ->
                    // Non-recursive: tylko bezpośrednie dzieci które są plikami (nie katalogi)
                    !path.isDirectory() && path.name.endsWith(".jar", ignoreCase = true)
                }
            }
        } catch (e: java.nio.file.NoSuchFileException) {
            logger.warn("Mods directory vanished during scan: {}", modsDir)
            return emptyList()
        } catch (e: java.io.IOException) {
            logger.error("Failed to list mods directory {}: {}", modsDir, e.message)
            return emptyList()
        }

        logger.info("Scanning mods directory: {} ({} JAR files)", modsDir, jars.size)

        return jars.mapNotNull { jarPath ->
            try {
                scanJar(jarPath)
            } catch (e: Exception) {
                logger.error("Failed to scan JAR: {} — {}", jarPath.name, e.message)
                null
            }
        }
    }

    private fun scanJar(jarPath: Path): DiscoveredMod {
        JarFile(jarPath.toFile()).use { jar ->
            val fabricEntry = jar.getJarEntry("fabric.mod.json")
            val forgeEntry = jar.getJarEntry("META-INF/mods.toml")

            val rawFabricJson = fabricEntry?.let {
                jar.getInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }
            val rawModsToml = forgeEntry?.let {
                jar.getInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }

            val hasFabric = rawFabricJson != null
            val hasForge = rawModsToml != null

            val loaderType = when {
                hasFabric && hasForge -> LoaderType.MULTI
                hasFabric -> LoaderType.FABRIC
                hasForge -> LoaderType.FORGE
                else -> identifyNonMod(jar)
            }

            val mavenGroupId = extractMavenGroup(jar)

            logger.debug("Discovered: {} → {}", jarPath.name, loaderType)

            return DiscoveredMod(
                jarPath = jarPath,
                loaderType = loaderType,
                rawFabricJson = rawFabricJson,
                rawModsToml = rawModsToml,
                isLibrary = loaderType == LoaderType.LIBRARY,
                mavenGroupId = mavenGroupId
            )
        }
    }

    /**
     * Identyfikuje JAR bez metadanych moda — biblioteka lub nieznany.
     */
    private fun identifyNonMod(jar: JarFile): LoaderType {
        val mavenGroup = extractMavenGroup(jar)
        if (mavenGroup != null && KNOWN_LIBRARY_GROUPS.any { mavenGroup.startsWith(it) }) {
            return LoaderType.LIBRARY
        }
        return LoaderType.UNKNOWN
    }

    /**
     * Wyciąga Maven group ID z MANIFEST.MF lub pom.properties.
     */
    private fun extractMavenGroup(jar: JarFile): String? {
        // Sprawdź MANIFEST.MF — różne konwencje przechowywania group ID
        val manifest = jar.manifest
        if (manifest != null) {
            val attrs = manifest.mainAttributes
            val group = attrs.getValue("Implementation-Vendor-Id")
                ?: attrs.getValue("Bundle-SymbolicName")
                ?: attrs.getValue("Automatic-Module-Name")
            if (group != null) return group
        }

        // Sprawdź pom.properties
        val pomEntry = jar.entries().toList().firstOrNull {
            it.name.matches(Regex("META-INF/maven/.+/.+/pom\\.properties"))
        }
        if (pomEntry != null) {
            val props = java.util.Properties()
            jar.getInputStream(pomEntry).use { props.load(it) }
            return props.getProperty("groupId")
        }

        return null
    }
}
