// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.util

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Sygnalizacja do auto-update że launcher dojechał do stable UI state.
 *
 * Auto-update (Rust) przy spawn launcher ustawia env var
 * `SINGULARITY_INSTALL_DIR` wskazujący gdzie żyje `auto-update-state.json`
 * i inne installer-self-repair state. Po reach-ową main UI composable
 * (2 s po pierwszym Compose frame) launcher touchuje
 * `$INSTALL_DIR/launcher-alive-flag`. Auto-update przy następnym boot
 * konsumuje ten flag (`consume_launcher_alive_flag` w Rust-owym
 * `auto-update/src/launcher.rs`) → reset `launcher_crash_counter`.
 *
 * Flag ABSENT przy next boot = auto-update zakłada że poprzedni launcher
 * crashed before stabilising → increment counter → po 2 consecutive
 * crashach trigger rollback z `File-Backups/pre-update-*`. Threshold
 * i cała rollback logic po stronie Rust-a (patrz
 * `LAUNCHER_CRASH_THRESHOLD` w `auto-update/src/launcher.rs`).
 *
 * Env var brak = launcher uruchomiony bez auto-update wrappera (np.
 * dev run `./gradlew :singularity-launcher:run`) → skip bez błędu.
 * Nie ma crash counter do koordynacji.
 *
 * **Thread safety:** statyczne metody w object, brak mutowalnego state.
 * Wywołuj z dowolnego kontekstu coroutine/thread; IO file-write musi
 * być na Dispatchers.IO żeby nie blokować głównego Compose thread-a —
 * callera odpowiedzialność (patrz `App.kt` LaunchedEffect).
 */
object LauncherAliveFlag {
    private val logger = LoggerFactory.getLogger(LauncherAliveFlag::class.java)

    private const val ENV_INSTALL_DIR = "SINGULARITY_INSTALL_DIR"
    private const val FLAG_FILE_NAME = "launcher-alive-flag"

    /**
     * Zapisz alive flag. Wywołaj z Dispatchers.IO (file I/O).
     *
     * No-op gdy env `SINGULARITY_INSTALL_DIR` brak/pusty (launcher
     * uruchomiony bezpośrednio przez usera, nie przez auto-update).
     * Nie throw-uje — wszystkie błędy są logged i swallowed bo flag jest
     * advisory: fail-to-write powoduje mis-count jako crash, ale to tylko
     * bump counter o 1 (do threshold potrzeba 2 kolejne crashy), nie
     * wywoła pojedynczy false positive rollback.
     */
    fun write(env: Map<String, String> = System.getenv()) {
        val installDir = env[ENV_INSTALL_DIR]?.takeIf { it.isNotBlank() }
        if (installDir == null) {
            logger.debug("$ENV_INSTALL_DIR not set; launcher started outside auto-update — skipping alive flag")
            return
        }
        writeTo(Path.of(installDir))
    }

    /**
     * Test hook — write do konkretnego katalogu (zamiast czytać env).
     * Publiczne żeby test mógł weryfikować file contract bez mutowania
     * env (JVM `System.getenv()` jest immutable po startup bez JNI
     * tricków).
     */
    fun writeTo(installDir: Path) {
        val flag = installDir.resolve(FLAG_FILE_NAME)
        try {
            // Overwrite empty string — idempotent create-or-touch. File
            // istotne jest w samym istnieniu, nie w zawartości.
            Files.writeString(flag, "")
            logger.info("Wrote launcher alive flag at {}", flag)
        } catch (e: Exception) {
            logger.warn("Failed to write launcher alive flag at $flag: ${e.message}", e)
        }
    }
}
