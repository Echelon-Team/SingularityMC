// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AetherColorsTest {

    // Material3 ColorScheme fields
    @Test
    fun `Aether background is rich sky blue`() {
        assertEquals(Color(0xFF9EBCD2), AetherLightColors.background)
    }

    @Test
    fun `Aether primary is zanite blue`() {
        assertEquals(Color(0xFF2070CC), AetherLightColors.primary)
    }

    @Test
    fun `Aether text primary is deep twilight`() {
        assertEquals(Color(0xFF1A2830), AetherLightColors.onBackground)
    }

    @Test
    fun `Aether secondary is gravitite purple`() {
        assertEquals(Color(0xFF7050B8), AetherLightColors.secondary)
    }

    @Test
    fun `Aether tertiary is ambrosium gold`() {
        assertEquals(Color(0xFFC89828), AetherLightColors.tertiary)
    }

    @Test
    fun `Aether surface is sky tinted cards`() {
        assertEquals(Color(0xFFB0CCDA), AetherLightColors.surface)
    }

    @Test
    fun `Aether error is lava red`() {
        assertEquals(Color(0xFFB83434), AetherLightColors.error)
    }

    // AetherExtraColors project-specific fields
    @Test
    fun `AetherExtra sidebarBg is deeper sky`() {
        assertEquals(Color(0xFF88AECA), AetherExtraColors.sidebarBg)
    }

    @Test
    fun `AetherExtra has zanite badge vanilla`() {
        assertEquals(Color(0xFF2070CC), AetherExtraColors.badgeVanilla)
    }

    @Test
    fun `AetherExtra has gravitite badge enhanced`() {
        assertEquals(Color(0xFF7050B8), AetherExtraColors.badgeEnhanced)
    }

    @Test
    fun `AetherExtra has aether grass status success`() {
        assertEquals(Color(0xFF28784A), AetherExtraColors.statusSuccess)
    }

    @Test
    fun `AetherExtra has ambrosium deep status warning`() {
        assertEquals(Color(0xFFA87820), AetherExtraColors.statusWarning)
    }

    @Test
    fun `AetherExtra has lava status error`() {
        assertEquals(Color(0xFFB83434), AetherExtraColors.statusError)
    }

    @Test
    fun `AetherExtra playGradientStart is zanite`() {
        assertEquals(Color(0xFF2070CC), AetherExtraColors.playGradientStart)
    }

    @Test
    fun `AetherExtra playGradientEnd is zanite deep`() {
        assertEquals(Color(0xFF3088B8), AetherExtraColors.playGradientEnd)
    }

    @Test
    fun `AetherExtra sidebarActive is zanite rgba 20pct`() {
        // rgba(32, 112, 204, 0.20) → Color(red=32, green=112, blue=204, alpha=51)
        val c = AetherExtraColors.sidebarActive
        assertEquals(0xFF2070CC.toInt() and 0xFFFFFF, c.toArgb() and 0xFFFFFF, "RGB matches zanite")
        assertTrue(c.alpha > 0.1f && c.alpha < 0.3f, "Alpha ~0.20")
    }

    @Test
    fun `AetherExtra sidebarBorder is rgba skyroot`() {
        assertTrue(AetherExtraColors.sidebarBorder.alpha < 0.3f, "Semi-transparent border")
    }
}
