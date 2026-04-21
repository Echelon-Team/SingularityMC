// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.screenshots

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.nio.file.Files
import java.nio.file.Path

/**
 * LRU cache dla thumbnail ImageBitmaps — prevent OOM przy 1000+ screenshots (#23 edge-case CRITICAL).
 *
 * **Design:** LinkedHashMap z access-order = true. Każdy `get` przesuwa key na koniec
 * (MRU). Po przekroczeniu `maxSize` usuwa head (LRU).
 *
 * **Thread safety:** NIE thread-safe — wołać tylko z Dispatchers.Main/Swing.
 *
 * **Max size:** 100 thumbnails. Średni thumbnail 100KB → 10MB heap usage limit.
 */
class ScreenshotLruCache(private val maxSize: Int = 100) {

    private val cache = object : LinkedHashMap<Path, ImageBitmap>(maxSize + 1, 1f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Path, ImageBitmap>): Boolean {
            return size > maxSize
        }
    }

    fun get(path: Path): ImageBitmap? = cache[path]

    /**
     * Load thumbnail z disk i cache. Jeśli już w cache, zwraca z cache (fast path).
     * Inaczej reads PNG z disk, converts do ImageBitmap, cachuje i zwraca.
     */
    fun loadOrCache(path: Path): ImageBitmap? {
        cache[path]?.let { return it }
        if (!Files.exists(path)) return null
        return try {
            val bytes = Files.readAllBytes(path)
            val awtImage = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                ?: return null
            val bitmap = awtImage.toComposeImageBitmap()
            cache[path] = bitmap
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun invalidate(path: Path) {
        cache.remove(path)
    }

    fun clear() = cache.clear()

    val size: Int get() = cache.size

    fun contains(path: Path): Boolean = cache.containsKey(path)
}
