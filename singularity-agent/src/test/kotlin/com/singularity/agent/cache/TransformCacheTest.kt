// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.cache

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TransformCacheTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `put and get round-trip`() {
        val cache = TransformCache(tempDir)
        val testBytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

        cache.put("abc123", "net/minecraft/world/level/Level", testBytes)
        val retrieved = cache.get("abc123", "net/minecraft/world/level/Level")

        assertNotNull(retrieved)
        assertArrayEquals(testBytes, retrieved)
    }

    @Test
    fun `get returns null for missing entry`() {
        val cache = TransformCache(tempDir)
        assertNull(cache.get("nonexistent", "some/Class"))
    }

    @Test
    fun `get returns null for wrong jar hash`() {
        val cache = TransformCache(tempDir)
        cache.put("hash1", "some/Class", byteArrayOf(1, 2, 3))
        assertNull(cache.get("hash2", "some/Class"))
    }

    @Test
    fun `invalidate removes entries for jar hash`() {
        val cache = TransformCache(tempDir)
        cache.put("hash1", "ClassA", byteArrayOf(1))
        cache.put("hash1", "ClassB", byteArrayOf(2))
        cache.put("hash2", "ClassC", byteArrayOf(3))

        cache.invalidate("hash1")

        assertNull(cache.get("hash1", "ClassA"))
        assertNull(cache.get("hash1", "ClassB"))
        assertNotNull(cache.get("hash2", "ClassC"))
    }

    @Test
    fun `cleanup removes hashes not in active set`() {
        val cache = TransformCache(tempDir)
        cache.put("active", "ClassA", byteArrayOf(1))
        cache.put("stale", "ClassB", byteArrayOf(2))

        cache.cleanup(activeJarHashes = setOf("active"))

        assertNotNull(cache.get("active", "ClassA"))
        assertNull(cache.get("stale", "ClassB"))
    }

    @Test
    fun `size counts cached entries`() {
        val cache = TransformCache(tempDir)
        assertEquals(0, cache.size)

        cache.put("h1", "ClassA", byteArrayOf(1))
        cache.put("h1", "ClassB", byteArrayOf(2))
        assertEquals(2, cache.size)
    }
}
