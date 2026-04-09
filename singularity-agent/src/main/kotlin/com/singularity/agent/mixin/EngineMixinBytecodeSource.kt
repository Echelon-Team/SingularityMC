package com.singularity.agent.mixin

import com.singularity.agent.classloader.JarRegistry
import com.singularity.agent.remapping.RemappingClassVisitor
import com.singularity.agent.remapping.RemappingEngine
import org.slf4j.LoggerFactory

/**
 * Implementacja MixinBytecodeSource ktora uzywa JarRegistry + RemappingEngine
 * do dostarczenia MOJMAP-remapped bytes do Mixin framework.
 *
 * Flow getClassBytes(mojmapName):
 * 1. Reverse resolve: mojmap → original name (dla Fabric/Forge modow)
 * 2. JarRegistry.findClassBytes(original) → raw bytes (moga byc obf/SRG/Intermediary)
 * 3. RemappingClassVisitor.remap(rawBytes, engine) → mojmap bytes
 * 4. Return mojmap bytes
 *
 * UWAGA: nie przechodzimy przez full pipeline (cache, mixin apply) zeby uniknac rekursji.
 * Mixin framework pyta o bytes ZANIM bedzie aplikowal mixiny, wiec nie mozemy tu odpalic
 * kroku Mixin apply (infinite loop). Cache tez pominiety bo to jest "mixin view" a nie
 * "jvm load view" — moga byc roznice semantyczne w future (np. pre-scan mode).
 */
class EngineMixinBytecodeSource(
    private val jarRegistry: JarRegistry,
    private val remappingEngine: RemappingEngine
) : MixinBytecodeSource {

    private val logger = LoggerFactory.getLogger(EngineMixinBytecodeSource::class.java)

    override fun getClassBytes(internalName: String): ByteArray? {
        // Reverse resolve — jesli klasa jest w mod JAR pod SRG/Intermediary,
        // dostaniemy original name. Jesli vanilla MC 1.20.1 (mojmap on disk), reverseResolve
        // zwroci null i uzywamy internalName bezposrednio.
        val originalName = remappingEngine.reverseResolveClass(internalName) ?: internalName

        val source = jarRegistry.findClassBytes(originalName) ?: run {
            logger.debug("Class not in JarRegistry: {} (original: {})", internalName, originalName)
            return null
        }

        // Remap raw bytes → mojmap bytes (tylko kroki 2-3 pipeline, bez cache i mixin)
        return try {
            RemappingClassVisitor.remap(source.bytes, remappingEngine)
        } catch (e: Exception) {
            logger.error("Remap failed for {} (Mixin bytecode source): {}", internalName, e.message, e)
            null
        }
    }
}
