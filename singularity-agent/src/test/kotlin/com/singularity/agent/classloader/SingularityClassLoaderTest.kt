// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.classloader

import com.singularity.agent.remapping.InheritanceTree
import com.singularity.agent.remapping.MappingTable
import com.singularity.agent.remapping.RemappingEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class SingularityClassLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    /** Minimalny valid classfile dla given internal name. */
    private fun generateClassBytes(internalName: String): ByteArray {
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

    private fun createJarWithClass(jarName: String, internalName: String, bytes: ByteArray): Path {
        val path = tempDir.resolve(jarName)
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            jos.putNextEntry(JarEntry("$internalName.class"))
            jos.write(bytes)
            jos.closeEntry()
        }
        return path
    }

    private fun buildEmptyEngine(): RemappingEngine {
        val tree = InheritanceTree()
        val empty = MappingTable("empty", emptyMap(), emptyMap(), emptyMap())
        return RemappingEngine(empty, empty, empty, tree)
    }

    @Test
    fun `JDK classes are delegated to parent classloader`() {
        val loader = SingularityClassLoader(
            parent = ClassLoader.getSystemClassLoader(),
            jarRegistry = JarRegistry(),
            remappingEngine = buildEmptyEngine(),
            transformFunction = { _, bytes -> bytes }
        )
        val stringClass = loader.loadClass("java.lang.String")
        assertNotNull(stringClass)
        assertEquals("java.lang.String", stringClass.name)
    }

    @Test
    fun `agent classes are delegated to parent classloader`() {
        val loader = SingularityClassLoader(
            parent = ClassLoader.getSystemClassLoader(),
            jarRegistry = JarRegistry(),
            remappingEngine = buildEmptyEngine(),
            transformFunction = { _, bytes -> bytes }
        )
        // Agent own classes powinny isc do parent (unikamy rekursywnego loading)
        val loaded = loader.loadClass("com.singularity.agent.classloader.JarRegistry")
        assertNotNull(loaded)
        assertEquals("com.singularity.agent.classloader.JarRegistry", loaded.name)
    }

    @Test
    fun `findClass loads non-agent class from JarRegistry and transforms it`() {
        val internalName = "com/test/Example"
        val bytes = generateClassBytes(internalName)
        val jarPath = createJarWithClass("test.jar", internalName, bytes)

        val jarRegistry = JarRegistry()
        jarRegistry.addJar(jarPath)

        val transformCalled = AtomicInteger(0)
        val loader = SingularityClassLoader(
            parent = ClassLoader.getSystemClassLoader(),
            jarRegistry = jarRegistry,
            remappingEngine = buildEmptyEngine(),
            transformFunction = { name, classBytes ->
                transformCalled.incrementAndGet()
                assertEquals("com/test/Example", name)
                classBytes
            }
        )

        val clazz = loader.loadClass("com.test.Example")
        assertNotNull(clazz)
        assertEquals("com.test.Example", clazz.name)
        assertEquals(loader, clazz.classLoader)
        assertEquals(1, transformCalled.get(), "Transform function must be called exactly once")
    }

    @Test
    fun `findClass uses reverseResolveClass for mojmap name lookup`() {
        // Scenariusz Fabric: klasa w JAR jest pod "pkg/orig/C" (intermediary),
        // mojmap to "pkg/mojmap/C". Loader dostaje loadClass("pkg.mojmap.C") →
        // reverseResolve → "pkg/orig/C" → znajduje w JAR.
        val originalName = "pkg/orig/C"
        val mojmapName = "pkg/mojmap/C"
        val bytes = generateClassBytes(originalName)
        val jarPath = createJarWithClass("fabric-mod.jar", originalName, bytes)

        val jarRegistry = JarRegistry()
        jarRegistry.addJar(jarPath)

        // Engine z mapping pkg/orig/C → pkg/mojmap/C (w intermediary table)
        val tree = InheritanceTree()
        val intermediaryTable = MappingTable(
            "intermediary",
            classes = mapOf("pkg/orig/C" to "pkg/mojmap/C"),
            methods = emptyMap(),
            fields = emptyMap()
        )
        val engine = RemappingEngine(
            MappingTable("obf", emptyMap(), emptyMap(), emptyMap()),
            MappingTable("srg", emptyMap(), emptyMap(), emptyMap()),
            intermediaryTable,
            tree
        )

        // Transform function ma zwrocic bytes z ZMIENIONA class name (bo ClassFileTransformer
        // normalnie by zremapowal internal name). W prawdziwym pipeline RemappingClassVisitor
        // to zrobi. Tutaj symulujemy przez ClassWriter z nowa nazwa.
        val mojmapBytes = generateClassBytes(mojmapName)
        val loader = SingularityClassLoader(
            parent = ClassLoader.getSystemClassLoader(),
            jarRegistry = jarRegistry,
            remappingEngine = engine,
            transformFunction = { _, _ -> mojmapBytes }
        )

        // loadClass z mojmap nazwą → reverse resolve → znajduje pkg/orig/C → transform → defineClass("pkg.mojmap.C")
        val clazz = loader.loadClass("pkg.mojmap.C")
        assertNotNull(clazz)
        assertEquals("pkg.mojmap.C", clazz.name)
    }

    @Test
    fun `findClass throws ClassNotFoundException when class not in registry`() {
        val loader = SingularityClassLoader(
            parent = ClassLoader.getSystemClassLoader(),
            jarRegistry = JarRegistry(),
            remappingEngine = buildEmptyEngine(),
            transformFunction = { _, bytes -> bytes }
        )
        assertThrows(ClassNotFoundException::class.java) {
            loader.loadClass("com.nonexistent.Unknown")
        }
    }

    @Test
    fun `companion init runs without throwing (registerAsParallelCapable smoke test)`() {
        // Companion object init wywoluje ClassLoader.registerAsParallelCapable().
        // Pelna weryfikacja ParallelLoaders.isRegistered wymagala refleksji na
        // java.lang.ClassLoader$ParallelLoaders ktora jest zablokowana w Java 17+
        // przez module system (InaccessibleObjectException bez --add-opens).
        //
        // Smoke test: instantiate loader triggeruje companion init; jesli
        // registerAsParallelCapable rzuci, tworzenie loadera sie wywali.
        // Weryfikacja faktyczna jest przez code inspection (grep "registerAsParallelCapable"
        // w SingularityClassLoader.kt).
        val loader = assertDoesNotThrow {
            SingularityClassLoader(
                parent = ClassLoader.getSystemClassLoader(),
                jarRegistry = JarRegistry(),
                remappingEngine = buildEmptyEngine(),
                transformFunction = { _, bytes -> bytes }
            )
        }
        assertNotNull(loader)
    }

    @Test
    fun `addModJar delegates to JarRegistry addJar`() {
        val jarPath = createJarWithClass("mod.jar", "pkg/Mod", generateClassBytes("pkg/Mod"))

        val jarRegistry = JarRegistry()
        val loader = SingularityClassLoader(
            parent = ClassLoader.getSystemClassLoader(),
            jarRegistry = jarRegistry,
            remappingEngine = buildEmptyEngine(),
            transformFunction = { _, bytes -> bytes }
        )

        val jarHash = loader.addModJar(jarPath)
        assertNotNull(jarHash)
        assertEquals(16, jarHash.length)
        assertNotNull(jarRegistry.findClassBytes("pkg/Mod"))
    }
}
