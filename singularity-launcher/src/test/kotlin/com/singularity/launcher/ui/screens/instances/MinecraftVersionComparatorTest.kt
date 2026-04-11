package com.singularity.launcher.ui.screens.instances

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MinecraftVersionComparatorTest {

    private val cmp = MinecraftVersionComparator()

    @Test
    fun `1_20_1 is greater than 1_9`() {
        // Regression test — string sort dałby 1.9 > 1.20.1 (bo "9" > "2" lexicographically)
        assertTrue(cmp.compare("1.20.1", "1.9") > 0, "1.20.1 musi być > 1.9 (semver-aware)")
        assertTrue(cmp.compare("1.9", "1.20.1") < 0)
    }

    @Test
    fun `1_20_1 is greater than 1_20`() {
        assertTrue(cmp.compare("1.20.1", "1.20") > 0)
        assertTrue(cmp.compare("1.20", "1.20.1") < 0)
    }

    @Test
    fun `equal versions return 0`() {
        assertEquals(0, cmp.compare("1.20.1", "1.20.1"))
        assertEquals(0, cmp.compare("1.16.5", "1.16.5"))
    }

    @Test
    fun `major version beats minor`() {
        assertTrue(cmp.compare("2.0", "1.99") > 0)
    }

    @Test
    fun `semver-aware sort of list of MC versions descending`() {
        val input = listOf("1.9", "1.20.1", "1.16.5", "1.20", "1.8.9", "1.12.2", "1.7.10", "1.21")
        val sorted = input.sortedWith(cmp.reversed())
        assertEquals(
            listOf("1.21", "1.20.1", "1.20", "1.16.5", "1.12.2", "1.9", "1.8.9", "1.7.10"),
            sorted
        )
    }

    @Test
    fun `snapshot versions sort after release`() {
        // Snapshot "1.20.2-pre1" traktujemy jako release 1.20.2 (strip suffix, compare numeric)
        assertTrue(cmp.compare("1.20.2", "1.20.1-pre1") > 0)
    }

    @Test
    fun `missing trailing zero is treated as 0`() {
        // 1.20 == 1.20.0 semantycznie
        assertEquals(0, cmp.compare("1.20", "1.20.0"))
    }

    @Test
    fun `short version vs long version`() {
        assertTrue(cmp.compare("1.16", "1.16.5") < 0, "1.16 < 1.16.5 bo 0 < 5")
    }

    @Test
    fun `version with four parts`() {
        assertTrue(cmp.compare("1.20.1.1", "1.20.1") > 0)
    }

    @Test
    fun `totally equal short vs short`() {
        assertEquals(0, cmp.compare("1.7", "1.7"))
    }

    @Test
    fun `snapshot with different base versions compare by base`() {
        assertTrue(cmp.compare("1.21-pre1", "1.20.4") > 0, "base 1.21 > 1.20.4")
    }

    @Test
    fun `invalid versions fallback to string compare`() {
        // Malformed versions shouldn't crash — fallback na string compare
        val result = cmp.compare("invalid-version", "another-bad")
        assertTrue(result == "invalid-version".compareTo("another-bad"))
    }

    @Test
    fun `empty strings compare equal`() {
        assertEquals(0, cmp.compare("", ""))
    }

    @Test
    fun `empty vs version — version is greater`() {
        // Empty traktowany jako 0.0
        assertTrue(cmp.compare("1.0", "") > 0)
    }
}
