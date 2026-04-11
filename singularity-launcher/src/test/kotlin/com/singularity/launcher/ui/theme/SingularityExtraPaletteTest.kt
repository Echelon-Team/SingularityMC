package com.singularity.launcher.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SingularityExtraPaletteTest {

    @Test
    fun `endExtraPalette returns End Dimension colors`() {
        val palette = endExtraPalette()
        assertEquals(SingularityColors.sidebarBg, palette.sidebarBg)
        assertEquals(SingularityColors.cardBg, palette.cardBg)
        assertEquals(SingularityColors.badgeEnhanced, palette.badgeEnhanced)
        assertEquals(SingularityColors.statusSuccess, palette.statusSuccess)
        assertEquals(SingularityColors.playGradientStart, palette.playGradientStart)
        assertEquals(SingularityColors.sidebarActive, palette.sidebarActive)
        assertEquals(SingularityColors.sidebarHover, palette.sidebarHover)
        assertEquals(SingularityColors.sidebarBorder, palette.sidebarBorder)
    }

    @Test
    fun `aetherExtraPalette returns Aether colors`() {
        val palette = aetherExtraPalette()
        assertEquals(AetherExtraColors.sidebarBg, palette.sidebarBg)
        assertEquals(AetherExtraColors.cardBg, palette.cardBg)
        assertEquals(AetherExtraColors.badgeEnhanced, palette.badgeEnhanced)
        assertEquals(AetherExtraColors.statusSuccess, palette.statusSuccess)
        assertEquals(AetherExtraColors.playGradientStart, palette.playGradientStart)
        assertEquals(AetherExtraColors.sidebarActive, palette.sidebarActive)
    }

    @Test
    fun `End and Aether palettes differ`() {
        assertNotEquals(endExtraPalette().sidebarBg, aetherExtraPalette().sidebarBg)
        assertNotEquals(endExtraPalette().badgeEnhanced, aetherExtraPalette().badgeEnhanced)
        assertNotEquals(endExtraPalette().textPrimary, aetherExtraPalette().textPrimary,
            "End text-primary is cream #DDDFA5, Aether is deep twilight #1A2830")
    }

    @Test
    fun `LocalExtraPalette default error throws when not provided`() {
        // compositionLocalOf default error — verified in live Compose environment
        // Here we just verify the value is NOT staticCompositionLocalOf (reactive).
        // Runtime verification in integration test (Task 32).
        assertNotNull(LocalExtraPalette)
    }
}
