package com.singularity.launcher.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Paleta Aether Theme (light).
 *
 * Source of truth: docs/visual-companion/index.html, CSS vars :root[data-theme="aether"]
 * (linie 61-110). **NIE jest to "white + light blue" — to RICH COLORFUL SKY** z
 * bogatymi tonami: zanite (niebieski gem), gravitite (fiolet), ambrosium (złoto),
 * skyroot (sky muted), aether grass (visible green). Potwierdzone screenshotem
 * proto-10-home-aether.png.
 */
val AetherLightColors = lightColorScheme(
    // Accents
    primary = Color(0xFF2070CC),              // zanite — rich blue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8CDB4),      // aether grass
    onPrimaryContainer = Color(0xFF1A2830),
    secondary = Color(0xFF7050B8),             // gravitite
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA8BCCE),
    onSecondaryContainer = Color(0xFF1A2830),
    tertiary = Color(0xFFC89828),              // ambrosium gold
    onTertiary = Color(0xFF1A2830),

    // Backgrounds
    background = Color(0xFF9EBCD2),            // sky blue — rich saturated
    onBackground = Color(0xFF1A2830),          // deep twilight
    surface = Color(0xFFB0CCDA),               // sky tinted — cards
    onSurface = Color(0xFF1A2830),
    surfaceVariant = Color(0xFF94B4CE),        // deeper sky
    onSurfaceVariant = Color(0xFF3A5565),

    // Status
    error = Color(0xFFB83434),                 // lava red
    onError = Color(0xFFFFFFFF),

    // Outlines
    outline = Color(0xFF6E8494),               // deep holystone
    outlineVariant = Color(0xFF8A9EAA)         // muted sky
)

/**
 * Aether project-specific colors — wszystko poza Material3 ColorScheme.
 * Rozszerzone o playGradient* (S5 v3 design review) + sidebarActive/Hover/Border (S5 v3).
 */
object AetherExtraColors {
    // Sidebar
    val sidebarBg = Color(0xFF88AECA)                // deeper sky — rich sidebar
    val sidebarActive = Color(0x332070CC)            // rgba(32, 112, 204, 0.20)
    val sidebarHover = Color(0x1F2070CC)             // rgba(32, 112, 204, 0.12)
    val sidebarBorder = Color(0x263C5A6E)            // rgba(60, 90, 110, 0.15)

    // Cards
    val cardBg = Color(0xFFB0CCDA)                   // sky tinted — matches surface-0
    val cardHover = Color(0xFFBCDAE4)                // lighter on hover

    // Badges
    val badgeEnhanced = Color(0xFF7050B8)            // gravitite
    val badgeVanilla = Color(0xFF2070CC)             // zanite

    // Status
    val statusSuccess = Color(0xFF28784A)            // aether grass — rich green
    val statusWarning = Color(0xFFA87820)            // ambrosium deep
    val statusError = Color(0xFFB83434)              // lava — deep red
    val statusInfo = Color(0xFF2070CC)               // zanite

    // Play button gradient — zanite → zanite deep (design spec playGradient)
    val playGradientStart = Color(0xFF2070CC)        // zanite
    val playGradientEnd = Color(0xFF3088B8)          // zanite deep

    // Text levels
    val textPrimary = Color(0xFF1A2830)              // deep twilight
    val textSecondary = Color(0xFF3A5060)            // mid sky dark
    val textMuted = Color(0xFF3A5565)                // holystone blue — WCAG AA compliant
    val textDisabled = Color(0xFF8A9EAA)             // muted sky

    // Scrollbar
    val scrollbarThumb = Color(0xFF7EA2BC)           // deep sky
    val scrollbarTrack = Color(0xFF94B4CE)           // sky
}
