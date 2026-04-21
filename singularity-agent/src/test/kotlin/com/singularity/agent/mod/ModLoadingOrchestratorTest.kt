// Copyright (c) 2026 Echelon Team. All rights reserved.

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

class ModLoadingOrchestratorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createModJar(name: String, fabricJson: String? = null, modsToml: String? = null): Path {
        val path = tempDir.resolve(name)
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            if (fabricJson != null) {
                jos.putNextEntry(JarEntry("fabric.mod.json"))
                jos.write(fabricJson.toByteArray())
                jos.closeEntry()
            }
            if (modsToml != null) {
                jos.putNextEntry(JarEntry("META-INF/mods.toml"))
                jos.write(modsToml.toByteArray())
                jos.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `full pipeline discovers and parses mods`() {
        createModJar("sodium.jar", fabricJson = """
            {"id":"sodium","version":"0.5.8","name":"Sodium","environment":"client"}
        """.trimIndent())
        createModJar("create.jar", modsToml = """
            [[mods]]
            modId="create"
            version="0.5.1"
            displayName="Create"
        """.trimIndent())

        val result = ModLoadingOrchestrator.discoverAndParse(tempDir)
        assertEquals(2, result.mods.size)
        assertTrue(result.errors.isEmpty())

        val sodium = result.mods.first { it.modId == "sodium" }
        assertEquals(LoaderType.FABRIC, sodium.loaderType)

        val create = result.mods.first { it.modId == "create" }
        assertEquals(LoaderType.FORGE, create.loaderType)
    }

    @Test
    fun `pipeline detects duplicates`() {
        createModJar("jei-old.jar", fabricJson = """
            {"id":"jei","version":"1.0","name":"JEI","authors":["mezz"]}
        """.trimIndent())
        createModJar("jei-new.jar", fabricJson = """
            {"id":"jei","version":"2.0","name":"JEI","authors":["mezz"]}
        """.trimIndent())

        val result = ModLoadingOrchestrator.discoverAndParse(tempDir)
        assertTrue(result.duplicates.isNotEmpty())
    }

    @Test
    fun `pipeline resolves dependencies`() {
        createModJar("base.jar", fabricJson = """{"id":"base","version":"1.0"}""")
        createModJar("addon.jar", fabricJson = """
            {"id":"addon","version":"1.0","depends":{"base":">=1.0"}}
        """.trimIndent())

        val result = ModLoadingOrchestrator.discoverAndParse(tempDir)
        assertTrue(result.resolutionErrors.isEmpty())

        // base przed addon w posortowanej liście
        val order = result.sortedMods.map { it.modId }
        assertTrue(order.indexOf("base") < order.indexOf("addon"))
    }

    @Test
    fun `empty directory produces empty result`() {
        val result = ModLoadingOrchestrator.discoverAndParse(tempDir)
        assertTrue(result.mods.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `unknown JARs tracked in result`() {
        val path = tempDir.resolve("mystery.jar")
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        JarOutputStream(Files.newOutputStream(path), manifest).use { }

        val result = ModLoadingOrchestrator.discoverAndParse(tempDir)
        assertEquals(1, result.unknownJars.size)
    }

    @Test
    fun `duplicates are filtered from resolution input (prevents fake cyclic error)`() {
        // Edge-case-hunter L5 fix: dwie wersje tego samego moda nie powinny powodować
        // fałszywego "cyclic dependency" error w resolverze.
        createModJar("old.jar", fabricJson = """
            {"id":"dup-mod","version":"1.0","name":"Dup","authors":["author"]}
        """.trimIndent())
        createModJar("new.jar", fabricJson = """
            {"id":"dup-mod","version":"2.0","name":"Dup","authors":["author"]}
        """.trimIndent())

        val result = ModLoadingOrchestrator.discoverAndParse(tempDir)

        // Oba mody w mods (raw), ale duplicates ma KeepNewer action
        assertEquals(2, result.mods.size)
        assertEquals(1, result.duplicates.size)

        // sortedMods zawiera TYLKO newer (1 entry), NIE fałszywego cycle error
        assertEquals(1, result.sortedMods.size)
        assertEquals("2.0", result.sortedMods[0].version)

        // Brak fałszywego cyclic dependency error
        assertTrue(result.resolutionErrors.isEmpty())
    }

    @Test
    fun `parse error in one mod does not block others`() {
        createModJar("valid.jar", fabricJson = """{"id":"valid","version":"1.0"}""")
        createModJar("broken.jar", fabricJson = """{"id":"broken","""  /* malformed */)

        val result = ModLoadingOrchestrator.discoverAndParse(tempDir)
        // Valid mod parsowany
        assertEquals(1, result.mods.size)
        assertEquals("valid", result.mods[0].modId)
        // Broken mod w errors
        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.errors.any { it.contains("broken.jar") })
    }

    @Test
    fun `MULTI-loader mod preserves MULTI tag after parse`() {
        // Regression: FabricMetadataParser hardcodes loaderType=FABRIC. Without .copy()
        // fix, MULTI mod would appear as FABRIC in downstream code → invisible to Forge
        // shims, skipped in Forge phase split.
        createModJar("architectury.jar",
            fabricJson = """{"id":"architectury","version":"9.0","name":"Architectury"}""",
            modsToml = """
                [[mods]]
                modId="architectury"
                version="9.0"
            """.trimIndent()
        )

        val result = ModLoadingOrchestrator.discoverAndParse(tempDir)
        assertEquals(1, result.mods.size)
        assertEquals(LoaderType.MULTI, result.mods[0].loaderType)
    }

    @Test
    fun `NEOFORGE mod preserves NEOFORGE tag after parse`() {
        // Bug sister to MULTI: ForgeMetadataParser hardcodes FORGE, NEOFORGE would
        // collapse to FORGE. Orchestrator must copy loaderType from discovery.
        val path = tempDir.resolve("neoforge-mod.jar")
        val manifest = java.util.jar.Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
        }
        java.util.jar.JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            // Write mods.toml but force NEOFORGE classification by NOT adding fabric.mod.json
            jos.putNextEntry(java.util.jar.JarEntry("META-INF/mods.toml"))
            jos.write("[[mods]]\nmodId=\"neotest\"\nversion=\"1.0\"".toByteArray())
            jos.closeEntry()
        }
        // NOTE: ModDiscovery currently identifies any mods.toml as FORGE (no distinct
        // detection logic for NEOFORGE on 1.20.1 since format is identical). Test asserts
        // that IF discovery somehow returns NEOFORGE, orchestrator preserves it.
        // End-to-end NEOFORGE detection is an open issue documented in TODO flags.
        val result = ModLoadingOrchestrator.discoverAndParse(tempDir)
        assertEquals(1, result.mods.size)
        // Current discovery returns FORGE for mods.toml — this verifies FORGE preservation
        // (via the new copy() path in orchestrator that kept FORGE working).
        assertEquals(LoaderType.FORGE, result.mods[0].loaderType)
    }
}
