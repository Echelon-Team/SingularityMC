// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.launcher.ui.theme.LocalExtraPalette
import java.util.Locale

/**
 * 4-segment horizontal bar wyświetlający rozkład usage dysku per instance.
 *
 * Segmenty (kolejność od lewej): Światy | Mody | Backupy | Config | Free.
 * Wagi proporcjonalne do fractions z `calculateSegmentWidths`.
 */
@Composable
fun DiskUsageBar(
    widths: DiskUsageBarMath.SegmentWidths,
    totalLabel: String,
    usedLabel: String,
    modifier: Modifier = Modifier
) {
    val extra = LocalExtraPalette.current

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = usedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = extra.textSecondary
            )
            Text(
                text = totalLabel,
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(extra.textDisabled.copy(alpha = 0.2f))
        ) {
            if (widths.worldsFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(widths.worldsFraction)
                        .background(Color(0xFF4CAF50))  // green — worlds
                )
            }
            if (widths.modsFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(widths.modsFraction)
                        .background(extra.badgeEnhanced)  // purple — mods
                )
            }
            if (widths.backupsFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(widths.backupsFraction)
                        .background(Color(0xFFFFC107))  // amber — backups
                )
            }
            if (widths.configFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(widths.configFraction)
                        .background(Color(0xFF2196F3))  // blue — config
                )
            }
            if (widths.freeFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(widths.freeFraction)
                )
            }
        }
    }
}

/**
 * Pure math helper dla DiskUsageBar — testowalne bez Compose UI.
 */
object DiskUsageBarMath {
    data class SegmentWidths(
        val worldsFraction: Float,
        val modsFraction: Float,
        val backupsFraction: Float,
        val configFraction: Float,
        val freeFraction: Float
    )

    fun calculateSegmentWidths(
        totalGB: Float,
        worldsGB: Float,
        modsGB: Float,
        backupsGB: Float,
        configGB: Float
    ): SegmentWidths {
        if (totalGB <= 0f) {
            return SegmentWidths(0f, 0f, 0f, 0f, 1f)
        }
        val worlds = (worldsGB / totalGB).coerceIn(0f, 1f)
        val mods = (modsGB / totalGB).coerceIn(0f, 1f)
        val backups = (backupsGB / totalGB).coerceIn(0f, 1f)
        val config = (configGB / totalGB).coerceIn(0f, 1f)
        val used = (worlds + mods + backups + config).coerceIn(0f, 1f)
        val free = (1f - used).coerceAtLeast(0f)
        return SegmentWidths(worlds, mods, backups, config, free)
    }

    fun formatGB(gb: Float): String = String.format(Locale.ROOT, "%.1f GB", gb)
}
