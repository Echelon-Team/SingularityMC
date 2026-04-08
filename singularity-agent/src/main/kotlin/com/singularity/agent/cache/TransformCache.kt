package com.singularity.agent.cache

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Disk cache dla zremapowanych (i transformowanych) klas.
 *
 * Struktura:
 * ```
 * <cacheRoot>/
 *   <jarHash>/
 *     net/minecraft/world/level/Level.class
 *     net/minecraft/world/entity/Entity.class
 * ```
 *
 * Zakres: per-instancja (usuniecie instancji = usuniecie cache'a).
 * Klucz katalogu: hash JAR zrodlowego. Update moda = inny hash = nowy katalog.
 * Czyszczenie: przy starcie gry porownaj istniejace hashe z aktualnymi JARami.
 *
 * UWAGA (resource safety):
 * - `Files.walk()` i `Files.newDirectoryStream()` wymagaja `.use{}` bo inaczej
 *   Stream/DirectoryStream jest leaked (open file handle).
 * - Na Windows open handle blokuje delete katalogu → cleanup failuje.
 * - `deleteRecursively()` na Windows zwraca false przy partial failure (open handle),
 *   nie rzuca exception → logujemy return value zeby widziec silent failures.
 * (edge-case-hunter flag #1)
 *
 * Referencja: design spec sekcja 15 (Disk cache remapowanych klas).
 */
class TransformCache(private val cacheRoot: Path) {

    private val logger = LoggerFactory.getLogger(TransformCache::class.java)

    init {
        Files.createDirectories(cacheRoot)
    }

    /**
     * Liczba cache'owanych klas (rekurencyjnie). Zwraca 0 gdy cacheRoot nie istnieje.
     *
     * Uzywa Files.walk() — wymaga `.use{}` dla zamkniecia Stream.
     */
    val size: Int get() {
        if (!cacheRoot.exists()) return 0
        var count = 0
        Files.walk(cacheRoot).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".class") }.forEach { count++ }
        }
        return count
    }

    /**
     * Zapisuje zremapowany bytecode do cache.
     */
    fun put(jarHash: String, classInternalName: String, bytes: ByteArray) {
        val classFile = cacheRoot.resolve(jarHash).resolve("$classInternalName.class")
        Files.createDirectories(classFile.parent)
        classFile.writeBytes(bytes)
    }

    /**
     * Pobiera zremapowany bytecode z cache.
     * @return bytecode lub null jesli nie w cache
     */
    fun get(jarHash: String, classInternalName: String): ByteArray? {
        val classFile = cacheRoot.resolve(jarHash).resolve("$classInternalName.class")
        return if (classFile.exists()) classFile.readBytes() else null
    }

    /**
     * Usuwa WSZYSTKIE wpisy dla danego hash JAR.
     * Loguje warning jesli partial delete (Windows file lock).
     */
    fun invalidate(jarHash: String) {
        val dir = cacheRoot.resolve(jarHash)
        if (dir.exists()) {
            val success = dir.toFile().deleteRecursively()
            if (success) {
                logger.info("Cache invalidated for jar hash: {}", jarHash)
            } else {
                logger.warn("Failed to fully invalidate cache for jar hash: {} (partial delete — likely Windows file lock)", jarHash)
            }
        }
    }

    /**
     * Czysci stale wpisy: usuwa katalogi ktorych hash nie jest w zbiorze aktywnych JARow.
     * Wywolywane przy starcie gry.
     *
     * Uzywa Files.newDirectoryStream() (nie Files.list()) dla bezpiecznego Iterable API
     * i gwarancji zamkniecia handle przez .use{}.
     *
     * @param activeJarHashes zbior hashow JARow aktualnie zainstalowanych w instancji
     */
    fun cleanup(activeJarHashes: Set<String>) {
        if (!cacheRoot.exists()) return

        Files.newDirectoryStream(cacheRoot).use { dirStream ->
            dirStream.toList()
                .filter { it.isDirectory() }
                .filter { it.name !in activeJarHashes }
                .forEach { staleDir ->
                    val success = staleDir.toFile().deleteRecursively()
                    if (success) {
                        logger.info("Cleaned stale cache directory: {}", staleDir.name)
                    } else {
                        logger.warn("Failed to clean stale cache dir: {} (partial delete)", staleDir.name)
                    }
                }
        }
    }
}
