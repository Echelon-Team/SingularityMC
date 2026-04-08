package com.singularity.agent.module

import com.singularity.common.contracts.ModuleDescriptorData
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class ModuleLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createModuleJar(
        dir: Path,
        fileName: String,
        descriptor: ModuleDescriptorData
    ): Path {
        val jarPath = dir.resolve(fileName)
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
        }
        JarOutputStream(Files.newOutputStream(jarPath), manifest).use { jos ->
            jos.putNextEntry(JarEntry("singularity-module.json"))
            val json = Json.encodeToString(descriptor)
            jos.write(json.toByteArray())
            jos.closeEntry()
        }
        return jarPath
    }

    private val testDescriptor = ModuleDescriptorData(
        moduleId = "compat-1.20.1",
        moduleVersion = "1.0.0",
        minecraftVersion = "1.20.1",
        supportedLoaders = setOf("fabric", "forge", "neoforge"),
        requiredContracts = setOf("metadata", "remapping")
    )

    @Test
    fun `findModule returns module JAR for matching MC version`() {
        val modulesDir = tempDir.resolve("modules")
        Files.createDirectories(modulesDir)
        val jarPath = createModuleJar(modulesDir, "singularitymc-compat-1.20.1-v1.0.0.jar", testDescriptor)

        val result = ModuleLoader.findModule(modulesDir, "1.20.1")
        assertNotNull(result)
        assertEquals(jarPath, result)
    }

    @Test
    fun `findModule returns null when no module for MC version`() {
        val modulesDir = tempDir.resolve("modules")
        Files.createDirectories(modulesDir)
        createModuleJar(modulesDir, "singularitymc-compat-1.20.1-v1.0.0.jar", testDescriptor)

        val result = ModuleLoader.findModule(modulesDir, "1.19.2")
        assertNull(result)
    }

    @Test
    fun `findModule returns null when directory is empty`() {
        val modulesDir = tempDir.resolve("modules")
        Files.createDirectories(modulesDir)

        val result = ModuleLoader.findModule(modulesDir, "1.20.1")
        assertNull(result)
    }

    @Test
    fun `findModule returns null when directory does not exist`() {
        val modulesDir = tempDir.resolve("nonexistent")

        val result = ModuleLoader.findModule(modulesDir, "1.20.1")
        assertNull(result)
    }

    @Test
    fun `findModule picks newest version when multiple modules exist`() {
        val modulesDir = tempDir.resolve("modules")
        Files.createDirectories(modulesDir)
        createModuleJar(modulesDir, "singularitymc-compat-1.20.1-v1.0.0.jar", testDescriptor)
        createModuleJar(modulesDir, "singularitymc-compat-1.20.1-v2.0.0.jar",
            testDescriptor.copy(moduleVersion = "2.0.0"))

        val result = ModuleLoader.findModule(modulesDir, "1.20.1")
        assertNotNull(result)
        assertTrue(result.toString().contains("v2.0.0"))
    }

    @Test
    fun `loadModule parses descriptor from JAR`() {
        val modulesDir = tempDir.resolve("modules")
        Files.createDirectories(modulesDir)
        val jarPath = createModuleJar(modulesDir, "singularitymc-compat-1.20.1-v1.0.0.jar", testDescriptor)

        // use {} zamyka JarFile po asercjach. Bez tego na Windows TempDir cleanup
        // fail'uje bo open JarFile handle blokuje delete (edge-case-hunter flag #3).
        ModuleLoader.loadModule(jarPath).use { loaded ->
            assertEquals("compat-1.20.1", loaded.descriptor.moduleId)
            assertEquals("1.20.1", loaded.descriptor.minecraftVersion)
        }
    }

    @Test
    fun `loadModule provides access to JAR entries`() {
        val modulesDir = tempDir.resolve("modules")
        Files.createDirectories(modulesDir)
        val jarPath = createModuleJar(modulesDir, "singularitymc-compat-1.20.1-v1.0.0.jar", testDescriptor)

        ModuleLoader.loadModule(jarPath).use { loaded ->
            assertTrue(loaded.hasEntry("singularity-module.json"))
            assertFalse(loaded.hasEntry("nonexistent.txt"))
        }
    }
}
