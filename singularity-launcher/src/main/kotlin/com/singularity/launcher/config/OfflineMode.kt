package com.singularity.launcher.config

import org.slf4j.LoggerFactory

/**
 * Runtime flag set via CLI arg `--offline` (passed by auto-update.exe when user chose
 * OFFLINE MODE button in the update UI, e.g. after failed update or no internet retry).
 *
 * When enabled, launcher disables:
 * - Modrinth integration (no API calls, UI shows "offline")
 * - Microsoft/Mojang online login attempts (existing tokens still usable)
 * - News feed fetching from GitHub
 * - Auto-update check triggering (auto-update.exe already knows)
 * - Telemetry transmission
 *
 * Remains functional:
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
