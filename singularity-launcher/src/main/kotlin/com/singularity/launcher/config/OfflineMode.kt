// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.config

import org.slf4j.LoggerFactory

/**
 * Runtime flag set via CLI arg `--offline` (passed by auto-update.exe when user chose
 * OFFLINE MODE button in the update UI, e.g. after failed update or no internet retry).
 *
 * **Obecnie honorowane w kodzie:**
 * - News feed fetching from GitHub — `HomeViewModel` skips repo fetch
 *   → `ReleasesState.Offline` → HomeScreen news section renders
 *   `home.news.offline` banner.
 * - Modrinth integration — `ModrinthClientImpl.search` / `.getVersions`
 *   early-return `Result.success(emptyList())`. UI (ModrinthScreen) shows
 *   localized "tryb offline" banner instead of API error.
 * - Mojang piston-meta version fetch — `MojangVersionClient.fetchManifest`
 *   returns empty manifest. Create-Instance dialog UI detects empty list
 *   + shows banner ("lista wersji MC niedostępna"); istniejące instancje
 *   działają dalej z cached JARs w `versions/`.
 *
 * **NIE honorowane (intentional — subsystemy nic online nie robią):**
 * - Microsoft Auth: stub per Sub 4 scope — interfejs `AuthManager` nie
 *   ma `signInWithMicrosoft()` metody, UI (Account overlay) wyświetla
 *   disabled button z bannerem "będzie w przyszłości". Planowana pełna
 *   implementacja w przyszłym sub-projekcie; wtedy dodać gate tutaj.
 * - Telemetry transmission: pole `LauncherSettings.telemetryEnabled` +
 *   onboarding toggle istnieją ale brak sender class — żadne dane nie
 *   są wysyłane na backend. Planowane w przyszłości; wtedy dodać gate.
 * - Launcher-side auto-update check (`integration/AutoUpdater.kt`):
 *   **usunięte 2026-04-18** — redundant z `auto-update.exe` który już
 *   check-uje updates przed spawn launcher. Nie potrzebuje gate bo
 *   subsystem removed.
 *
 * **Pozostaje funkcjonalne offline:**
 * - Existing instances (launch / configure locally)
 * - LAN server connections
 * - Locally cached data (themes, settings, accounts in cache)
 *
 * **Set-once semantics:** calling `parseArgs` with `--offline` flips the flag to true and it
 * stays true. This prevents accidental flip-off by code parsing a subset of args later.
 * `reset()` is test-only.
 *
 * **Matching contract:** exact-match only. `"--offline"` enables; `"--OFFLINE"`, `"--Offline"`,
 * `"-offline"`, `"--offline=true"` do NOT. The Rust auto-update.exe emits the bare flag via
 * `Command::new().arg("--offline")` which matches exactly. See `OfflineModeTest` for locked-in
 * cases.
 *
 * **Thread safety:** single-writer (`Main.kt` at startup before any UI/service init),
 * many-readers thereafter. `@Volatile` ensures cross-thread visibility of the one-time write.
 */
object OfflineMode {
    private val logger = LoggerFactory.getLogger(OfflineMode::class.java)

    @Volatile private var enabled: Boolean = false

    /**
     * Parse CLI args for `--offline` flag. Additive: once enabled, not re-disabled.
     * Safe to call multiple times. Logs once on first enable.
     */
    fun parseArgs(args: Array<String>) {
        if (args.any { it == "--offline" } && !enabled) {
            enabled = true
            logger.info("OFFLINE MODE enabled via --offline flag")
        }
    }

    fun isEnabled(): Boolean = enabled

    /** Test-only: reset state between tests. */
    internal fun reset() {
        enabled = false
    }
}
