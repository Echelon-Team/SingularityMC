// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.mixin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.slf4j.LoggerFactory
import org.spongepowered.asm.launch.platform.container.IContainerHandle
import org.spongepowered.asm.logging.ILogger
import org.spongepowered.asm.logging.LoggerAdapterJava
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.service.IClassBytecodeProvider
import org.spongepowered.asm.service.IClassProvider
import org.spongepowered.asm.service.IClassTracker
import org.spongepowered.asm.service.IMixinAuditTrail
import org.spongepowered.asm.service.IMixinInternal
import org.spongepowered.asm.service.IMixinService
import org.spongepowered.asm.service.ITransformerProvider
import org.spongepowered.asm.util.ReEntranceLock
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementacja IMixinService SPI dla SingularityMC.
 *
 * Discovery: Mixin framework znajduje te klase przez ServiceLoader (META-INF/services/).
 *
 * Bytecode source injection: AgentMain po discovery wstrzykuje MixinBytecodeSource
 * (implementacja EngineMixinBytecodeSource uzywa JarRegistry + RemappingEngine).
 * Bez tej injection, getClassNode rzuca IllegalStateException.
 *
 * Metody abstract z IMixinService 0.8.7 (zweryfikowane przez web-researcher vs source):
 * - IMixinService: getName, isValid, prepare, getInitialPhase, init, beginPhase, checkEnv,
 *   getReEntranceLock, getClassProvider, getBytecodeProvider, getTransformerProvider,
 *   getSideName, getMinCompatibilityLevel, getMaxCompatibilityLevel, getPlatformAgents,
 *   getPrimaryContainer, getMixinContainers, getResourceAsStream, offer,
 *   getClassTracker, getAuditTrail, getLogger
 * - IClassProvider (osobny interfejs zwracany przez getClassProvider): getClassPath,
 *   findClass(name), findClass(name, init), findAgentClass
 * - IClassBytecodeProvider (osobny interfejs zwracany przez getBytecodeProvider):
 *   getClassNode(name), getClassNode(name, runTransformers), getClassNode(name, runTransformers, readerFlags)
 *
 * UWAGA: `wire`/`unwire` NIE istnieja w IMixinService 0.8.7 — bledne wymienione w planie v1.
 *
 * Referencja: design spec sekcja 5A.6, AD6 w plan v2.3.
 */
class SingularityMixinService : IMixinService, IClassProvider, IClassBytecodeProvider {

    private val slf4jLogger = LoggerFactory.getLogger(SingularityMixinService::class.java)
    private val lock = ReEntranceLock(1)

    /**
     * Cache Mixin-facing loggerow per name. ConcurrentHashMap zapewnia thread-safety
     * atomic put-if-absent przez getOrPut (contract IMixinService.getLogger:
     * "must not return null, thread-safe").
     *
     * Sub 2b uzywa LoggerAdapterJava (wraps java.util.logging). TODO Sub 5: wlasny
     * adapter ktory wraps org.slf4j.Logger zeby Mixin logs szly do naszego logback'a
     * z pozostalymi logami agenta.
     */
    private val mixinLoggerCache = ConcurrentHashMap<String, ILogger>()

    /**
     * MixinBytecodeSource wstrzykiwany przez AgentMain po ServiceLoader discovery.
     * Do injection point'u = null → getClassNode throws.
     */
    var bytecodeSource: MixinBytecodeSource? = null

    // === IMixinService ===

    override fun getName(): String = "SingularityMC"

    override fun isValid(): Boolean = true

    override fun prepare() {
        slf4jLogger.info("SingularityMixinService.prepare() called")
    }

    override fun getInitialPhase(): MixinEnvironment.Phase = MixinEnvironment.Phase.PREINIT

    override fun init() {
        slf4jLogger.info("SingularityMixinService.init() called")
    }

    override fun beginPhase() {
        slf4jLogger.debug("SingularityMixinService.beginPhase() called")
    }

    override fun checkEnv(bootSource: Any?) {
        // No-op — environment check nie jest potrzebny w Sub 2b
    }

