package com.singularity.agent.mixin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class MixinConfigScannerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createJar(name: String, entries: Map<String, String>): Path {
        val path = tempDir.resolve(name)
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            for ((entryName, content) in entries) {
                jos.putNextEntry(JarEntry(entryName))
                jos.write(content.toByteArray())
                jos.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `extractFromFabricModJson handles string array form`() {
        val json = """{"id":"test","mixins":["test.mixins.json","test.client.mixins.json"]}"""
        val configs = MixinConfigScanner.extractFromFabricModJson(json)
        assertEquals(listOf("test.mixins.json", "test.client.mixins.json"), configs)
    }

    @Test
    fun `extractFromFabricModJson handles object form with config field`() {
        val json = """
        {
          "id": "test",
          "mixins": [
            "test.mixins.json",
            {"config": "test.client.mixins.json", "environment": "client"}
          ]
        }
        """.trimIndent()
        val configs = MixinConfigScanner.extractFromFabricModJson(json)
        assertEquals(listOf("test.mixins.json", "test.client.mixins.json"), configs)
    }

    @Test
    fun `extractFromFabricModJson returns empty for mod without mixins`() {
        assertTrue(MixinConfigScanner.extractFromFabricModJson("""{"id":"simple","version":"1.0"}""").isEmpty())
    }

    @Test
    fun `extractFromFabricModJson returns empty for malformed JSON`() {
        assertTrue(MixinConfigScanner.extractFromFabricModJson("not json {").isEmpty())
    }

    @Test
    fun `extractFromModsToml finds config fields in mixins sections`() {
        val toml = """
            modLoader="javafml"
            [[mods]]
            modId="mymod"
            [[mixins]]
            config="mymod.mixins.json"
            [[mixins]]
            config="mymod.client.mixins.json"
        """.trimIndent()
        val configs = MixinConfigScanner.extractFromModsToml(toml)
        assertEquals(listOf("mymod.mixins.json", "mymod.client.mixins.json"), configs)
    }

    @Test
    fun `extractFromModsToml returns empty when no mixins section`() {
        val toml = """
            modLoader="javafml"
            [[mods]]
            modId="simple"
        """.trimIndent()
        assertTrue(MixinConfigScanner.extractFromModsToml(toml).isEmpty())
    }

    @Test
    fun `scanJar finds fabric mixin configs`() {
        val jar = createJar("fabric.jar", mapOf(
            "fabric.mod.json" to """{"id":"f","mixins":["f.mixins.json"]}"""
        ))
        val configs = MixinConfigScanner.scanJar(jar)
        assertEquals(listOf("f.mixins.json"), configs)
    }

    @Test
    fun `scanJar finds forge mixin configs`() {
        val jar = createJar("forge.jar", mapOf(
            "META-INF/mods.toml" to """
                modLoader="javafml"
                [[mods]]
                modId="m"
                [[mixins]]
                config="m.mixins.json"
            """.trimIndent()
        ))
        val configs = MixinConfigScanner.scanJar(jar)
        assertEquals(listOf("m.mixins.json"), configs)
    }

    @Test
    fun `scanJar finds BOTH fabric and forge configs (multi-loader JAR)`() {
        val jar = createJar("multi.jar", mapOf(
            "fabric.mod.json" to """{"id":"m","mixins":["m.fabric.mixins.json"]}""",
            "META-INF/mods.toml" to """
                modLoader="javafml"
                [[mods]]
                modId="m"
                [[mixins]]
                config="m.forge.mixins.json"
            """.trimIndent()
        ))
        val configs = MixinConfigScanner.scanJar(jar)
        assertTrue("m.fabric.mixins.json" in configs)
        assertTrue("m.forge.mixins.json" in configs)
    }

    @Test
    fun `scanJar returns empty for non-mod JAR`() {
        val jar = createJar("lib.jar", mapOf("com/example/Lib.class" to "dummy"))
        assertTrue(MixinConfigScanner.scanJar(jar).isEmpty())
    }
}
