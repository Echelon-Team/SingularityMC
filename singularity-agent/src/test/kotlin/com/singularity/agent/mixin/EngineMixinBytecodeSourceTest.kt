package com.singularity.agent.mixin

import com.singularity.agent.classloader.JarRegistry
import com.singularity.agent.remapping.InheritanceTree
import com.singularity.agent.remapping.MappingTable
import com.singularity.agent.remapping.RemappingEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class EngineMixinBytecodeSourceTest {

    @TempDir
    lateinit var tempDir: Path

    private fun generateBytes(internalName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun createJar(internalName: String, bytes: ByteArray): Path {
        val path = tempDir.resolve("test-${internalName.replace('/', '_')}.jar")
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            jos.putNextEntry(JarEntry("$internalName.class"))
            jos.write(bytes)
            jos.closeEntry()
        }
        return path
    }

    @Test
    fun `getClassBytes returns remapped bytes with mojmap class name`() {
        val originalName = "obf/Target"
        val mojmapName = "net/minecraft/Target"
        val jarPath = createJar(originalName, generateBytes(originalName))

        val jarRegistry = JarRegistry()
        jarRegistry.addJar(jarPath)

        val tree = InheritanceTree()
        tree.register(originalName, "java/lang/Object", emptyList())
        val obfTable = MappingTable(
            "obf",
            classes = mapOf(originalName to mojmapName),
            methods = emptyMap(),
            fields = emptyMap()
        )
        val engine = RemappingEngine(
            obfTable,
            MappingTable("srg", emptyMap(), emptyMap(), emptyMap()),
            MappingTable("intermediary", emptyMap(), emptyMap(), emptyMap()),
            tree
        )

        val source = EngineMixinBytecodeSource(jarRegistry, engine)
        val bytes = source.getClassBytes(mojmapName)
        assertNotNull(bytes)

        // Weryfikuj ze bytes maja mojmap internal name
        val node = ClassNode()
        ClassReader(bytes!!).accept(node, 0)
        assertEquals(mojmapName, node.name)
    }

    @Test
    fun `getClassBytes returns null when class not in registry`() {
        val jarRegistry = JarRegistry()
        val tree = InheritanceTree()
        val engine = RemappingEngine(
            MappingTable("obf", emptyMap(), emptyMap(), emptyMap()),
            MappingTable("srg", emptyMap(), emptyMap(), emptyMap()),
            MappingTable("intermediary", emptyMap(), emptyMap(), emptyMap()),
            tree
        )
        val source = EngineMixinBytecodeSource(jarRegistry, engine)
        assertNull(source.getClassBytes("com/unknown/Class"))
    }

    @Test
    fun `getClassBytes returns bytes for class already in mojmap (MC 1 20 1 vanilla)`() {
        // MC 1.20.1 ma mojmap on disk — klasa nie jest w classes mapping (jest juz mojmap),
        // ale istnieje w JarRegistry. reverseResolveClass zwraca null → uzywamy internalName jako fallback.
        val mojmapName = "net/minecraft/world/entity/Entity"
        val jarPath = createJar(mojmapName, generateBytes(mojmapName))

        val jarRegistry = JarRegistry()
        jarRegistry.addJar(jarPath)

        val tree = InheritanceTree()
        val engine = RemappingEngine(
            MappingTable("obf", emptyMap(), emptyMap(), emptyMap()),
            MappingTable("srg", emptyMap(), emptyMap(), emptyMap()),
            MappingTable("intermediary", emptyMap(), emptyMap(), emptyMap()),
            tree
        )

        val source = EngineMixinBytecodeSource(jarRegistry, engine)
        val bytes = source.getClassBytes(mojmapName)
        assertNotNull(bytes)
    }
}
