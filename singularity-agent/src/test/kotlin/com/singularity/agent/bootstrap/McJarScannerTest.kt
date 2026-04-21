// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.bootstrap

import com.singularity.agent.remapping.InheritanceTree
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class McJarScannerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun generateClass(name: String, parent: String, interfaces: Array<String> = emptyArray()): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, interfaces)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun createJar(fileName: String, classes: Map<String, ByteArray>): Path {
        val path = tempDir.resolve(fileName)
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            for ((name, bytes) in classes) {
                jos.putNextEntry(JarEntry("$name.class"))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `scan builds inheritance tree from MC JAR`() {
        val jar = createJar("mc.jar", mapOf(
            "net/minecraft/Entity" to generateClass("net/minecraft/Entity", "java/lang/Object"),
            "net/minecraft/LivingEntity" to generateClass("net/minecraft/LivingEntity", "net/minecraft/Entity"),
            "net/minecraft/Player" to generateClass("net/minecraft/Player", "net/minecraft/LivingEntity")
        ))

        val tree = InheritanceTree()
        val count = McJarScanner.scanInto(jar, tree)

        assertEquals(3, count)
        assertEquals("java/lang/Object", tree.getParent("net/minecraft/Entity"))
        assertEquals("net/minecraft/Entity", tree.getParent("net/minecraft/LivingEntity"))
        assertEquals("net/minecraft/LivingEntity", tree.getParent("net/minecraft/Player"))
    }

    @Test
    fun `scan captures interfaces`() {
        val jar = createJar("items.jar", mapOf(
            "net/minecraft/Item" to generateClass(
                "net/minecraft/Item",
                "java/lang/Object",
                arrayOf("java/lang/Comparable", "java/lang/Cloneable")
            )
        ))
        val tree = InheritanceTree()
        McJarScanner.scanInto(jar, tree)

        val ifaces = tree.getInterfaces("net/minecraft/Item")
        assertEquals(2, ifaces.size)
        assertTrue(ifaces.contains("java/lang/Comparable"))
        assertTrue(ifaces.contains("java/lang/Cloneable"))
    }

    @Test
    fun `scan returns 0 for empty JAR`() {
        val jar = createJar("empty.jar", emptyMap())
        val tree = InheritanceTree()
        val count = McJarScanner.scanInto(jar, tree)
        assertEquals(0, count)
        assertEquals(0, tree.size)
    }
}