    override fun getReEntranceLock(): ReEntranceLock = lock

    override fun getClassProvider(): IClassProvider = this

    override fun getBytecodeProvider(): IClassBytecodeProvider = this

    override fun getTransformerProvider(): ITransformerProvider? = null

    override fun getClassTracker(): IClassTracker? = null

    override fun getAuditTrail(): IMixinAuditTrail? = null

    override fun getSideName(): String = "CLIENT"

    override fun getMinCompatibilityLevel(): MixinEnvironment.CompatibilityLevel? = null

    override fun getMaxCompatibilityLevel(): MixinEnvironment.CompatibilityLevel? = null

    /**
     * Zwraca Mixin ILogger dla podanego name. MUSI byc non-null i thread-safe
     * (kontrakt IMixinService). Wykorzystuje ConcurrentHashMap.getOrPut dla
     * atomic put-if-absent.
     */
    override fun getLogger(name: String): ILogger =
        mixinLoggerCache.getOrPut(name) { LoggerAdapterJava(name) }

    override fun getPlatformAgents(): Collection<String> = emptyList()

    override fun getPrimaryContainer(): IContainerHandle = DummyContainerHandle

    override fun getMixinContainers(): Collection<IContainerHandle> = emptyList()

    override fun getResourceAsStream(name: String?): InputStream? {
        if (name == null) return null
        return javaClass.classLoader.getResourceAsStream(name)
    }

    override fun offer(internal: IMixinInternal?) {
        // No-op — Mixin framework passes internal API, Sub 2b nie wykorzystuje
    }

    // === IClassProvider ===

    @Deprecated("Deprecated in IClassProvider since Mixin 0.8 — zwraca empty array")
    override fun getClassPath(): Array<URL> = emptyArray()

    override fun findClass(name: String?): Class<*> =
        Class.forName(name, false, javaClass.classLoader)

    override fun findClass(name: String?, initialize: Boolean): Class<*> =
        Class.forName(name, initialize, javaClass.classLoader)

    override fun findAgentClass(name: String?, initialize: Boolean): Class<*> =
        Class.forName(name, initialize, javaClass.classLoader)

    // === IClassBytecodeProvider ===

    override fun getClassNode(name: String?): ClassNode = getClassNode(name, true)

    override fun getClassNode(name: String?, runTransformers: Boolean): ClassNode {
        requireNotNull(name) { "Class name must not be null" }
        val source = bytecodeSource
            ?: throw IllegalStateException(
                "MixinBytecodeSource not injected. AgentMain musi wstrzyknac source przed MixinBootstrap.init()."
            )

        val bytes = source.getClassBytes(name)
            ?: throw ClassNotFoundException(name)

        val node = ClassNode()
        ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES)
        return node
    }

    /**
     * Third overload wymagany przez IClassBytecodeProvider w Mixin 0.8.7.
     * readerFlags to ClassReader flags (SKIP_CODE, SKIP_DEBUG, SKIP_FRAMES, EXPAND_FRAMES).
     */
    override fun getClassNode(name: String?, runTransformers: Boolean, readerFlags: Int): ClassNode {
        requireNotNull(name) { "Class name must not be null" }
        val source = bytecodeSource
            ?: throw IllegalStateException(
                "MixinBytecodeSource not injected. AgentMain musi wstrzyknac source przed MixinBootstrap.init()."
            )

        val bytes = source.getClassBytes(name)
            ?: throw ClassNotFoundException(name)

        val node = ClassNode()
        ClassReader(bytes).accept(node, readerFlags)
        return node
    }

    /**
     * Dummy IContainerHandle — Mixin framework wymaga non-null ale w Sub 2b
     * nie uzywamy container-based discovery (feature Fabric/Forge loader'ow).
     */
    private object DummyContainerHandle : IContainerHandle {
        override fun getAttribute(name: String?): String? = null
        override fun getId(): String = "singularity"
        override fun getDescription(): String = "SingularityMC primary container"
        override fun getNestedContainers(): Collection<IContainerHandle> = emptyList()
    }
}
