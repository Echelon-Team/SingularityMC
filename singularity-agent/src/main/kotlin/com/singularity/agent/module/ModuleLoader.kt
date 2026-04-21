// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.module

import com.singularity.common.contracts.ModuleDescriptorData
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Wyszukuje i laduje moduly compat z cache instancji.
 *
 * Konwencja nazewnictwa plikow: singularitymc-compat-<mcVersion>-v<moduleVersion>.jar
 * Katalog cache: <instanceDir>/.singularity/modules/
 *
 * Referencja: design spec sekcja 3 (lifecycle modulu).
 */
object ModuleLoader {

    private val logger = LoggerFactory.getLogger(ModuleLoader::class.java)

    /**
     * Regex parsujacy nazwe pliku modulu.
     * Grupy: 1=mcVersion, 2=moduleVersion
     */
    private val MODULE_FILENAME_PATTERN =
        Regex("""singularitymc-compat-(.+)-v(\d+\.\d+\.\d+)\.jar""")

    /**
     * Szuka modulu compat dla danej wersji MC w katalogu modules.
     * Jesli jest kilka modulow dla tej samej wersji MC — zwraca najnowszy (najwyzsza moduleVersion).
     *
     * Uwaga: version sorting jest SEMVER-aware (numeric), nie leksykograficzny.
     * "1.0.10" > "1.0.9" mimo ze lex sort by dal odwrotnie.
     *
     * @param modulesDir katalog z JARami modulow (np. <instanceDir>/.singularity/modules/)
     * @param minecraftVersion wersja MC do dopasowania (np. "1.20.1")
     * @return sciezka do JARa modulu lub null jesli nie znaleziono
     */
    fun findModule(modulesDir: Path, minecraftVersion: String): Path? {
        if (!modulesDir.exists() || !modulesDir.isDirectory()) {
            logger.debug("Modules directory does not exist: {}", modulesDir)
            return null
        }

        data class ModuleCandidate(val path: Path, val mcVersion: String, val moduleVersion: String)

        // Files.newDirectoryStream() zwraca DirectoryStream<Path> : Iterable<Path>.
        // Kotlin `.toList()` extension na Iterable dziala natywnie, bez lazy issues.
        // NIE uzywamy Files.list() bo Stream<Path> mial resource leak na Windows
        // (lazy terminal op po use{} block zamknieciu → IOException w ForEachOps).
        val candidates = Files.newDirectoryStream(modulesDir).use { dirStream ->
            dirStream.toList()
                .filter { it.name.endsWith(".jar") }
                .mapNotNull { path ->
                    MODULE_FILENAME_PATTERN.matchEntire(path.name)?.let { match ->
                        ModuleCandidate(path, match.groupValues[1], match.groupValues[2])
                    }
                }
                .filter { it.mcVersion == minecraftVersion }
        }

        if (candidates.isEmpty()) {
            logger.info("No compat module found for MC {} in {}", minecraftVersion, modulesDir)
            return null
        }

        // Sort semver-numeric, DESC (newest first). Kolejnosc zapewnia ze "1.0.10" > "1.0.9".
        val best = candidates.sortedWith(
            Comparator { a, b -> compareSemver(b.moduleVersion, a.moduleVersion) }
        ).first()
        logger.info("Found compat module: {} (v{}) for MC {}", best.path.name, best.moduleVersion, best.mcVersion)
        return best.path
    }

    /**
     * Porownuje dwie wersje semver NUMERYCZNIE (nie leksykograficznie).
     *
     * Zwraca liczbe dodatnia jesli a > b, ujemna jesli a < b, 0 dla rownych.
     * Przyklad: `compareSemver("1.0.10", "1.0.9")` = 1 (NIE -1 jak lex sort).
     * Obsluguje rowne oraz unequal number of parts przez `getOrElse(i) { 0 }`.
     *
     * Uwaga: nie wspiera pre-release suffixow (alpha/beta/rc). Plan Sub 2a wymaga
     * strict 3-part semver (regex w MODULE_FILENAME_PATTERN wymusza \d+\.\d+\.\d+).
     */
    private fun compareSemver(a: String, b: String): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val diff = aParts.getOrElse(i) { 0 } - bParts.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    /**
     * Laduje modul z JAR: parsuje deskryptor, przygotowuje dostep do zasobow.
     *
     * @param jarPath sciezka do JAR modulu
     * @return LoadedModule z dostepem do deskryptora i zasobow JAR
     */
    fun loadModule(jarPath: Path): LoadedModule {
        logger.info("Loading module from: {}", jarPath)
        val jarFile = JarFile(jarPath.toFile())
        val descriptor = ModuleDescriptor.parseFromJar(jarFile)
        return LoadedModule(descriptor, jarFile, jarPath)
    }
}

/**
 * Zaladowany modul compat z dostepem do deskryptora i zasobow JAR.
 * Implementuje AutoCloseable — zamyka JarFile przy close().
 *
 * UZYCIE: wrap w `use {}` aby uniknac resource leak.
 * W AgentMain.premain() musi byc explicit close — inaczej JarFile zostaje otwarty
 * przez caly runtime JVM (edge-case-hunter flag #3).
 */
class LoadedModule(
    val descriptor: ModuleDescriptorData,
    private val jarFile: JarFile,
    val path: Path
) : AutoCloseable {

    /**
     * Sprawdza czy JAR zawiera podany wpis.
     */
    fun hasEntry(entryPath: String): Boolean =
        jarFile.getJarEntry(entryPath) != null

    /**
     * Czyta zawartosc wpisu jako ByteArray.
     * @throws IllegalArgumentException jesli wpis nie istnieje
     *
     * Uzywa `.use{}` na InputStream zeby nie trzymac Inflater w JarFile pool
     * (edge-case-hunter final review #3 — pre-existing Sub 2a flaw).
     */
    fun readEntry(entryPath: String): ByteArray {
        val entry = jarFile.getJarEntry(entryPath)
            ?: throw IllegalArgumentException("Entry '$entryPath' not found in ${path.fileName}")
        return jarFile.getInputStream(entry).use { it.readBytes() }
    }

    /**
     * Zwraca liste wszystkich wpisow w JAR.
     */
    fun listEntries(): List<String> =
        jarFile.entries().toList().map { it.name }

    override fun close() {
        jarFile.close()
    }
}
