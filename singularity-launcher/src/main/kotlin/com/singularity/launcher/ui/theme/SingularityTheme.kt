package com.singularity.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
 */
object SingularityColors {
    // Sidebar
    val sidebarBg = Color(0xFF0B0D1A)              // --sidebar-bg
    val sidebarActive = Color(0x407F3FB2)           // --sidebar-active (rgba 25%)
    val sidebarHover = Color(0x337F3FB2)            // --sidebar-hover (rgba 20%)

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
 * Composable opakowujące MaterialTheme z kolorami End Dimension.
 * Aether Theme (light) dodawany w Subsystemie 5 (GUI) z przełączaniem dynamicznym.
 */
@Composable
fun SingularityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EndDimensionDarkColors,
        content = content
    )
}
