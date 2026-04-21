// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.bootstrap

import com.singularity.agent.classloader.JarRegistry
import com.singularity.agent.classloader.SingularityClassLoader
import com.singularity.agent.mod.ModInfo
import com.singularity.agent.mod.ModInitializer
import com.singularity.agent.remapping.InheritanceTree
import com.singularity.agent.remapping.MappingTable
import com.singularity.agent.remapping.RemappingEngine
import com.singularity.agent.registry.SingularityModRegistry
import com.singularity.common.model.LoaderType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class ModBootstrapTest {

    @TempDir
    lateinit var tempDir: Path

    private fun makeEmptyClassLoader(): SingularityClassLoader {
        val tree = InheritanceTree()
        val empty = MappingTable("empty", emptyMap(), emptyMap(), emptyMap())
        val engine = RemappingEngine(empty, empty, empty, tree)
        return SingularityClassLoader(
            parent = ClassLoader.getSystemClassLoader(),
            jarRegistry = JarRegistry(),
            remappingEngine = engine,
            transformFunction = { _, bytes -> bytes }
        )
    }

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

    private fun stubInitializer(executionLog: MutableList<String> = mutableListOf()): ModInitializer =
        ModInitializer(
            onPhase1a = { executionLog.add("1a:${it.modId}") },
            onPhase1b = { executionLog.add("1b:${it.modId}") },
            onPhase2 = { executionLog.add("2:${it.modId}") },
            onPhase3 = { executionLog.add("3:${it.modId}") },
            onPhase4 = { executionLog.add("4:complete") }
        )

    @Test
    fun `empty mods directory — registry stays empty, mixin init still called`() {
        // Mixin init musi być wywoływany ZAWSZE (nawet bez modów) żeby environment
        // był gotowy. Verified against FabricMixinBootstrap pattern.
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)
        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()
        var mixinBootstrapCalled = false
        val injectedConfigs = mutableListOf<String>()

        val result = ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(),
            mixinConfigInjector = { injectedConfigs.add(it) },
            mixinBootstrapInit = { mixinBootstrapCalled = true }
        )

        assertEquals(0, result.registeredCount)
        assertEquals(0, registry.size)
        assertTrue(result.loadedMixinConfigs.isEmpty())
        assertTrue(injectedConfigs.isEmpty(), "No configs to inject when no mods")
        assertTrue(mixinBootstrapCalled, "MixinBootstrap.init() should be called UNCONDITIONALLY")
    }

    @Test
    fun `mixin init called BEFORE addConfiguration (correct order per Fabric pattern)`() {
        // Verify order: MixinBootstrap.init() PRZED Mixins.addConfiguration().
        // Inverted order breaks real Mixin 0.8.x contract (init sets up environment,
        // addConfiguration registers into that environment).
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)

        createModJarInDir(modsDir, "sodium.jar", fabricJson = """
            {"id":"sodium","version":"1.0","mixins":["sodium.mixins.json"]}
        """.trimIndent())

        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()

        val orderLog = mutableListOf<String>()

        ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(),
            mixinConfigInjector = { orderLog.add("addConfig:$it") },
            mixinBootstrapInit = { orderLog.add("init") }
        )

        // init MUSI być przed wszystkimi addConfig
        val initIdx = orderLog.indexOf("init")
        val firstConfigIdx = orderLog.indexOfFirst { it.startsWith("addConfig:") }
        assertTrue(initIdx >= 0, "init should be called")
        assertTrue(firstConfigIdx >= 0, "addConfig should be called")
        assertTrue(initIdx < firstConfigIdx, "init MUST precede addConfiguration (Fabric pattern)")
    }

    @Test
    fun `fabric mod discovered + registered + mixin configs collected`() {
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)
        val modsDirFixed = modsDir.toFile()
        modsDirFixed.mkdirs()

        // Tworz mod w mods/ folder
        createModJarInDir(modsDir, "sodium.jar", fabricJson = """
            {
              "id": "sodium",
              "version": "0.5.8",
              "name": "Sodium",
              "environment": "client",
              "mixins": ["sodium.mixins.json"]
            }
        """.trimIndent())

        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()
        val injectedConfigs = mutableListOf<String>()

        val result = ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(),
            mixinConfigInjector = { injectedConfigs.add(it) },
            mixinBootstrapInit = { }
        )

        assertEquals(1, result.registeredCount)
        assertEquals(1, registry.size)
        assertNotNull(registry.getById("sodium"))
        assertEquals(LoaderType.FABRIC, registry.getById("sodium")!!.loaderType)
        assertTrue(result.loadedMixinConfigs.contains("sodium.mixins.json"))
        assertTrue(injectedConfigs.contains("sodium.mixins.json"))
    }

    @Test
    fun `multiple mods with dependencies — registered in topological order`() {
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)

        createModJarInDir(modsDir, "base-lib.jar", fabricJson = """{"id":"base-lib","version":"1.0"}""")
        createModJarInDir(modsDir, "addon.jar", fabricJson = """
            {"id":"addon","version":"1.0","depends":{"base-lib":">=1.0"}}
        """.trimIndent())

        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()

        val result = ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(),
            mixinConfigInjector = { },
            mixinBootstrapInit = { }
        )

        assertEquals(2, result.registeredCount)
        assertTrue(registry.isLoaded("base-lib"))
        assertTrue(registry.isLoaded("addon"))
        // Topological order w discoveryResult.sortedMods — base-lib PRZED addon
        val order = result.discoveryResult.sortedMods.map { it.modId }
        assertTrue(order.indexOf("base-lib") < order.indexOf("addon"))
    }

    @Test
    fun `forge mod goes through phase 1a but not 1b`() {
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)

        createModJarInDir(modsDir, "create.jar", modsToml = """
            [[mods]]
            modId="create"
            version="0.5.1"
            displayName="Create"
        """.trimIndent())

        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()
        val executionLog = mutableListOf<String>()

        ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(executionLog),
            mixinConfigInjector = { },
            mixinBootstrapInit = { }
        )

        // Forge mod: phase 1a, 2, 3 — NOT 1b
        assertTrue(executionLog.contains("1a:create"), "Forge mod should go through phase 1a")
        assertFalse(executionLog.contains("1b:create"), "Forge mod should NOT go through phase 1b")
        assertTrue(executionLog.contains("2:create"))
        assertTrue(executionLog.contains("3:create"))
        assertTrue(executionLog.contains("4:complete"))
    }

    @Test
    fun `fabric mod goes through phase 1b but not 1a`() {
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)

        createModJarInDir(modsDir, "sodium.jar", fabricJson = """{"id":"sodium","version":"1.0"}""")

        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()
        val executionLog = mutableListOf<String>()

        ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(executionLog),
            mixinConfigInjector = { },
            mixinBootstrapInit = { }
        )

        assertFalse(executionLog.contains("1a:sodium"))
        assertTrue(executionLog.contains("1b:sodium"))
    }

    @Test
    fun `duplicate mixin configs are deduplicated`() {
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)

        // Dwa mody z TAKIM SAMYM mixin config (rzadkie ale teoretycznie możliwe)
        createModJarInDir(modsDir, "mod-a.jar", fabricJson = """
            {"id":"mod-a","version":"1.0","mixins":["shared.mixins.json"]}
        """.trimIndent())
        createModJarInDir(modsDir, "mod-b.jar", fabricJson = """
            {"id":"mod-b","version":"1.0","mixins":["shared.mixins.json"]}
        """.trimIndent())

        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()
        val injectedConfigs = mutableListOf<String>()

        val result = ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(),
            mixinConfigInjector = { injectedConfigs.add(it) },
            mixinBootstrapInit = { }
        )

        // Każdy config wpisany tylko RAZ (LinkedHashSet)
        assertEquals(1, result.loadedMixinConfigs.count { it == "shared.mixins.json" })
        assertEquals(1, injectedConfigs.count { it == "shared.mixins.json" })
    }

    @Test
    fun `mixinBootstrapInit called when configs present`() {
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)

        createModJarInDir(modsDir, "sodium.jar", fabricJson = """
            {"id":"sodium","version":"1.0","mixins":["sodium.mixins.json"]}
        """.trimIndent())

        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()
        var mixinInitCalled = false

        ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(),
            mixinConfigInjector = { },
            mixinBootstrapInit = { mixinInitCalled = true }
        )

        assertTrue(mixinInitCalled, "MixinBootstrap.init() should be called when configs present")
    }

    @Test
    fun `mixinConfigInjector exception does not abort loading`() {
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)

        createModJarInDir(modsDir, "mod-a.jar", fabricJson = """
            {"id":"mod-a","version":"1.0","mixins":["broken.mixins.json"]}
        """.trimIndent())
        createModJarInDir(modsDir, "mod-b.jar", fabricJson = """{"id":"mod-b","version":"1.0"}""")

        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()

        val result = ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(),
            mixinConfigInjector = { throw RuntimeException("Cannot register $it") },
            mixinBootstrapInit = { }
        )

        // Obaj mody zostali zarejestrowani mimo błędu w mixin injection
        assertEquals(2, result.registeredCount)
        assertTrue(registry.isLoaded("mod-a"))
        assertTrue(registry.isLoaded("mod-b"))
    }

    @Test
    fun `nonexistent mods directory produces empty result`() {
        val nonexistent = tempDir.resolve("does-not-exist")
        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()

        val result = ModBootstrap.loadMods(
            modsDir = nonexistent,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(),
            mixinConfigInjector = { },
            mixinBootstrapInit = { }
        )

        assertEquals(0, result.registeredCount)
    }

    @Test
    fun `multi-loader mod goes through BOTH Forge phase 1a AND Fabric phase 1b`() {
        // Per spec 5A.5 + 5A.7: MULTI mod is visible to both loader shims → gets both
        // Forge phases (1a/2/3) AND Fabric phase 1b. Previous version only routed to
        // Fabric → Forge integration lost.
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)

        createModJarInDir(modsDir, "architectury.jar",
            fabricJson = """{"id":"architectury","version":"9.0"}""",
            modsToml = """
                [[mods]]
                modId="architectury"
                version="9.0"
            """.trimIndent()
        )

        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()
        val executionLog = mutableListOf<String>()

        ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(executionLog),
            mixinConfigInjector = { },
            mixinBootstrapInit = { }
        )

        // MULTI mod dostaje BOTH Forge phases AND Fabric phase 1b
        assertTrue(executionLog.contains("1a:architectury"), "MULTI should get Forge phase 1a")
        assertTrue(executionLog.contains("1b:architectury"), "MULTI should get Fabric phase 1b")
        assertTrue(executionLog.contains("2:architectury"), "MULTI should get Forge phase 2")
        assertTrue(executionLog.contains("3:architectury"), "MULTI should get Forge phase 3")
    }

    @Test
    fun `mixinBootstrapInit throws does NOT abort loading`() {
        // If MixinBootstrap.init() fails, we log error but continue — registry still
        // populated for mods that managed to addModJar. Matches existing pattern for
        // other failures (catch + log).
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)

        createModJarInDir(modsDir, "sodium.jar", fabricJson = """
            {"id":"sodium","version":"1.0","mixins":["sodium.mixins.json"]}
        """.trimIndent())

        val registry = SingularityModRegistry()
        val classLoader = makeEmptyClassLoader()

        val result = ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = classLoader::addModJar,
            registry = registry,
            initializer = stubInitializer(),
            mixinConfigInjector = { },
            mixinBootstrapInit = { throw RuntimeException("Mixin bootstrap failed") }
        )

        // Registry still populated (mod registered even if Mixin init threw)
        assertEquals(1, result.registeredCount)
        assertTrue(registry.isLoaded("sodium"))
    }

    @Test
    fun `failed addModJar skips mod registration and phase init`() {
        // Edge-case-hunter U-NEW-3: if addModJar throws, the mod's classes aren't loadable.
        // Registering it would expose a broken mod to shims. Must skip.
        val modsDir = tempDir.resolve("mods")
        Files.createDirectories(modsDir)

        createModJarInDir(modsDir, "good.jar", fabricJson = """{"id":"good","version":"1.0"}""")
        createModJarInDir(modsDir, "bad.jar", fabricJson = """{"id":"bad","version":"1.0"}""")

        val registry = SingularityModRegistry()
        val realLoader = makeEmptyClassLoader()
        val executionLog = mutableListOf<String>()

        // Failing addModJar lambda: throws na specific JAR
        val failingAddJar: (java.nio.file.Path) -> String = { path ->
            if (path.fileName.toString() == "bad.jar") {
                throw RuntimeException("Simulated classloader failure")
            }
            realLoader.addModJar(path)
        }

        val result = ModBootstrap.loadMods(
            modsDir = modsDir,
            addModJar = failingAddJar,
            registry = registry,
            initializer = stubInitializer(executionLog),
            mixinConfigInjector = { },
            mixinBootstrapInit = { }
        )

        // Only "good" registered, "bad" skipped
        assertEquals(1, result.registeredCount)
        assertTrue(registry.isLoaded("good"))
        assertFalse(registry.isLoaded("bad"), "Failed JAR should NOT be registered")

        // "bad" also skipped in phase init
        assertFalse(executionLog.any { it.contains("bad") }, "Failed mod should not run phases")
        assertTrue(executionLog.contains("1b:good"))
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    private fun createModJarInDir(
        dir: Path,
        name: String,
        fabricJson: String? = null,
        modsToml: String? = null
    ): Path {
        val path = dir.resolve(name)
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
}
