package com.singularity.agent.classloader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class JarRegistryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createJar(name: String, entries: Map<String, ByteArray>): Path {
        val path = tempDir.resolve(name)
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
        }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            for ((entryName, bytes) in entries) {
                jos.putNextEntry(JarEntry(entryName))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `addJar indexes class entries and returns jar hash`() {
        // MANIFEST.MF jest auto-dodawany przez JarOutputStream(manifest) — NIE dodajemy
        // go w entries mapie, inaczej ZipException duplicate entry.
        val jarPath = createJar("test.jar", mapOf(
            "com/example/Foo.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 1, 2, 3, 4),
            "com/example/Bar.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 5, 6, 7, 8)
        ))

        val registry = JarRegistry()
        val jarHash = registry.addJar(jarPath)

        assertNotNull(jarHash)
        assertEquals(16, jarHash.length, "Jar hash should be 16 hex chars (first 8 bytes of SHA-256)")

        val source = registry.findClassBytes("com/example/Foo")
        assertNotNull(source)
        assertEquals(jarHash, source!!.jarHash)
        assertEquals(jarPath, source.jarPath)
        assertTrue(source.bytes.isNotEmpty())
    }

    @Test
    fun `findClassBytes returns null for unknown class`() {
        val registry = JarRegistry()
        assertNull(registry.findClassBytes("com/unknown/Class"))
    }

    @Test
    fun `findClassBytes searches across multiple jars`() {
        val jarA = createJar("a.jar", mapOf(
            "pkg/A.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 1)
        ))
        val jarB = createJar("b.jar", mapOf(
            "pkg/B.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 2)
        ))

        val registry = JarRegistry()
        val hashA = registry.addJar(jarA)
        val hashB = registry.addJar(jarB)

        assertNotEquals(hashA, hashB)

        val sourceA = registry.findClassBytes("pkg/A")
        assertNotNull(sourceA)
        assertEquals(hashA, sourceA!!.jarHash)

        val sourceB = registry.findClassBytes("pkg/B")
        assertNotNull(sourceB)
        assertEquals(hashB, sourceB!!.jarHash)
    }

    @Test
    fun `getJarHashForClass returns hash without reading bytes`() {
        val jarPath = createJar("x.jar", mapOf(
            "pkg/X.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 42)
        ))
        val registry = JarRegistry()
        val expectedHash = registry.addJar(jarPath)

        assertEquals(expectedHash, registry.getJarHashForClass("pkg/X"))
        assertNull(registry.getJarHashForClass("pkg/Unknown"))
    }

    @Test
    fun `getAllJarHashes returns all registered jars`() {
        val jarA = createJar("a.jar", mapOf("pkg/A.class" to ByteArray(8)))
        val jarB = createJar("b.jar", mapOf("pkg/B.class" to ByteArray(8)))

        val registry = JarRegistry()
        val hashA = registry.addJar(jarA)
        val hashB = registry.addJar(jarB)

        val all = registry.getAllJarHashes()
        assertEquals(2, all.size)
        assertTrue(hashA in all)
        assertTrue(hashB in all)
    }

    @Test
    fun `addJar does not leak JarFile handle on Windows`() {
        // Windows file handle test: po addJar + findClassBytes, plik JAR musi byc
        // mozliwy do usuniecia. Jesli JarRegistry nie zamknal handle, Files.delete
        // rzuci IOException na Windows (Unix pozwala delete z open handle).
        val jarPath = createJar("deletable.jar", mapOf("pkg/Del.class" to ByteArray(8)))
        val registry = JarRegistry()
        registry.addJar(jarPath)
        registry.findClassBytes("pkg/Del")

        // Delete — musi sie udac
        Files.delete(jarPath)
        assertFalse(Files.exists(jarPath))
    }
}
