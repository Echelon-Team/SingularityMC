package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class ModDiscoveryTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Helper: tworzy JAR z podanymi wpisami (nazwa → zawartość).
     */
    private fun createJar(name: String, entries: Map<String, String>): Path {
        val path = tempDir.resolve(name)
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            entries.forEach { (entryName, content) ->
                jos.putNextEntry(JarEntry(entryName))
                jos.write(content.toByteArray())
                jos.closeEntry()
            }
        }
        return path
    }

    private fun createJarWithManifest(name: String, manifestAttrs: Map<String, String>): Path {
        val path = tempDir.resolve(name)
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            manifestAttrs.forEach { (k, v) -> mainAttributes.putValue(k, v) }
        }
        JarOutputStream(Files.newOutputStream(path), manifest).use { }
        return path
    }

    @Test
    fun `discovers Fabric mod by fabric_mod_json`() {
        createJar("sodium.jar", mapOf(
            "fabric.mod.json" to """{"id":"sodium","version":"0.5.8","name":"Sodium"}"""
        ))

        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(1, results.size)
        assertEquals(LoaderType.FABRIC, results[0].loaderType)
        assertNotNull(results[0].rawFabricJson)
        assertNull(results[0].rawModsToml)
    }

    @Test
    fun `discovers Forge mod by mods_toml`() {
        createJar("create.jar", mapOf(
            "META-INF/mods.toml" to "modLoader=\"javafml\"\n[[mods]]\nmodId=\"create\""
        ))

        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(1, results.size)
        assertEquals(LoaderType.FORGE, results[0].loaderType)
        assertNull(results[0].rawFabricJson)
        assertNotNull(results[0].rawModsToml)
    }

    @Test
    fun `discovers multi-loader mod with both metadata files`() {
        createJar("architectury.jar", mapOf(
            "fabric.mod.json" to """{"id":"architectury","version":"9.0"}""",
            "META-INF/mods.toml" to "modLoader=\"javafml\"\n[[mods]]\nmodId=\"architectury\""
        ))

        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(1, results.size)
        assertEquals(LoaderType.MULTI, results[0].loaderType)
        assertNotNull(results[0].rawFabricJson)
        assertNotNull(results[0].rawModsToml)
    }

    @Test
    fun `identifies library by Maven group ID in manifest`() {
        createJarWithManifest("gson-2.10.jar", mapOf(
            "Implementation-Vendor-Id" to "com.google.code.gson"
        ))

        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(1, results.size)
        assertEquals(LoaderType.LIBRARY, results[0].loaderType)
        assertTrue(results[0].isLibrary)
        assertEquals("com.google.code.gson", results[0].mavenGroupId)
    }

    @Test
    fun `identifies unknown JAR without metadata`() {
        createJar("mystery.jar", mapOf(
            "com/example/Something.class" to "fake class bytes"
        ))

        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(1, results.size)
        assertEquals(LoaderType.UNKNOWN, results[0].loaderType)
    }

    @Test
    fun `ignores non-JAR files`() {
        Files.writeString(tempDir.resolve("readme.txt"), "hello")
        Files.writeString(tempDir.resolve("config.json"), "{}")
        createJar("real-mod.jar", mapOf("fabric.mod.json" to """{"id":"test","version":"1.0"}"""))

        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(1, results.size) // Tylko JAR
    }

    @Test
    fun `empty directory returns empty list`() {
        val results = ModDiscovery.scanDirectory(tempDir)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `nonexistent directory returns empty list`() {
        val results = ModDiscovery.scanDirectory(tempDir.resolve("nonexistent"))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `discovers multiple mods`() {
        createJar("mod-a.jar", mapOf("fabric.mod.json" to """{"id":"mod-a","version":"1.0"}"""))
        createJar("mod-b.jar", mapOf("META-INF/mods.toml" to "[[mods]]\nmodId=\"mod-b\""))
        createJar("mod-c.jar", mapOf("fabric.mod.json" to """{"id":"mod-c","version":"1.0"}"""))

        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(3, results.size)
    }

    @Test
    fun `identifies library by known Maven groups`() {
        createJarWithManifest("commons-lang.jar", mapOf(
            "Bundle-SymbolicName" to "org.apache.commons.lang3"
        ))

        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(1, results.size)
        assertEquals(LoaderType.LIBRARY, results[0].loaderType)
    }

    @Test
    fun `corrupted JAR does not crash scan — skip and continue`() {
        // Corrupted JAR file (random bytes, not a valid zip)
        Files.write(tempDir.resolve("corrupted.jar"), byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
        createJar("valid.jar", mapOf("fabric.mod.json" to """{"id":"valid","version":"1.0"}"""))

        // Powinno zwrócić 1 valid mod (corrupted pominięty)
        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(1, results.size)
        assertEquals("valid.jar", results[0].jarPath.fileName.toString())
    }

    @Test
    fun `scanDirectory is non-recursive — ignores subdirectories with valid JAR`() {
        // Real fabric JAR w subfolderze — gdyby scanner był recursive, znalazłby go.
        // Wcześniejsza wersja używała ByteArray(0) co było false security (corrupted JAR
        // i tak byłby skipped przez catch, niezależnie od recursive-or-not).
        val subDir = tempDir.resolve("nested")
        Files.createDirectory(subDir)
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        JarOutputStream(Files.newOutputStream(subDir.resolve("nested-mod.jar")), manifest).use { jos ->
            jos.putNextEntry(JarEntry("fabric.mod.json"))
            jos.write("""{"id":"nested","version":"1.0"}""".toByteArray())
            jos.closeEntry()
        }
        createJar("root-mod.jar", mapOf("fabric.mod.json" to """{"id":"root","version":"1.0"}"""))

        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(1, results.size, "Subdirectory JARs should be IGNORED (non-recursive)")
        assertEquals("root-mod.jar", results[0].jarPath.fileName.toString())
    }

    @Test
    fun `case-insensitive JAR extension (uppercase _JAR)`() {
        createJar("UPPER.JAR", mapOf("fabric.mod.json" to """{"id":"upper","version":"1.0"}"""))
        val results = ModDiscovery.scanDirectory(tempDir)
        assertEquals(1, results.size)
    }

    @Test
    fun `discovered mods include all expected identities not just count`() {
        createJar("a.jar", mapOf("fabric.mod.json" to """{"id":"mod-a","version":"1.0"}"""))
        createJar("b.jar", mapOf("fabric.mod.json" to """{"id":"mod-b","version":"1.0"}"""))
        createJar("c.jar", mapOf("fabric.mod.json" to """{"id":"mod-c","version":"1.0"}"""))

        val results = ModDiscovery.scanDirectory(tempDir)
        val names = results.map { it.jarPath.fileName.toString() }.toSet()
        assertEquals(setOf("a.jar", "b.jar", "c.jar"), names)
    }
}
