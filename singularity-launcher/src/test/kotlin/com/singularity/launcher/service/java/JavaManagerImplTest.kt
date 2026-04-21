// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service.java

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JavaManagerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun unusedClient(): HttpClient = HttpClient(MockEngine { error("HTTP not expected in this test") }) {
        install(ContentNegotiation) { json(json) }
    }

    private fun manager(client: HttpClient = unusedClient()) =
        JavaManagerImpl(javaRoot = tempDir, httpClient = client)

    @Test
    fun `getJavaFor 1_16_5 returns 8`() {
        val mgr = manager()
        assertEquals(8, mgr.getJavaFor("1.16.5"))
    }

    @Test
    fun `getJavaFor 1_17 returns 17`() {
        val mgr = manager()
        assertEquals(17, mgr.getJavaFor("1.17"))
    }

    @Test
    fun `getJavaFor 1_20_1 returns 17`() {
        val mgr = manager()
        assertEquals(17, mgr.getJavaFor("1.20.1"))
    }

    @Test
    fun `getJavaFor 1_20_4 returns 17`() {
        val mgr = manager()
        assertEquals(17, mgr.getJavaFor("1.20.4"))
    }

    @Test
    fun `getJavaFor 1_20_5 returns 21`() {
        val mgr = manager()
        assertEquals(21, mgr.getJavaFor("1.20.5"))
    }

    @Test
    fun `getJavaFor 1_21 returns 21`() {
        val mgr = manager()
        assertEquals(21, mgr.getJavaFor("1.21"))
    }

    @Test
    fun `getJavaFor unknown version throws`() {
        val mgr = manager()
        assertThrows(IllegalArgumentException::class.java) {
            mgr.getJavaFor("invalid-version")
        }
    }

    @Test
    fun `isInstalled false when no java in root`() {
        val mgr = manager()
        assertFalse(mgr.isInstalled(17))
    }

    @Test
    fun `listInstalledVersions empty when no dirs`() {
        val mgr = manager()
        assertEquals(emptyList<Int>(), mgr.listInstalledVersions())
    }

    @Test
    fun `listInstalledVersions returns existing versions`() {
        // Create fake java directories
        val javaExe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        Files.createDirectories(tempDir.resolve("java-17").resolve("bin"))
        Files.createFile(tempDir.resolve("java-17").resolve("bin").resolve(javaExe))
        Files.createDirectories(tempDir.resolve("java-21").resolve("bin"))
        Files.createFile(tempDir.resolve("java-21").resolve("bin").resolve(javaExe))

        val mgr = manager()
        val installed = mgr.listInstalledVersions().sorted()
        assertEquals(listOf(17, 21), installed)
    }
}
