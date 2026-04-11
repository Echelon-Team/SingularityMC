package com.singularity.launcher.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Project-specific kolory poza Material3 ColorScheme. Osobne per theme (End/Aether).
 *
 * **Rozszerzone o (Task 2 rewrite 2026-04-11)**:
 * - `playGradientStart/End` (S5 v3 design review) — dla btn-play gradient
 * - `sidebarActive/sidebarHover/sidebarBorder` (S5 v3) — dla sidebar 3 stany hover
 * - `scrollbarThumb/scrollbarTrack` — dla custom Compose ScrollbarStyle (Task 3)
 *
 * **compositionLocalOf** (nie `staticCompositionLocalOf`) — dynamic propagation
 * przy theme switch. Static lockuje wartość przy pierwszym provide i nie reaguje
 * na zmiany — regresja przy theme switching (S4 v1 design review).
 */
data class SingularityExtraPalette(
    // Sidebar
    val sidebarBg: Color,
    val sidebarActive: Color,
    val sidebarHover: Color,
    val sidebarBorder: Color,

    // Cards
    val cardBg: Color,
    val cardHover: Color,

    // Badges
    val badgeEnhanced: Color,
    val badgeVanilla: Color,

    // Status
    val statusSuccess: Color,
    val statusWarning: Color,
    val statusError: Color,
    val statusInfo: Color,

    // Play button gradient
    val playGradientStart: Color,
    val playGradientEnd: Color,

    // Text levels (poza Material3 onBackground/onSurface)
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textDisabled: Color,

    // Scrollbar
    val scrollbarThumb: Color,
    val scrollbarTrack: Color
)

/** Factory function dla End Dimension palette. */
fun endExtraPalette() = SingularityExtraPalette(
    sidebarBg = SingularityColors.sidebarBg,
    sidebarActive = SingularityColors.sidebarActive,
    sidebarHover = SingularityColors.sidebarHover,
    sidebarBorder = SingularityColors.sidebarBorder,
    cardBg = SingularityColors.cardBg,
    cardHover = SingularityColors.cardHover,
    badgeEnhanced = SingularityColors.badgeEnhanced,
    badgeVanilla = SingularityColors.badgeVanilla,
    statusSuccess = SingularityColors.statusSuccess,
    statusWarning = SingularityColors.statusWarning,
    statusError = SingularityColors.statusError,
    statusInfo = SingularityColors.statusInfo,
    playGradientStart = SingularityColors.playGradientStart,
    playGradientEnd = SingularityColors.playGradientEnd,
    textPrimary = SingularityColors.textPrimary,
    textSecondary = SingularityColors.textSecondary,
    textMuted = SingularityColors.textMuted,
    textDisabled = SingularityColors.textDisabled,
    scrollbarThumb = SingularityColors.scrollbarThumb,
    scrollbarTrack = SingularityColors.scrollbarTrack
)

/** Factory function dla Aether palette. */
fun aetherExtraPalette() = SingularityExtraPalette(
    sidebarBg = AetherExtraColors.sidebarBg,
    sidebarActive = AetherExtraColors.sidebarActive,
    sidebarHover = AetherExtraColors.sidebarHover,
    sidebarBorder = AetherExtraColors.sidebarBorder,
    cardBg = AetherExtraColors.cardBg,
    cardHover = AetherExtraColors.cardHover,
    badgeEnhanced = AetherExtraColors.badgeEnhanced,
    badgeVanilla = AetherExtraColors.badgeVanilla,
    statusSuccess = AetherExtraColors.statusSuccess,
    statusWarning = AetherExtraColors.statusWarning,
    statusError = AetherExtraColors.statusError,
    statusInfo = AetherExtraColors.statusInfo,
    playGradientStart = AetherExtraColors.playGradientStart,
    playGradientEnd = AetherExtraColors.playGradientEnd,
    textPrimary = AetherExtraColors.textPrimary,
    textSecondary = AetherExtraColors.textSecondary,
    textMuted = AetherExtraColors.textMuted,
    textDisabled = AetherExtraColors.textDisabled,
    scrollbarThumb = AetherExtraColors.scrollbarThumb,
    scrollbarTrack = AetherExtraColors.scrollbarTrack
)

/**
 * CompositionLocal dla palette — dostępny przez `LocalExtraPalette.current`
 * w dowolnym composable wewnątrz `SingularityTheme`.
 *
 * Fail-fast default: error gdy użyte poza SingularityTheme. Zapobiega zapomnianemu
 * wire'owaniu w standalone composable albo testach — lepiej crash z wyraźnym
 * komunikatem niż silent fallback z domyślnym End palette.
 */
val LocalExtraPalette = compositionLocalOf<SingularityExtraPalette> {
    error("LocalExtraPalette not provided — wrap content in SingularityTheme { ... }")
}
