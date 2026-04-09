package com.singularity.agent.classloader

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * In-memory registry mapping original class internal names → source JAR location.
 *
 * Uzywane przez SingularityClassLoader:
 * 1. Reverse resolve: mojmap "net/minecraft/world/entity/Entity" → obf/SRG "a" lub "class_1297"
 *    (przez RemappingEngine.reverseResolveClass — Task 0.2)
 * 2. `findClassBytes(originalName)` znajduje JAR i wczytuje bytes
 * 3. Cache key uzywa jarHash z tego lookup'u
 *
 * Thread-safe:
 * - addJar(): write-once per JAR podczas bootstrap, serializowane przez ConcurrentHashMap
 * - findClassBytes(): read-only po bootstrap, thread-safe przez ConcurrentHashMap
 *
 * Windows file handle safety:
 * - addJar() otwiera JarFile z .use{} i zamyka PRZED return
 * - findClassBytes() otwiera nowy JarFile(path).use{} per call
 * - Path do JAR jest cache'owany, ale handle nie jest
 *
 * Referencja: AD3 w plan v2.3, bytecode-safety section F.7.
 */
class JarRegistry {

    private val logger = LoggerFactory.getLogger(JarRegistry::class.java)

    /**
     * Index klasy → metadane JAR'a w ktorym klasa jest.
     * Klucz: original internal name (np. "net/minecraft/class_1297" dla Fabric moda).
     */
    private val classIndex = ConcurrentHashMap<String, JarEntry>()

    /** Wszystkie zarejestrowane jarHashe. Uzywane przez TransformCache.cleanup. */
    private val jarHashes = ConcurrentHashMap.newKeySet<String>()

    private data class JarEntry(
        val jarHash: String,
        val jarPath: Path,
        val entryName: String
    )

    /**
     * Dodaje JAR do rejestru. Computuje jarHash, skanuje wszystkie `.class` entries,
     * dodaje do index'u. Nie przechowuje handle do JAR'a.
     *
     * @param path sciezka JAR'a (musi istniec)
     * @return jarHash (16 hex chars — first 8 bytes SHA-256 z bytes pliku)
     */
    fun addJar(path: Path): String {
        require(Files.exists(path)) { "JAR file does not exist: $path" }

        val jarHash = computeJarHash(path)
        jarHashes.add(jarHash)

        JarFile(path.toFile()).use { jar ->
            val entries = jar.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (!entry.name.endsWith(".class")) continue
                if (entry.name.startsWith("META-INF/")) continue  // skip module-info, signatures

                val internalName = entry.name.removeSuffix(".class")
                val existing = classIndex.putIfAbsent(internalName, JarEntry(jarHash, path, entry.name))
                if (existing != null && existing.jarHash != jarHash) {
                    logger.warn(
                        "Duplicate class {} found in {} (already indexed from {})",
                        internalName, path.fileName, existing.jarPath.fileName
                    )
                }
                count++
            }
            logger.info("Indexed {} classes from {} (jarHash={})", count, path.fileName, jarHash)
        }

        return jarHash
    }

    /**
     * Znajduje bytes klasy przez original internal name.
     *
     * Otwiera JarFile z .use{} per call — nie cache'uje handle (Windows safety).
     * W hot path bootstrap'u (30k+ klas MC), overhead jest acceptable bo JarFile
     * internal cache'uje directory na poziomie JVM.
     *
     * @param originalInternalName nazwa klasy JAKO W JAR (obf/SRG/Intermediary/mojmap)
     * @return ClassBytesSource lub null jesli klasa nie jest zarejestrowana
     */
    fun findClassBytes(originalInternalName: String): ClassBytesSource? {
        val entry = classIndex[originalInternalName] ?: return null
        return JarFile(entry.jarPath.toFile()).use { jar ->
            val jarEntry = jar.getJarEntry(entry.entryName) ?: run {
                logger.error(
                    "Class {} indexed but entry {} not found in {}",
                    originalInternalName, entry.entryName, entry.jarPath.fileName
                )
                return@use null
            }
            val bytes = jar.getInputStream(jarEntry).use { it.readBytes() }
            ClassBytesSource(bytes, entry.jarHash, entry.jarPath)
        }
    }

    /**
     * Zwraca jarHash bez wczytywania bytes. Uzywane przez cache key computation.
     */
    fun getJarHashForClass(originalInternalName: String): String? =
        classIndex[originalInternalName]?.jarHash

    /**
     * Zwraca set WSZYSTKICH jarHashow dla TransformCache.cleanup.
     */
    fun getAllJarHashes(): Set<String> = jarHashes.toSet()

    /**
     * Liczba zarejestrowanych klas (dla diagnostyki).
     */
    val size: Int get() = classIndex.size

    /**
     * Computuje jarHash = first 8 bytes SHA-256 z bytes pliku, hex-encoded (16 chars).
     * 64-bit entropia — dla realistic use case (dziesiatki-setki JARow w instance)
     * collision probability ~2.7e-12 (birthday paradox). Dla Sub 2b absolutnie wystarczajace.
     */
    private fun computeJarHash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var n = input.read(buffer)
            while (n > 0) {
                digest.update(buffer, 0, n)
                n = input.read(buffer)
            }
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
