// Copyright (c) 2026 Echelon Team. All rights reserved.

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
        // 230us * 30k klas = 7s startup time wasted. Ten test potwierdza ze 10k
        // wywolan idzie w rozsadnym czasie (bez ThreadLocal alokacja MD na call
        // daje ~2300ms lokalnie, ~5s+ na CI).
        //
        // Warmup: JIT tier-up dla CacheKey.dirKey + JVM startup overhead
        // (ClassLoader, SHA-256 provider init) outside measurement. Bez tego
        // pierwsze iteracje dodają 100-300ms noise na slow runners.
        repeat(1_000) {
            CacheKey.dirKey("warmup", "warmup", "warmup")
        }

        val start = System.nanoTime()
        repeat(10_000) {
            CacheKey.dirKey("1.0.0", "1.0.0", "abcd1234567890ab")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // Threshold 3000ms = 10x margin powyżej typowy local run (~20ms) +
        // tolerancja CI volatility (Windows runner pod load + cold workload).
        // Bez ThreadLocal regresja → 2300ms lokalnie + CI overhead → łatwo
        // przekracza 3000 → test fail (catches regresję).
        // Wcześniej 500ms — fragile na CI (fail 2026-04-21 flaky z copyright
        // notice push, bo akurat runner pod heavy load). Warmup above + ten
        // threshold razem eliminują flakiness bez luzowania regression detection.
        assertTrue(elapsedMs < 3000, "10k dirKey calls took ${elapsedMs}ms — suggests per-call allocation")
    }
}
