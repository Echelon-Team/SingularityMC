package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class FabricMetadataParserTest {

    private val dummyPath = Paths.get("/mods/test.jar")

    @Test
    fun `parses complete Sodium-like fabric_mod_json`() {
        val json = """
        {
            "schemaVersion": 1,
            "id": "sodium",
            "version": "0.5.8+mc1.20.1",
            "name": "Sodium",
            "description": "A modern rendering engine for Minecraft",
            "authors": ["JellySquid"],
            "contact": {"homepage": "https://modrinth.com/mod/sodium"},
            "license": "LGPL-3.0-only",
            "environment": "client",
            "entrypoints": {
                "client": ["me.jellysquid.mods.sodium.client.SodiumClientMod"],
                "preLaunch": ["me.jellysquid.mods.sodium.client.SodiumPreLaunch"]
            },
            "mixins": ["sodium.mixins.json", "sodium.accesswidener.json"],
            "depends": {
                "fabricloader": ">=0.14.21",
                "minecraft": "~1.20.1",
                "java": ">=17"
            },
            "suggests": {
                "iris": "*"
            }
        }
        """.trimIndent()

        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals("sodium", result.modId)
        assertEquals("0.5.8+mc1.20.1", result.version)
        assertEquals("Sodium", result.name)
        assertEquals(LoaderType.FABRIC, result.loaderType)
        assertEquals(ModSide.CLIENT, result.side)
        assertEquals(listOf("JellySquid"), result.authors)
        assertEquals("A modern rendering engine for Minecraft", result.description)

        // Entrypoints: all values flattened
        assertTrue(result.entryPoints.contains("me.jellysquid.mods.sodium.client.SodiumClientMod"))
        assertTrue(result.entryPoints.contains("me.jellysquid.mods.sodium.client.SodiumPreLaunch"))

        // Mixins
        assertEquals(listOf("sodium.mixins.json", "sodium.accesswidener.json"), result.mixinConfigs)

        // Dependencies: depends = required, suggests = optional
        val fabricloaderDep = result.dependencies.first { it.modId == "fabricloader" }
        assertTrue(fabricloaderDep.required)
        assertEquals(">=0.14.21", fabricloaderDep.versionRange)

        val irisDep = result.dependencies.first { it.modId == "iris" }
        assertFalse(irisDep.required)
        assertEquals("*", irisDep.versionRange)
    }

    @Test
    fun `parses minimal fabric_mod_json`() {
        val json = """{"id":"simple","version":"1.0"}"""

        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals("simple", result.modId)
        assertEquals("1.0", result.version)
        assertEquals("simple", result.name) // nazwa = id gdy brak pola name
        assertEquals(ModSide.BOTH, result.side) // brak environment = BOTH
        assertTrue(result.dependencies.isEmpty())
        assertTrue(result.entryPoints.isEmpty())
        assertTrue(result.mixinConfigs.isEmpty())
        assertTrue(result.authors.isEmpty())
    }

    @Test
    fun `environment star maps to BOTH`() {
        val json = """{"id":"test","version":"1.0","environment":"*"}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals(ModSide.BOTH, result.side)
    }

    @Test
    fun `environment server maps to SERVER`() {
        val json = """{"id":"test","version":"1.0","environment":"server"}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals(ModSide.SERVER, result.side)
    }

    @Test
    fun `depends entries are required dependencies`() {
        val json = """{"id":"test","version":"1.0","depends":{"fabric-api":">=0.90.0","minecraft":"~1.20"}}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals(2, result.dependencies.count { it.required })
    }

    @Test
    fun `suggests entries are optional dependencies`() {
        val json = """{"id":"test","version":"1.0","suggests":{"modmenu":"*"}}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals(1, result.dependencies.size)
        assertFalse(result.dependencies[0].required)
        assertEquals("modmenu", result.dependencies[0].modId)
    }

    @Test
    fun `authors as array of strings`() {
        val json = """{"id":"test","version":"1.0","authors":["Alice","Bob"]}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals(listOf("Alice", "Bob"), result.authors)
    }

    @Test
    fun `authors as array of objects with name field`() {
        val json = """{"id":"test","version":"1.0","authors":[{"name":"Alice"},{"name":"Bob"}]}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals(listOf("Alice", "Bob"), result.authors)
    }

    @Test
    fun `jarPath is preserved`() {
        val path = Paths.get("/instances/test/mods/mymod.jar")
        val json = """{"id":"test","version":"1.0"}"""
        val result = FabricMetadataParser.parse(json, path)
        assertEquals(path, result.jarPath)
    }

    @Test
    fun `object-form entrypoints (Kotlin mods) parsed correctly`() {
        val json = """
        {
            "id": "kotlin-mod",
            "version": "1.0",
            "entrypoints": {
                "main": [
                    {"adapter": "kotlin", "value": "com.example.ExampleModKt"},
                    "com.example.JavaExampleMod"
                ]
            }
        }
        """.trimIndent()
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals(2, result.entryPoints.size)
        assertTrue(result.entryPoints.contains("com.example.ExampleModKt"))
        assertTrue(result.entryPoints.contains("com.example.JavaExampleMod"))
    }

    @Test
    fun `object-form mixins with config field parsed correctly`() {
        val json = """
        {
            "id": "test-mod",
            "version": "1.0",
            "mixins": [
                "simple.mixins.json",
                {"config": "client.mixins.json", "environment": "client"}
            ]
        }
        """.trimIndent()
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals(2, result.mixinConfigs.size)
        assertTrue(result.mixinConfigs.contains("simple.mixins.json"))
        assertTrue(result.mixinConfigs.contains("client.mixins.json"))
    }

    @Test
    fun `recommends entries are treated as optional dependencies`() {
        val json = """{"id":"test","version":"1.0","recommends":{"cool-mod":"*"}}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals(1, result.dependencies.size)
        assertFalse(result.dependencies[0].required)
        assertEquals("cool-mod", result.dependencies[0].modId)
    }

    @Test
    fun `missing id throws IllegalArgumentException`() {
        val json = """{"version":"1.0"}"""
        assertThrows(IllegalArgumentException::class.java) {
            FabricMetadataParser.parse(json, dummyPath)
        }
    }

    @Test
    fun `missing version throws IllegalArgumentException`() {
        val json = """{"id":"test"}"""
        assertThrows(IllegalArgumentException::class.java) {
            FabricMetadataParser.parse(json, dummyPath)
        }
    }

    @Test
    fun `malformed JSON throws parse exception`() {
        val json = """{"id":"test","version":"1.0","""  // truncated
        assertThrows(Exception::class.java) {
            FabricMetadataParser.parse(json, dummyPath)
        }
    }

    @Test
    fun `UTF-8 BOM at start of JSON is handled`() {
        // Windows Notepad sometimes adds BOM prefix \uFEFF
        val json = "\uFEFF" + """{"id":"bom-mod","version":"1.0"}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals("bom-mod", result.modId)
    }

    @Test
    fun `array-form version range in depends is handled`() {
        // Real Fabric API uses array form: `"depends":{"fabric":[">=0.9","<0.100"]}`
        val json = """
        {
          "id": "test",
          "version": "1.0",
          "depends": {
            "fabric-api": [">=0.90.0", "<0.100.0"]
          }
        }
        """.trimIndent()

        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals(1, result.dependencies.size)
        val dep = result.dependencies[0]
        assertEquals("fabric-api", dep.modId)
        // Joined version range
        assertNotNull(dep.versionRange)
        assertTrue(dep.versionRange!!.contains(">=0.90.0"))
        assertTrue(dep.versionRange.contains("<0.100.0"))
    }

    @Test
    fun `empty depends object does not crash`() {
        val json = """{"id":"test","version":"1.0","depends":{}}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertTrue(result.dependencies.isEmpty())
    }

    @Test
    fun `empty mixins array produces empty mixinConfigs`() {
        val json = """{"id":"test","version":"1.0","mixins":[]}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertTrue(result.mixinConfigs.isEmpty())
    }

    @Test
    fun `modId is normalized to lowercase`() {
        // Flag #28: Fabric spec says modId should be lowercase but real mods don't always comply.
        // Parser normalizes to prevent "JEI" vs "jei" false-positive missing deps.
        val json = """{"id":"MyMod","version":"1.0"}"""
        val result = FabricMetadataParser.parse(json, dummyPath)
        assertEquals("mymod", result.modId)
    }
}
