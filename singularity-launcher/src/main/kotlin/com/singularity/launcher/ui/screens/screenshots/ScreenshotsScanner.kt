package com.singularity.launcher.ui.screens.screenshots

import com.singularity.launcher.service.InstanceManager
import java.nio.file.Files
import kotlin.io.path.name

/**
 * Skanuje wszystkie instancje w poszukiwaniu plików PNG w `<instance>/minecraft/screenshots/`.
 *
 * **Performance note:** nie ładuje ImageBitmap — tylko metadata (path, modification time, size).
 * Full image loading jest lazy przez `ScreenshotLruCache.loadOrCache`.
 */
object ScreenshotsScanner {

    /**
     * Scan wszystkich instancji, zbierz wszystkie PNG files w screenshots/.
     * Returns sorted compound (instance → date desc, #41 edge-case).
     */
    suspend fun scan(instanceManager: InstanceManager): List<ScreenshotEntry> {
        val all = instanceManager.getAll()
        val entries = mutableListOf<ScreenshotEntry>()

        for (instance in all) {
            val screenshotDir = instance.rootDir.resolve("minecraft").resolve("screenshots")
            if (!Files.exists(screenshotDir) || !Files.isDirectory(screenshotDir)) continue

            try {
                Files.list(screenshotDir).use { stream ->
                    stream.filter { it.name.lowercase().endsWith(".png") }.forEach { path ->
                        try {
                            entries.add(ScreenshotEntry(
                                path = path,
                                instanceId = instance.id,
                                instanceName = instance.config.name,
                                filename = path.name,
                                lastModified = Files.getLastModifiedTime(path).toMillis(),
                                sizeBytes = Files.size(path)
                            ))
                        } catch (e: Exception) {
                            // Skip unreadable files
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip instance jeśli directory listing fails
            }
        }

        // Sort compound: instance name → date desc (#41)
        return entries.sortedWith(
            compareBy<ScreenshotEntry> { it.instanceName }.thenByDescending { it.lastModified }
        )
    }
}
