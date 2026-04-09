package com.singularity.agent.cache

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CacheKeyTest {

    @Test
    fun `dirKey produces 16 hex chars`() {
        val key = CacheKey.dirKey("1.0.0", "1.0.0", "abcd1234567890ab")
        assertEquals(16, key.length)
        assertTrue(key.matches(Regex("^[0-9a-f]{16}$")))
    }

    @Test
    fun `dirKey is deterministic for same inputs`() {
        val key1 = CacheKey.dirKey("1.0.0", "1.0.0", "abcd")
        val key2 = CacheKey.dirKey("1.0.0", "1.0.0", "abcd")
        assertEquals(key1, key2)
    }

    @Test
    fun `dirKey changes with agent version`() {
        val key1 = CacheKey.dirKey("1.0.0", "1.0.0", "abcd")
        val key2 = CacheKey.dirKey("1.0.1", "1.0.0", "abcd")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `dirKey changes with module version`() {
        val key1 = CacheKey.dirKey("1.0.0", "1.0.0", "abcd")
        val key2 = CacheKey.dirKey("1.0.0", "2.0.0", "abcd")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `dirKey changes with jar hash`() {
        val key1 = CacheKey.dirKey("1.0.0", "1.0.0", "abcd")
        val key2 = CacheKey.dirKey("1.0.0", "1.0.0", "ef01")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `dirKey uses ThreadLocal MessageDigest (no allocation per call)`() {
        // Regression: plan v1 alokowal MessageDigest.getInstance per transform —
        // 230us * 30k klas = 7s startup time wasted. Ten test nie mierzy czasu absolut
        // ale potwierdza ze 10k wywolan idzie szybko (jesli alokacja per-call byla,
        // bedzie wolne).
        val start = System.nanoTime()
        repeat(10_000) {
            CacheKey.dirKey("1.0.0", "1.0.0", "abcd1234567890ab")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // 10k operacji z ThreadLocal digest < 500ms (realnie <20ms)
        assertTrue(elapsedMs < 500, "10k dirKey calls took ${elapsedMs}ms — suggests per-call allocation")
    }
}
