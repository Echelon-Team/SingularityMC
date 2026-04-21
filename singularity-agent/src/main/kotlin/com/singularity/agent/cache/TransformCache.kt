// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.cache

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes

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
     *
     * **First-writer-wins atomic write** (concurrent safety regression
     * SingularityTransformerTest 2026-04-21): pisze do sibling temp file,
     * potem `Files.move` z `ATOMIC_MOVE` (bez REPLACE_EXISTING). Gdy
     * inny wątek zdążył wpisać ten sam target — łapiemy
     * `FileAlreadyExistsException` i traktujemy jako SUCCESS (transform jest
     * deterministic: same input = same output = byte-equivalent cached copy).
     *
     * Dlaczego NIE `REPLACE_EXISTING` + `ATOMIC_MOVE` razem: Windows mapuje to
     * na `MoveFileEx(MOVEFILE_WRITE_THROUGH | MOVEFILE_REPLACE_EXISTING)`, ale
     * pod heavy concurrent replace (10 wątków dla tego samego target) Windows
     * driver reject'uje subsequent moves z `AccessDenied` / `FileSystemException`
     * zanim handle previous move jest fully released. Empirycznie zobserwowane
     * 2026-04-21 na lokalnym Windows stress test (9/10 wątków success, 1 rzucał).
     *
     * First-writer-wins eliminuje problem: tylko jeden wątek commits, reszta
     * wycofuje się cicho. Zero overwrite contention, zero partial writes (atomic
     * move gwarantuje all-or-nothing visibility).
     *
     * Plik wyjściowy zawsze będzie miał PEŁNY content (nigdy partial) —
     * reader przez `get()` widzi albo brak pliku, albo complete bytes od
     * zwycięskiego wątku.
     *
     * Windows NTFS: ATOMIC_MOVE = `MoveFileEx(MOVEFILE_WRITE_THROUGH)`, atomic.
     * Linux: `rename(2)` atomic per POSIX dla same-filesystem.
     *
     * Temp file MUSI być w tym samym filesystemie co target (stąd
     * `createTempFile(classFile.parent, ...)` — sibling guarantee).
     *
     * Na failed write/move cleanup temp file żeby nie zostawić orphan
     * w cache dir.
     */
    fun put(jarHash: String, classInternalName: String, bytes: ByteArray) {
        val classFile = cacheRoot.resolve(jarHash).resolve("$classInternalName.class")
        Files.createDirectories(classFile.parent)

        // Fast path: plik już istnieje (np. z poprzedniego wątku) — skip write.
        // Transform deterministic = content identyczny = pomijanie bezpieczne.
        // Nie-atomic check (TOCTOU) ale harmless: gdy race na exists/write
        // wystąpi, dolna logika złapie `FileAlreadyExistsException`.
        if (Files.exists(classFile)) return

        val tmpFile = Files.createTempFile(
            classFile.parent,
            "${classInternalName.substringAfterLast('/')}-",
            ".tmp"
        )
        try {
            Files.write(tmpFile, bytes)
            try {
                Files.move(tmpFile, classFile, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.FileSystemException) {
                // Racing threads dla tego samego klucza mogą zobaczyć:
                //  - `FileAlreadyExistsException` (POSIX standard zachowanie
                //     ATOMIC_MOVE gdy target istnieje) — jeden wygrał, my drudzy
                //  - `AccessDeniedException` (Windows specific) — target
                //     istnieje, ALE NTFS trzyma handle lock od file close
                //     previous wątku (Sub 2b learnings #14 NTFS lock delay)
                //
                // W obu przypadkach: jeśli target widoczny w filesystemie,
                // inny wątek SKUTECZNIE wpisał content (transform deterministic
                // → byte-equivalent). Drop temp, success path.
                //
                // Jeśli target NIE istnieje — to prawdziwy IO problem
                // (permissions, dysk pełny, path invalid), rzut.
                if (Files.exists(classFile)) {
                    Files.deleteIfExists(tmpFile)
                } else {
                    throw e
                }
            }
        } catch (e: Exception) {
            // Cleanup temp na każdym innym fail path — bez tego cache dir
            // zapełniałby się orphan .tmp.
            try {
                Files.deleteIfExists(tmpFile)
            } catch (cleanupErr: Exception) {
                logger.warn("Failed to cleanup orphan temp file {}: {}", tmpFile, cleanupErr.message)
            }
            throw e
        }
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
