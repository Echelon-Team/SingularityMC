package com.singularity.agent.mod

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModVersionComparatorTest {

    @Test
    fun `0_5_10 is newer than 0_5_9 (the lexicographic bug test)`() {
        // THE classic bug: string sort says "0.5.10" < "0.5.9" bo '1' < '9'.
        // ModVersionComparator musi zwrócić +1 (0.5.10 newer).
        assertTrue(ModVersionComparator.compare("0.5.10", "0.5.9") > 0)
        assertTrue(ModVersionComparator.compare("0.5.9", "0.5.10") < 0)
    }

    @Test
    fun `identical versions compare to 0`() {
        assertEquals(0, ModVersionComparator.compare("1.0.0", "1.0.0"))
        assertEquals(0, ModVersionComparator.compare("0.5.8+mc1.20.1", "0.5.8+mc1.20.1"))
    }

    @Test
    fun `major version difference dominates`() {
        assertTrue(ModVersionComparator.compare("2.0.0", "1.99.99") > 0)
        assertTrue(ModVersionComparator.compare("1.0.0", "2.0.0") < 0)
    }

    @Test
    fun `minor version difference dominates over patch`() {
        assertTrue(ModVersionComparator.compare("1.5.0", "1.4.99") > 0)
    }

    @Test
    fun `patch version difference is detected`() {
        assertTrue(ModVersionComparator.compare("1.0.100", "1.0.99") > 0)
    }

    @Test
    fun `build metadata is ignored in comparison`() {
        // Build metadata (po `+`) nie powinno wpływać na porównanie
        assertEquals(0, ModVersionComparator.compare("0.5.8+mc1.20.1", "0.5.8+mc1.20.2"))
        assertEquals(0, ModVersionComparator.compare("1.0.0+abc", "1.0.0+xyz"))
    }

    @Test
    fun `stable version is newer than pre-release`() {
        // SemVer rule: 1.0.0 > 1.0.0-rc1 (stable > pre-release)
        assertTrue(ModVersionComparator.compare("1.0.0", "1.0.0-rc1") > 0)
        assertTrue(ModVersionComparator.compare("1.0.0-rc1", "1.0.0") < 0)
    }

    @Test
    fun `pre-release comparison uses string order`() {
        // Prosta implementacja: alpha < beta < rc (lexicographic)
        assertTrue(ModVersionComparator.compare("1.0.0-beta", "1.0.0-alpha") > 0)
        assertTrue(ModVersionComparator.compare("1.0.0-rc1", "1.0.0-beta") > 0)
    }

    @Test
    fun `missing components default to 0`() {
        // 1.0 == 1.0.0 (trailing zeros)
        assertEquals(0, ModVersionComparator.compare("1.0", "1.0.0"))
        assertEquals(0, ModVersionComparator.compare("1", "1.0.0"))
    }

    @Test
    fun `non-numeric components treated as 0`() {
        // 0.5.1.f vs 0.5.1 — 'f' → 0 → tied
        assertEquals(0, ModVersionComparator.compare("0.5.1.f", "0.5.1"))
        // 0.5.1 vs 0.5.2 — 2 > 1
        assertTrue(ModVersionComparator.compare("0.5.2", "0.5.1.f") > 0)
    }

    @Test
    fun `MC-style version with build metadata`() {
        // Realne: Sodium używa "0.5.8+mc1.20.1", Lithium "0.11.1+mc1.20"
        assertTrue(ModVersionComparator.compare("0.5.9+mc1.20.1", "0.5.8+mc1.20.1") > 0)
    }

    @Test
    fun `Minecraft version-like strings compare correctly`() {
        assertTrue(ModVersionComparator.compare("1.20.1", "1.20") > 0)
        assertTrue(ModVersionComparator.compare("1.20.10", "1.20.2") > 0) // classic bug case again
    }

    @Test
    fun `empty strings do not crash`() {
        // Pathological: empty version strings
        assertDoesNotThrow { ModVersionComparator.compare("", "") }
        assertDoesNotThrow { ModVersionComparator.compare("", "1.0.0") }
        assertDoesNotThrow { ModVersionComparator.compare("1.0.0", "") }
        // "" vs "1.0.0": "" → numeric 0 → 0 < 1 → ""  < "1.0.0"
        assertTrue(ModVersionComparator.compare("1.0.0", "") > 0)
    }

    @Test
    fun `whitespace-only versions do not crash`() {
        assertDoesNotThrow { ModVersionComparator.compare("  ", "1.0") }
        assertDoesNotThrow { ModVersionComparator.compare("\t", "\n") }
    }

    @Test
    fun `trailing separator without following data does not crash`() {
        // "1.0.0-" (trailing dash, nothing after) — should not IOOBE
        assertDoesNotThrow { ModVersionComparator.compare("1.0.0-", "1.0.0") }
        assertDoesNotThrow { ModVersionComparator.compare("1.0.0+", "1.0.0") }
        assertDoesNotThrow { ModVersionComparator.compare("1.0.0-+", "1.0.0") }
    }

    @Test
    fun `very long version strings do not crash`() {
        // 100 components version (pathological but must not OOM/crash)
        val longVersion = (1..100).joinToString(".") { it.toString() }
        assertDoesNotThrow { ModVersionComparator.compare(longVersion, "1.0") }
        assertTrue(ModVersionComparator.compare(longVersion, "1.0") > 0)
    }

    @Test
    fun `non-numeric only versions compare without crash`() {
        // SNAPSHOT vs RELEASE — both non-numeric → collapse to 0 → equal
        assertDoesNotThrow { ModVersionComparator.compare("SNAPSHOT", "RELEASE") }
        // With suffix
        assertDoesNotThrow { ModVersionComparator.compare("1.0-SNAPSHOT", "1.0-RELEASE") }
    }
}
