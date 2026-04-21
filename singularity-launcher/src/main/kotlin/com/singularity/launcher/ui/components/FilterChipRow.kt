// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable horizontal row of FilterChip z singleton selection (tylko jeden wybrany).
 *
 * **Usage:** InstancesScreen filter (ALL/ENHANCED/VANILLA), ScreenshotsScreen instance
 * filter, NewInstanceWizard step navigation. Każda opcja renderowana jako FilterChip.
 *
 * @param options lista label do wyświetlenia
 * @param selectedIndex aktualny wybrany index (0-based)
 * @param onSelect callback gdy user klika chip
 */
@Composable
fun FilterChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors()
            )
        }
    }
}
