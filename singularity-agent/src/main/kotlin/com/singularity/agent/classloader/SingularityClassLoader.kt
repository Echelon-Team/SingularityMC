package com.singularity.agent.classloader

import com.singularity.agent.remapping.RemappingEngine
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Main path classloader dla SingularityMC. Laduje klasy MC i modow.
 *
 * Algorytm loadClass(name):
 * 1. JDK classes (java., javax., sun., jdk., com.sun.) → parent (ZAWSZE)
 * 2. Agent/common/framework classes (com.singularity.agent., com.singularity.common.) → parent
 * 3. Pozostale klasy:
 *    a. findLoadedClass → jesli juz zaladowane, zwroc
 *    b. findClass (reverse resolve + JarRegistry lookup + transform + defineClass)
 *    c. Jesli findClass throws → parent.loadClass (fallback dla klas w JVM classpath)
 *
 * Algorytm findClass(name):
 * 1. Konwertuj name (dot-separated) → internalName (slash-separated)
 * 2. Reverse resolve: RemappingEngine.reverseResolveClass(internalName) → originalName
 *    - Jesli reverse resolve zwroci null, uzyj internalName bezposrednio (klasa juz
 *      jest w mojmap format, np. MC 1.20.1 vanilla)
 * 3. JarRegistry.findClassBytes(originalName) → ClassBytesSource z raw bytes
 *    - Jesli null → ClassNotFoundException
 * 4. transformFunction(internalName, rawBytes) → transformedBytes
 * 5. defineClass(name, transformedBytes, 0, transformedBytes.size)
 *
 * Thread safety:
 * - registerAsParallelCapable() w static init → JVM serializuje loadClass na CLASS LOCK
 *   zamiast CLASSLOADER LOCK → paralelne ladowanie roznych klas
 * - JarRegistry jest thread-safe
 * - RemappingEngine jest thread-safe (tylko read operations post-bootstrap)
 * - transformFunction musi byc thread-safe (SingularityTransformer jest)
 *
 * **TODO Sub 2c: hybrid per-mod isolation.** W Sub 2b uzywamy JEDNEGO globalnego loadera
 * dla MC + wszystkich modow — wystarczajace dla pierwszej iteracji. W Sub 2c, gdy ModDiscovery
 * dostarczy liste modow + dependency graf, refactor na MultiModClassLoader (lub similar)
 * z per-mod isolation: mody widza swoje wersje bibliotek (child-first), shared libs są
 * w parent common scope. Aktualna implementacja nie przeszkadza w tym upgrade — classloader
 * API (addModJar, findClass) pozostaje backward compat w Sub 2c refactor.
 *
 * Referencja: design spec sekcja 5A.4, AD1 w plan header.
 */
class SingularityClassLoader(
    parent: ClassLoader,
    private val jarRegistry: JarRegistry,
    private val remappingEngine: RemappingEngine,
    private val transformFunction: (String, ByteArray) -> ByteArray
) : ClassLoader(parent) {

    private val logger = LoggerFactory.getLogger(SingularityClassLoader::class.java)

    companion object {
        // AD1: wlaczenie parallel class loading. Bez tego JVM serializuje WSZYSTKIE
        // loadClass na classloader instance lock → parallel transform bezcelowy.
        init {
            ClassLoader.registerAsParallelCapable()
        }

        private val JDK_PREFIXES = listOf(
            "java.", "javax.", "sun.", "jdk.", "com.sun.",
            "org.xml.", "org.w3c.", "org.ietf."
        )

        private val AGENT_PREFIXES = listOf(
            "com.singularity.agent.", "com.singularity.common.",
            "org.spongepowered.asm.",  // Mixin framework
            "org.objectweb.asm.",      // ASM library
            "org.slf4j.", "ch.qos.logback.",
            "kotlin.", "kotlinx.",
            "net.fabricmc.mappingio.", "net.fabricmc.tinyremapper."
        )

        internal fun isJdkClass(name: String): Boolean =
            JDK_PREFIXES.any { name.startsWith(it) }

        internal fun isAgentClass(name: String): Boolean =
            AGENT_PREFIXES.any { name.startsWith(it) }
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Synchronize per class name (parallel capable via registerAsParallelCapable)
        synchronized(getClassLoadingLock(name)) {
            // 1. JDK → parent (ZAWSZE)
            if (isJdkClass(name)) {
                return parent.loadClass(name)
            }

            // 2. Agent/common/framework classes → parent
            if (isAgentClass(name)) {
                return parent.loadClass(name)
            }

            // 3. Sprawdz czy juz zaladowane
            val loaded = findLoadedClass(name)
            if (loaded != null) {
                if (resolve) resolveClass(loaded)
                return loaded
            }

            // 4. Child-first: findClass z naszego JarRegistry
            return try {
                val clazz = findClass(name)
                if (resolve) resolveClass(clazz)
                clazz
            } catch (_: ClassNotFoundException) {
                // 5. Fallback do parent (dla klas w JVM classpath ktore nie sa w nasz registry)
                parent.loadClass(name)
            }
        }
    }

    override fun findClass(name: String): Class<*> {
        // Konwertuj dot-separated → slash-separated internal name
        val internalName = name.replace('.', '/')

        // Reverse resolve: mojmap → original (dla Fabric/Forge modow z SRG/Intermediary klasami)
        val originalName = remappingEngine.reverseResolveClass(internalName) ?: internalName

        // Find bytes w JarRegistry
        val source = jarRegistry.findClassBytes(originalName)
            ?: throw ClassNotFoundException(name)

        // Transform bytes przez pipeline (remapping + mixin + verify)
        // Transform function dostaje internalName (mojmap target) — pipeline RemappingClassVisitor
        // zmieni internal name w bytecode na mojmap. Post-transform bytes maja internalName jako binary name.
        val transformedBytes = try {
            transformFunction(internalName, source.bytes)
        } catch (e: Exception) {
            logger.error("Transform failed for {}: {}", internalName, e.message, e)
            throw ClassNotFoundException("Transform failed for $name", e)
        }

        // defineClass z mojmap name — bytes.internalName musi byc == internalName
        // (ClassFormatError wrong name w przeciwnym razie)
        return defineClass(name, transformedBytes, 0, transformedBytes.size)
    }

    /**
     * Dodaje JAR moda do registry. Uzywane przez Sub 2c ModDiscovery po discovery.
     *
     * @param path sciezka JAR'a moda
     * @return jarHash (do diagnostyki)
     */
    fun addModJar(path: Path): String {
        logger.info("Adding mod JAR: {}", path.fileName)
        return jarRegistry.addJar(path)
    }
}
