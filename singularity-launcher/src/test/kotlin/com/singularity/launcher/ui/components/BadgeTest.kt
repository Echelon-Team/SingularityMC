// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.components

import com.singularity.common.model.InstanceType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BadgeTest {

    @Test
    fun `badge text for ENHANCED is uppercase ENHANCED`() {
        // Prototyp index.html:620-621 ma `text-transform: uppercase` → "ENHANCED"
        assertEquals("ENHANCED", badgeTextForInstanceType(InstanceType.ENHANCED))
    }

    @Test
    fun `badge text for VANILLA is uppercase VANILLA`() {
        assertEquals("VANILLA", badgeTextForInstanceType(InstanceType.VANILLA))
    }

    @Test
    fun `badge letter spacing is 0_5 sp`() {
        // Verifies constant used in Badge composable
        assertEquals(0.5f, BADGE_LETTER_SPACING_SP)
    }

    @Test
    fun `badge font size is 11 sp`() {
        assertEquals(11f, BADGE_FONT_SIZE_SP)
    }

    @Test
    fun `badge background alpha is 0_15`() {
        assertEquals(0.15f, BADGE_BG_ALPHA)
    }
}
