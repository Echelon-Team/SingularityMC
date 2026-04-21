// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.InstanceRuntimeSettings
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.config.PreGenPreset
import com.singularity.launcher.ui.screens.instances.PreGenStateLogic
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Content tab dla `InstanceTab.PRE_GEN` — Start/Pauza/Stop + radius slider 16..256 + preset chips.
 *
 * **Sub 4 scope:** GUI real + persistence (radius + preset w InstanceRuntimeSettings). Backend
 * (actual chunk pre-generation) = Sub 5 via agent IPC.
 */
@Composable
fun PreGenTabContent(
    settings: InstanceRuntimeSettings,
    onRadiusChange: (Int) -> Unit,
    onPresetSelect: (PreGenPreset) -> Unit
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    val detectedPreset = PreGenStateLogic.detectPresetFromRadius(settings.preGenRadius)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = i18n["pre_gen.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )

        // Action buttons — Start/Pauza/Stop (Sub 5 wire)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { /* Sub 5 — wire do IpcClient preGenStart */ }) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(i18n["pre_gen.action.start"])
            }
            OutlinedButton(onClick = { /* Sub 5 — wire do IpcClient preGenPause */ }) {
                Icon(Icons.Default.Pause, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(i18n["pre_gen.action.pause"])
            }
            OutlinedButton(onClick = { /* Sub 5 — wire do IpcClient preGenStop */ }) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(i18n["pre_gen.action.stop"])
            }
        }

        // Radius slider 16..256
        Column {
            Text(
                text = "${i18n["pre_gen.radius"]}: ${settings.preGenRadius} ${i18n["pre_gen.chunks"]}",
                color = extra.textSecondary
            )
            Spacer(Modifier.height(4.dp))
            Slider(
                value = settings.preGenRadius.toFloat(),
                onValueChange = { onRadiusChange(it.toInt()) },
                valueRange = 16f..256f,
                steps = 240 - 1  // 240 steps between 16..256
            )
        }

        // Preset chips
        Column {
            Text(
                text = i18n["pre_gen.presets"],
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreGenPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = detectedPreset == preset,
                        onClick = { onPresetSelect(preset) },
                        label = { Text(i18n[preset.displayKey]) }
                    )
                }
            }
        }

        // Description of selected preset (if any)
        detectedPreset?.let { preset ->
            Text(
                text = i18n[preset.descriptionKey],
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted
            )
        }
    }
}
