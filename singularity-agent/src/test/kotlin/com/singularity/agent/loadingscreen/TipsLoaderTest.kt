package com.singularity.agent.loadingscreen

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TipsLoaderTest {

    @Test
    fun `loadTips loads bundled tips from agent resources`() {
        val tips = TipsLoader.loadTips("/loading-tips.json")
        assertTrue(tips.isNotEmpty(), "Should load tips from bundled JSON")
        assertTrue(tips.size >= 8, "Should have at least 8 tips, got ${tips.size}")
    }

    @Test
    fun `each tip has both pl and en text`() {
        val tips = TipsLoader.loadTips("/loading-tips.json")
        for (tip in tips) {
            assertTrue(tip.pl.isNotBlank(), "PL text should not be blank for tip: $tip")
            assertTrue(tip.en.isNotBlank(), "EN text should not be blank for tip: $tip")
        }
    }

    @Test
    fun `randomTip returns PL text for pl language`() {
        val tips = TipsLoader.loadTips("/loading-tips.json")
        val tip = TipsLoader.randomTip(tips, "pl")
        assertNotNull(tip)
        // PL tips should contain Polish-specific characters or words
        assertTrue(tips.any { it.pl == tip }, "Returned tip should be from PL set")
    }

    @Test
    fun `randomTip returns EN text for en language`() {
        val tips = TipsLoader.loadTips("/loading-tips.json")
        val tip = TipsLoader.randomTip(tips, "en")
        assertNotNull(tip)
        assertTrue(tips.any { it.en == tip }, "Returned tip should be from EN set")
    }

    @Test
    fun `randomTip returns null for empty list`() {
        val tip = TipsLoader.randomTip(emptyList(), "pl")
        assertNull(tip)
    }

    @Test
    fun `loadTips returns empty for nonexistent resource`() {
        val tips = TipsLoader.loadTips("/nonexistent.json")
        assertTrue(tips.isEmpty())
    }

    @Test
    fun `loadTips handles malformed JSON gracefully`() {
        // This would require a malformed resource — test via a known-bad path
        // Since we can't easily inject a bad resource, test that empty list is returned for parse errors
        val tips = TipsLoader.loadTips("/logback.xml") // exists but not JSON
        assertTrue(tips.isEmpty())
    }
}
