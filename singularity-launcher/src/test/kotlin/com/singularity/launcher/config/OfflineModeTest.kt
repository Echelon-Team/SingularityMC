package com.singularity.launcher.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class OfflineModeTest {

    @BeforeEach
    fun reset() {
        OfflineMode.reset()
    }

    @Test
    fun `default is not offline`() {
        assertFalse(OfflineMode.isEnabled())
    }

    @Test
    fun `parseArgs with --offline enables offline mode`() {
        OfflineMode.parseArgs(arrayOf("--offline"))
        assertTrue(OfflineMode.isEnabled())
    }

    @Test
    fun `parseArgs with --offline among other args enables offline`() {
        OfflineMode.parseArgs(arrayOf("--other-flag", "value", "--offline", "extra"))
        assertTrue(OfflineMode.isEnabled())
    }

    @Test
    fun `parseArgs without --offline stays disabled`() {
        OfflineMode.parseArgs(arrayOf("--other-flag", "value"))
        assertFalse(OfflineMode.isEnabled())
    }

    @Test
    fun `parseArgs with empty args stays disabled`() {
        OfflineMode.parseArgs(emptyArray())
        assertFalse(OfflineMode.isEnabled())
    }

    @Test
    fun `parseArgs is additive — once enabled, stays enabled`() {
        // In case auto-update parses args twice (e.g. reload), the flag must not flip off.
        OfflineMode.parseArgs(arrayOf("--offline"))
        OfflineMode.parseArgs(arrayOf("--something-else"))
        assertTrue(OfflineMode.isEnabled())
    }

    @Test
    fun `reset clears offline state (test-only helper)`() {
        OfflineMode.parseArgs(arrayOf("--offline"))
        OfflineMode.reset()
        assertFalse(OfflineMode.isEnabled())
    }

    // === Contract lock-in: case-sensitive exact match ===

    @Test
    fun `parseArgs is CASE-SENSITIVE — --OFFLINE does NOT enable`() {
        OfflineMode.parseArgs(arrayOf("--OFFLINE"))
        assertFalse(OfflineMode.isEnabled(), "exact-match contract: uppercase ignored")
    }

    @Test
    fun `parseArgs is CASE-SENSITIVE — --Offline does NOT enable`() {
        OfflineMode.parseArgs(arrayOf("--Offline"))
        assertFalse(OfflineMode.isEnabled(), "exact-match contract: mixed-case ignored")
    }

    @Test
    fun `parseArgs does NOT accept --offline=true (no =value form)`() {
        OfflineMode.parseArgs(arrayOf("--offline=true"))
        assertFalse(OfflineMode.isEnabled(), "exact-match contract: =value form ignored")
    }

    @Test
    fun `parseArgs does NOT accept single-dash -offline`() {
        OfflineMode.parseArgs(arrayOf("-offline"))
        assertFalse(OfflineMode.isEnabled(), "exact-match contract: single-dash ignored")
    }
}
