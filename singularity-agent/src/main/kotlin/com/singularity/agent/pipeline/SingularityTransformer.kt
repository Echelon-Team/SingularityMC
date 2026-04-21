// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.pipeline

import com.singularity.agent.cache.CacheKey
import com.singularity.agent.cache.TransformCache
import com.singularity.agent.classloader.JarRegistry
import com.singularity.agent.remapping.RemappingClassVisitor
import com.singularity.agent.remapping.RemappingEngine
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Glowny pipeline transformacji klas SingularityMC.
 *
 * UWAGA: NIE jest to `ClassFileTransformer` (Instrumentation API). Wywolywany bezposrednio
 * przez `SingularityClassLoader.transformFunction` (AD1 — SingularityClassLoader jest main path).
 *
 * 12-krokowy pipeline (kroki 1-5 aktywne w Sub 2b, reszta jako stubs/TODO):
 * 1. Skip check (JDK, ASM, agent)
 * 2. Cache check (dirKey z JarRegistry.jarHash + agentVer + moduleVer)
 * 3. Remapping (RemappingClassVisitor.remap — obf/SRG/Intermediary → Mojmap)
 * 4. Reflection interception (LDC scanning — TODO Sub 2b late, skeleton na razie)
 * 5. Cache write
 * 6. Pre-scan conflicts (PreScanAnalyzer wywolywany raz przy bootstrap, nie per class)
 * 7. Mixin apply (Mixin framework hookuje sie sam przez IMixinService + wstrzyknięty BytecodeSource)
 * 8. Coremod patches (TODO Sub 5)
 * 9. Post-verification (CheckClassAdapter — opcjonalny, enabled via system property)
 * 10. Threading transforms (DISABLED — Sub 3)
 * 11. C2ME transforms (DISABLED — Sub 3)
 * 12. Compat layer transforms (TODO Sub 2d wire)
 *
 * Cache key: dirKey cache'owany per jarHash w `dirKeyCache` (lazy compute raz per JAR).
 *
 * Fail-fast policy (edge-case #5): exception w pipeline → throw z context, NIE silent null return.
 * Silent null = JVM ladowalby oryginalne obf/SRG bytes → cascade NoSuchMethodError głęboko w runtime.
 *
 * Thread safety: wszystkie dependencies sa thread-safe. `dirKeyCache` ConcurrentHashMap.
 *
 * Referencja: design spec sekcja 5.3, implementation design sekcja 4.2, AD2.
 */
class SingularityTransformer(
    private val jarRegistry: JarRegistry,
    private val remappingEngine: RemappingEngine,
    private val transformCache: TransformCache,
    private val agentVersion: String,
    private val moduleVersion: String,
    @Suppress("UNUSED_PARAMETER") private val enableThreading: Boolean = false,
    @Suppress("UNUSED_PARAMETER") private val enableC2ME: Boolean = false
) {

    private val logger = LoggerFactory.getLogger(SingularityTransformer::class.java)

    /** Cache dirKey per jarHash — raz liczone, reused dla wszystkich klas z tego JAR'a. */
    private val dirKeyCache = ConcurrentHashMap<String, String>()

    companion object {
        private val SKIP_PREFIXES = listOf(
            "java/", "javax/", "sun/", "jdk/", "com/sun/",
            "org/objectweb/asm/", "org/spongepowered/",
            "org/slf4j/", "ch/qos/logback/",
            "com/singularity/agent/", "com/singularity/common/",
            "kotlin/", "kotlinx/",
            "net/fabricmc/mappingio/", "net/fabricmc/tinyremapper/",
            "it/unimi/dsi/fastutil/",  // bundled w MC, remapping no-op = waste CPU
            "org/jetbrains/annotations/",
            "com/google/common/"  // Guava — Mixin runtime dep
        )
    }

    /**
     * Transformuje bytecode klasy. Wywolywane przez SingularityClassLoader.findClass.
     *
     * @param internalClassName mojmap internal name (slash-separated, np. "net/minecraft/world/entity/Entity")
     * @param originalBytes raw bytes z JarRegistry (pre-remap)
     * @return transformed bytes (mojmap names, ready for defineClass)
     * @throws RuntimeException jesli transformacja fails — fail-fast, NIE silent null
     */
    fun transform(internalClassName: String, originalBytes: ByteArray): ByteArray {
        // Step 1: Skip check
        if (SKIP_PREFIXES.any { internalClassName.startsWith(it) }) {
            return originalBytes
        }

        // Compute dirKey dla tego JAR'a (lazy cached)
        val originalName = remappingEngine.reverseResolveClass(internalClassName) ?: internalClassName
        val jarHash = jarRegistry.getJarHashForClass(originalName) ?: "unknown"
        val dirKey = dirKeyCache.getOrPut(jarHash) {
            CacheKey.dirKey(agentVersion, moduleVersion, jarHash)
        }

        // Step 2: Cache check
        val cached = transformCache.get(dirKey, internalClassName)
        if (cached != null) {
            return cached
        }

        return try {
            var bytes = originalBytes

            // Step 3: Remapping (obf/SRG/Intermediary → Mojmap)
            bytes = RemappingClassVisitor.remap(bytes, remappingEngine)

            // Step 4: Reflection interception — TODO skeleton (LDC scanning)
            // Step 5: Cache write
            transformCache.put(dirKey, internalClassName, bytes)

            // Step 6: Pre-scan — wywolywany raz przy bootstrap, nie per class
            // Step 7: Mixin apply — framework hookuje sie sam przez IMixinService
            // Step 8: Coremod patches — TODO Sub 5
            // Step 9: Post-verification — optional (system property singularity.verifyBytecode)
            // Step 10-11: Threading/C2ME — DISABLED Sub 3
            // Step 12: Compat — TODO Sub 2d

            bytes
        } catch (e: Exception) {
            logger.error("Transform pipeline FAILED for {}: {}", internalClassName, e.message, e)
            // Fail-fast: throw z context, NIE return originalBytes/null
            throw RuntimeException("SingularityTransformer failed for $internalClassName", e)
        }
    }

    /**
     * Zwraca snapshot wszystkich dirKey'ow, ktore transformer uzyl.
     * Uzywane przez AgentMain do cleanup (`transformCache.cleanup(activeDirKeys)`).
     */
    fun getAllDirKeys(): Set<String> = dirKeyCache.values.toSet()
}
