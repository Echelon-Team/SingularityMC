package com.singularity.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.common.model.InstanceType
import com.singularity.launcher.ui.theme.LocalExtraPalette

// Extract constants dla testowalności + spójność z prototypem index.html:609-628
const val BADGE_LETTER_SPACING_SP = 0.5f
const val BADGE_FONT_SIZE_SP = 11f
const val BADGE_BG_ALPHA = 0.15f

/**
 * Text dla Instance Type badge — UPPERCASE (prototyp index.html:620-621).
 * Extracted jako helper dla pure-unit test.
 */
fun badgeTextForInstanceType(type: InstanceType): String = when (type) {
    InstanceType.ENHANCED -> "ENHANCED"
    InstanceType.VANILLA -> "VANILLA"
}

/**
 * Badge Enhanced/Vanilla przy instancjach (design spec 4, prototyp index.html:609-628).
 *
 * **Visual spec (z prototypu):**
 * - `padding: 2px 8px` (Compose: horizontal 8dp, vertical 2dp)
 * - `border-radius: 4px`
 * - `font-size: 11px` (Compose: 11sp)
 * - `font-weight: 600` (Compose: SemiBold)
 * - `text-transform: uppercase` (Kotlin: `text.uppercase()`)
 * - `letter-spacing: 0.5px` (Compose: `TextStyle(letterSpacing = 0.5.sp)`)
 * - `background: rgba(color, 0.15)`
 * - `color: <theme badge color>`
 */
@Composable
fun InstanceTypeBadge(type: InstanceType) {
    val extra = LocalExtraPalette.current
    val color = when (type) {
        InstanceType.ENHANCED -> extra.badgeEnhanced
        InstanceType.VANILLA -> extra.badgeVanilla
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = BADGE_BG_ALPHA))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = badgeTextForInstanceType(type),
            color = color,
            fontSize = BADGE_FONT_SIZE_SP.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = BADGE_LETTER_SPACING_SP.sp
        )
    }
}

/**
 * Generic tekstowy badge (uppercase + letter-spacing + semi-bold).
 */
@Composable
fun TextBadge(
    text: String,
    backgroundColor: Color,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor.copy(alpha = BADGE_BG_ALPHA))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text.uppercase(),
            color = textColor,
            fontSize = BADGE_FONT_SIZE_SP.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = BADGE_LETTER_SPACING_SP.sp
        )
    }
}
