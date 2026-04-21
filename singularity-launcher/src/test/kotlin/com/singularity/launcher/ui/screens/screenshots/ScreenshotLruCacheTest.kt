// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.screenshots

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path

class ScreenshotLruCacheTest {

    @Test
    fun `empty cache returns null`() {
        val cache = ScreenshotLruCache(maxSize = 10)
        assertNull(cache.get(Path.of("/nonexistent.png")))
        assertEquals(0, cache.size)
    }

    @Test
    fun `loadOrCache returns null for nonexistent path`() {
        val cache = ScreenshotLruCache(maxSize = 10)
        val bitmap = cache.loadOrCache(Path.of("/tmp/definitely-not-there-singularitymc-test.png"))
        assertNull(bitmap)
        assertEquals(0, cache.size)
    }

    @Test
    fun `contains returns false for non-cached path`() {
        val cache = ScreenshotLruCache(maxSize = 10)
        assertFalse(cache.contains(Path.of("/tmp/test.png")))
    }

    @Test
    fun `invalidate removes specific entry (no-op if missing)`() {
        val cache = ScreenshotLruCache(maxSize = 10)
        val path = Path.of("/tmp/test.png")
        cache.invalidate(path)  // no-op if not present
        assertFalse(cache.contains(path))
        assertEquals(0, cache.size)
    }

    @Test
    fun `clear empties cache`() {
        val cache = ScreenshotLruCache(maxSize = 10)
        cache.clear()
        assertEquals(0, cache.size)
    }
}
