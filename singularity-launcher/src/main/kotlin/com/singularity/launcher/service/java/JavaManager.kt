// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service.java

import java.nio.file.Path

/**
 * Java runtime manager — odpowiada za download + cache Java versions dla MC instances.
 *
 * **Implementation:** `JavaManagerImpl` (Task 29) z Adoptium API v3 + SHA256 verification.
 *
 * **Exact MC → Java mapping (NO fallback higher):**
 * - MC 1.8.9-1.16.5 → Java 8
 * - MC 1.17-1.20.4 → Java 17
 * - MC 1.20.5+ → Java 21
 */
interface JavaManager {
    /**
     * Returns path do `bin/java` executable dla MC version. Lazy downloads jeśli brak.
     *
     * @param mcVersion wersja MC (np. "1.20.1")
     * @param onProgress callback 0..100 progress percent
     * @return Path do java executable
     * @throws SecurityException gdy SHA256 mismatch
     * @throws RuntimeException gdy download fails lub nieznana wersja MC
     */
    suspend fun ensureJava(mcVersion: String, onProgress: (Int) -> Unit = {}): Path

    /**
     * Returns Java version required dla MC version — exact match, NO fallback higher.
     * @throws IllegalArgumentException dla nieznanej wersji MC
     */
    fun getJavaFor(mcVersion: String): Int

    /**
     * Checks if Java version already downloaded locally.
     */
    fun isInstalled(javaMajorVersion: Int): Boolean

    /**
     * List installed Java versions (dla SettingsScreen dropdown).
     */
    fun listInstalledVersions(): List<Int>
}
