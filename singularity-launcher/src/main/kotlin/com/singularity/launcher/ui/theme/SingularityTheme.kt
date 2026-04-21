// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Paleta kolorów End Dimension Theme.
 *
 * Source of truth: docs/visual-companion/index.html, CSS vars :root[data-theme="end"].
 * Mapowanie CSS → Material3:
 *   --base           → background
 *   --surface-0      → surface
 *   --surface-1      → surfaceVariant / primaryContainer
 *   --surface-2      → secondaryContainer
 *   --accent-primary → primary
 *   --accent-secondary → secondary
 *   --accent-tertiary → tertiary
 *   --text-primary   → onBackground, onSurface
 *   --text-muted     → onSurfaceVariant
 *   --status-error   → error
 *   --overlay-0      → outline
 *   --text-disabled  → outlineVariant
 */
val EndDimensionDarkColors = darkColorScheme(
    // Accent colors
    primary = Color(0xFF7F3FB2),              // --accent-primary
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2F195F),      // --surface-1
    onPrimaryContainer = Color(0xFFDA73DE),    // --accent-secondary
    secondary = Color(0xFFDA73DE),             // --accent-secondary
    onSecondary = Color(0xFF14121D),           // --base
    secondaryContainer = Color(0xFF472147),    // --surface-2
    onSecondaryContainer = Color(0xFFDA73DE),
    tertiary = Color(0xFFA233EB),              // --accent-tertiary
    onTertiary = Color(0xFF14121D),
    tertiaryContainer = Color(0xFF2F195F),
    onTertiaryContainer = Color(0xFFA233EB),

    // Background / Surface
    background = Color(0xFF14121D),            // --base
    onBackground = Color(0xFFDDDFA5),          // --text-primary
    surface = Color(0xFF1B1028),               // --surface-0
    onSurface = Color(0xFFDDDFA5),             // --text-primary
    surfaceVariant = Color(0xFF2F195F),         // --surface-1
    onSurfaceVariant = Color(0xFF9399B2),      // --text-muted

    // Status
    error = Color(0xFFF38BA8),                 // --status-error
    onError = Color(0xFF14121D),

    // Chrome
    outline = Color(0xFF765876),               // --overlay-0
    outlineVariant = Color(0xFF5A587A),        // --text-disabled

    // Inverted
    inverseSurface = Color(0xFFDDDFA5),
    inverseOnSurface = Color(0xFF14121D),
    inversePrimary = Color(0xFF7F3FB2),

    // Scrim
    scrim = Color(0xFF000000),
)

/**
 * Kolory projektu SingularityMC wykraczające poza paletę Material3.
 * Używane do elementów specyficznych dla naszego UI (sidebar, karty, badge'e, statusy).
 *
 * **Task 2 2026-04-11**: dodany `sidebarBorder` (S10 v3 design review — parity z Aether).
 */
object SingularityColors {
    // Sidebar
    val sidebarBg = Color(0xFF0B0D1A)              // --sidebar-bg
    val sidebarActive = Color(0x407F3FB2)           // --sidebar-active (rgba 25%)
    val sidebarHover = Color(0x337F3FB2)            // --sidebar-hover (rgba 20%)
    val sidebarBorder = Color(0x33765876)           // --sidebar-border (rgba 20%) — Task 2

    // Cards
    val cardBg = Color(0xFF1B1028)                  // --card-bg
    val cardHover = Color(0xFF241535)                // --card-hover

    // Badges
    val badgeEnhanced = Color(0xFFB84AFF)           // --badge-enhanced
    val badgeVanilla = Color(0xFF8185C9)             // --badge-vanilla

    // Status
    val statusSuccess = Color(0xFF2CCAAF)           // --status-success
    val statusWarning = Color(0xFFF7E9A3)           // --status-warning
    val statusError = Color(0xFFF38BA8)              // --status-error
    val statusInfo = Color(0xFF6C70B2)               // --status-info

    // Play button gradient (Compose nie ma natywnych gradientów w kolorze przycisku)
    val playGradientStart = Color(0xFF7F3FB2)        // --button-play-bg start
    val playGradientEnd = Color(0xFFA233EB)          // --button-play-bg end

    // Backgrounds
    val crust = Color(0xFF0F1020)                    // --crust (najciemniejszy)
    val mantle = Color(0xFF1A0F2B)                   // --mantle

    // Scrollbar
    val scrollbarThumb = Color(0xFF2F195F)           // --scrollbar-thumb
    val scrollbarTrack = Color(0xFF14121D)            // --scrollbar-track

    // Text levels
    val textPrimary = Color(0xFFDDDFA5)              // --text-primary
    val textSecondary = Color(0xFFBAC2DE)             // --text-secondary
    val textMuted = Color(0xFF9399B2)                 // --text-muted
    val textDisabled = Color(0xFF5A587A)              // --text-disabled
}

/**
 * Dwa tryby theme: End Dimension (dark, default) i Aether (light).
 * Switching przez `animateColorAsState` per ColorScheme kolor — smooth transition
 * bez niszczenia state (NIE używamy AnimatedContent który gubi scroll/modal/focus state).
 *
 * Referencja: GUI spec sekcja 1, visual companion index.html linie 2, 9-58, 61-110.
 */
enum class ThemeMode { END, AETHER }

/**
 * CompositionLocal dla ThemeMode — dostępny przez `LocalThemeMode.current`.
 * `compositionLocalOf` (nie static) — reaguje na zmiany (propaguje do konsumentów).
 */
val LocalThemeMode = compositionLocalOf { ThemeMode.END }

/**
 * Composable opakowujące MaterialTheme. Theme switching jest **instant** — transition
 * na poziomie tła daje `ThemeTransitionBackground` przez Crossfade (400ms).
 *
 * ExtraPalette (SingularityExtraPalette) jest dostarczane przez LocalExtraPalette —
 * kolory custom (sidebar, cards, badges, gradients) nie są animowane per field bo
 * data class Color fields nie mogą być animateColorAsState'owane na poziomie data
 * class. Theme switch jest instant dla custom colors, ale Material3 kolory animują
 * smooth → visual effect spójny bo główne tło/surface to Material3.
 */
/**
 * **PERF NOTE (2026-04-11 fix):** Wcześniejsza wersja używała 36× `animateColorAsState`
 * na każdym polu `ColorScheme` żeby dać smooth theme transition. To był anti-pattern:
 * każde pole tworzyło własny `Animatable<Color>` który przez stan `isRunning` wymuszał
 * constant recomposition — efekt był 15 FPS + 20% GPU usage cały czas (nie tylko podczas
 * theme switching). Usunięte — theme switch jest teraz instant. Smooth transition daje
 * `ThemeTransitionBackground` przez 400ms `Crossfade` na poziomie tła (Task 25).
 */
@Composable
fun SingularityTheme(
    themeMode: ThemeMode = ThemeMode.END,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        ThemeMode.END -> EndDimensionDarkColors
        ThemeMode.AETHER -> AetherLightColors
    }

    val extraPalette = when (themeMode) {
        ThemeMode.END -> endExtraPalette()
        ThemeMode.AETHER -> aetherExtraPalette()
    }

    CompositionLocalProvider(
        LocalThemeMode provides themeMode,
        LocalExtraPalette provides extraPalette
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
