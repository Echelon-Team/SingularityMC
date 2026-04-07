package com.singularity.launcher.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SingularityThemeTest {

    @Test
    fun `End Dimension color scheme has correct background`() {
        val colors = EndDimensionDarkColors
        assertEquals(Color(0xFF14121D), colors.background)
    }

    @Test
    fun `End Dimension color scheme has correct primary accent`() {
        val colors = EndDimensionDarkColors
        assertEquals(Color(0xFF7F3FB2), colors.primary)
    }

    @Test
    fun `End Dimension color scheme has correct text color on background`() {
        val colors = EndDimensionDarkColors
        assertEquals(Color(0xFFDDDFA5), colors.onBackground)
    }

    @Test
    fun `End Dimension color scheme has correct surface`() {
        val colors = EndDimensionDarkColors
        assertEquals(Color(0xFF1B1028), colors.surface)
    }

    @Test
    fun `End Dimension color scheme has correct error color`() {
        val colors = EndDimensionDarkColors
        assertEquals(Color(0xFFF38BA8), colors.error)
    }

    @Test
    fun `SingularityColors has correct sidebar background`() {
        assertEquals(Color(0xFF0B0D1A), SingularityColors.sidebarBg)
    }

    @Test
    fun `SingularityColors has correct card background`() {
        assertEquals(Color(0xFF1B1028), SingularityColors.cardBg)
    }

    @Test
    fun `SingularityColors has correct badge enhanced color`() {
        assertEquals(Color(0xFFB84AFF), SingularityColors.badgeEnhanced)
    }

    @Test
    fun `SingularityColors has correct status success`() {
        assertEquals(Color(0xFF2CCAAF), SingularityColors.statusSuccess)
    }
}
