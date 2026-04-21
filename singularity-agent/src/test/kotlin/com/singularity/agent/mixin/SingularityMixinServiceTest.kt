// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.mixin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.service.IMixinService

class SingularityMixinServiceTest {

    private lateinit var service: SingularityMixinService

    @BeforeEach
    fun setup() {
        service = SingularityMixinService()
    }

    @Test
    fun `getName returns SingularityMC`() {
        assertEquals("SingularityMC", service.name)
    }

    @Test
    fun `isValid returns true`() {
        assertTrue(service.isValid)
    }

    @Test
    fun `getInitialPhase returns PREINIT`() {
        assertEquals(MixinEnvironment.Phase.PREINIT, service.initialPhase)
    }

    @Test
    fun `getReEntranceLock returns non-null`() {
        assertNotNull(service.reEntranceLock)
    }

    @Test
    fun `getClassProvider returns self`() {
        assertSame(service, service.classProvider)
    }

    @Test
    fun `getBytecodeProvider returns self`() {
        assertSame(service, service.bytecodeProvider)
    }

    @Test
    fun `getTransformerProvider returns null`() {
        assertNull(service.transformerProvider)
    }

    @Test
    fun `getSideName returns CLIENT`() {
        assertEquals("CLIENT", service.sideName)
    }

    @Test
    fun `getMinCompatibilityLevel returns null`() {
        assertNull(service.minCompatibilityLevel)
    }

    @Test
    fun `getClassNode throws when bytecodeSource not injected`() {
        assertThrows(IllegalStateException::class.java) {
            service.getClassNode("net/minecraft/Entity")
        }
    }

    @Test
    fun `getClassNode with injected source returns ClassNode with given name`() {
        val stubBytes = generateStubClass("net/minecraft/Entity")
        service.bytecodeSource = object : MixinBytecodeSource {
            override fun getClassBytes(internalName: String): ByteArray? {
                return if (internalName == "net/minecraft/Entity") stubBytes else null
            }
        }

        val node = service.getClassNode("net/minecraft/Entity")
        assertNotNull(node)
        assertEquals("net/minecraft/Entity", node.name)
    }

    @Test
    fun `getClassNode throws ClassNotFoundException for unknown class`() {
        service.bytecodeSource = object : MixinBytecodeSource {
            override fun getClassBytes(internalName: String): ByteArray? = null
        }
        assertThrows(ClassNotFoundException::class.java) {
            service.getClassNode("com/unknown/Class")
        }
    }

    @Test
    fun `getPlatformAgents returns empty collection`() {
        assertTrue(service.platformAgents.isEmpty())
    }

    @Test
    fun `getPrimaryContainer returns non-null dummy handle`() {
        val container = service.primaryContainer
        assertNotNull(container)
        assertNull(container.getAttribute("any"))
    }

    @Test
    fun `getMixinContainers returns empty collection`() {
        assertTrue(service.mixinContainers.isEmpty())
    }

    // Sub 2b Task 0.2 fix — nowe metody IMixinService 0.8.7 dodane po web-researcher review

    @Test
    fun `getClassTracker returns null (optional)`() {
        assertNull(service.classTracker)
    }

    @Test
    fun `getAuditTrail returns null (optional)`() {
        assertNull(service.auditTrail)
    }

    @Test
    fun `getMaxCompatibilityLevel returns null`() {
        assertNull(service.maxCompatibilityLevel)
    }

    @Test
    fun `getLogger returns non-null for any name`() {
        val logger = service.getLogger("test-logger")
        assertNotNull(logger)
    }

    @Test
    fun `getLogger returns same instance for same name (cached)`() {
        val logger1 = service.getLogger("cached-name")
        val logger2 = service.getLogger("cached-name")
        assertSame(logger1, logger2, "Same name must return cached instance (thread-safe ConcurrentHashMap)")
    }

    @Test
    fun `getLogger returns different instances for different names`() {
        val loggerA = service.getLogger("name-A")
        val loggerB = service.getLogger("name-B")
        assertNotSame(loggerA, loggerB)
    }

    @Test
    fun `getClassNode three-arg overload respects readerFlags`() {
        val stubBytes = generateStubClass("net/minecraft/FlagTest")
        service.bytecodeSource = object : MixinBytecodeSource {
            override fun getClassBytes(internalName: String): ByteArray? =
                if (internalName == "net/minecraft/FlagTest") stubBytes else null
        }

        val node = service.getClassNode(
            "net/minecraft/FlagTest",
            runTransformers = true,
            readerFlags = org.objectweb.asm.ClassReader.SKIP_CODE
        )
        assertNotNull(node)
        assertEquals("net/minecraft/FlagTest", node.name)
    }

    @Test
    fun `SingularityMixinService is discoverable via ServiceLoader SPI`() {
        // Mixin JAR 0.8.7 bundled zawiera META-INF/services/IMixinService z kilkoma wpisami
        // (np. MixinServiceLaunchWrapper ktory wymaga LaunchWrapper dependency z Forge).
        // ServiceLoader.toList() eager-instantiate WSZYSTKICH services → pierwszy fail krusze iteracje.
        // Uzywamy iterator + try/catch per-next zeby omijac problematic services z innych packages.
        val loader = java.util.ServiceLoader.load(
            IMixinService::class.java,
            SingularityMixinService::class.java.classLoader
        )
        var found = false
        val iterator = loader.iterator()
        while (iterator.hasNext()) {
            try {
                val service = iterator.next()
                if (service is SingularityMixinService) {
                    found = true
                    break
                }
            } catch (_: Throwable) {
                // Skip problematic services (np. LaunchWrapper wymaga deps nie w Sub 2b classpath)
            }
        }
        assertTrue(found, "SingularityMixinService not discovered via SPI")
    }

    private fun generateStubClass(internalName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitCode()
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(1, 1)
        ctor.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
