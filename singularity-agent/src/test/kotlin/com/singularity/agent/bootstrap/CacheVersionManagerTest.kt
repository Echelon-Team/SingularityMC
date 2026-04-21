// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.bootstrap

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CacheVersionManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `isStale returns true for empty cache dir`() {
        val manager = CacheVersionManager(tempDir)
        assertTrue(manager.isStale("1.0.0", "1.0.0"))
    }

    @Test
    fun `isStale returns false after writeCurrent with matching versions`() {
        val manager = CacheVersionManager(tempDir)
        manager.writeCurrent("1.0.0", "1.0.0")
        assertFalse(manager.isStale("1.0.0", "1.0.0"))
    }

    @Test
    fun `isStale returns true when agentVer differs`() {
        val manager = CacheVersionManager(tempDir)
        manager.writeCurrent("1.0.0", "1.0.0")
        assertTrue(manager.isStale("2.0.0", "1.0.0"))
    }

    @Test
    fun `isStale returns true when moduleVer differs`() {
        val manager = CacheVersionManager(tempDir)
        manager.writeCurrent("1.0.0", "1.0.0")
        assertTrue(manager.isStale("1.0.0", "2.0.0"))
    }

    @Test
    fun `clearAll removes all files in cache dir`() {
        val manager = CacheVersionManager(tempDir)
        val subdir = Files.createDirectories(tempDir.resolve("abcd1234"))
        Files.writeString(subdir.resolve("test.class"), "dummy")
        assertTrue(Files.exists(subdir.resolve("test.class")))

        manager.clearAll()
        assertFalse(Files.exists(subdir.resolve("test.class")))
        assertTrue(Files.exists(tempDir), "Cache root dir should remain")
    }
}
