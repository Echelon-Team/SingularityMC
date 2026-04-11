package com.singularity.launcher.ui.screens.instances.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.InstanceRuntimeSettings
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.ModalDialog
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.theme.LocalExtraPalette
import java.nio.file.Path

/**
 * InstanceSettingsModal LARGE (720dp) z 4 horizontal tabs (General/Resources/Threading/ChunkGen).
 *
 * **Auto-load** przy open z `InstanceRuntimeSettingsStore.load(instanceDir)`.
 * **Isolated working state** — `originalSettings` vs `workingSettings`, `isDirty` computed.
 * **Revert button** — przywraca working do original.
 * **Save button** — disabled gdy !isDirty, wywołuje Store.save + onClose.
 *
 * Sub 4 MVP: simplified per-section UI. Pełna elegancja po dev-local sanity.
 */
@Composable
fun InstanceSettingsModal(
    instanceDir: Path,
    onClose: () -> Unit
) {
    val vm = remember(instanceDir) { InstanceSettingsModalViewModel(instanceDir) }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    val state by vm.state.collectAsState()
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    ModalDialog(
        title = i18n["instance_settings.title"],
        onDismiss = {
            // Jeśli user zamyka bez save, traktujemy jako revert (nie zapisuj)
            onClose()
        },
        size = ModalSize.LARGE,
        actions = {
            TextButton(
                onClick = { vm.revert() },
                enabled = state.isDirty
            ) {
                Text(i18n["instance_settings.revert"])
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClose) {
                Text(i18n["action.cancel"])
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.save(onDone = onClose) },
                enabled = state.isDirty && !state.isSaving
            ) {
                Text(i18n["action.save"])
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Horizontal tabs (P1 v1 fix — NIE lewy sidebar)
            TabRow(selectedTabIndex = state.currentTab.ordinal) {
                InstanceSettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == state.currentTab,
                        onClick = { vm.switchTab(tab) },
                        text = { Text(i18n[tab.i18nKey]) }
                    )
                }
            }

            // Tab content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (state.currentTab) {
                    InstanceSettingsTab.GENERAL -> GeneralTab(state.workingSettings)
                    InstanceSettingsTab.RESOURCES -> ResourcesTab(state.workingSettings)
                    InstanceSettingsTab.THREADING -> ThreadingTab(
                        settings = state.workingSettings,
                        onRegionSizeChange = vm::updateRegionSize,
                        onManualModeToggle = vm::toggleManualThreadMode
                    )
                    InstanceSettingsTab.CHUNK_GEN -> ChunkGenTab(
                        settings = state.workingSettings,
                        onGpuChange = vm::updateGpuAcceleration,
                        onUnloadDelayChange = vm::updateUnloadDelay,
                        onMemoryThresholdChange = vm::updateMemoryThreshold
                    )
                }

                // Save error
                state.saveError?.let { err ->
                    Text(
                        text = "${i18n["instance_settings.save_error"]}: $err",
                        color = extra.statusError
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneralTab(settings: InstanceRuntimeSettings) {
    val extra = LocalExtraPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "General Settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        Text(
            text = "General instance settings — name + version (read-only w Sub 4, edit w Sub 5 via InstanceManager.update)",
            color = extra.textMuted,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ResourcesTab(settings: InstanceRuntimeSettings) {
    val extra = LocalExtraPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Resources",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        Text(
            text = "RAM + threads + Java version config. Sub 4 MVP — edit via InstanceConfig (Task 11 model).",
            color = extra.textMuted,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ThreadingTab(
    settings: InstanceRuntimeSettings,
    onRegionSizeChange: (Int) -> Unit,
    onManualModeToggle: (Boolean) -> Unit
) {
    val extra = LocalExtraPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Threading",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )

        // Region size
        Column {
            Text("Region size", color = extra.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InstanceSettingsModalLogic.allowedRegionSizes.forEach { size ->
                    if (settings.regionSize == size) {
                        Button(onClick = { onRegionSizeChange(size) }) {
                            Text("${size}×${size}")
                        }
                    } else {
                        OutlinedButton(onClick = { onRegionSizeChange(size) }) {
                            Text("${size}×${size}")
                        }
                    }
                }
            }
        }

        // Manual mode toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Manual thread pool config",
                color = extra.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.manualThreadConfig != null,
                onCheckedChange = onManualModeToggle
            )
        }

        if (settings.manualThreadConfig != null) {
            Text(
                text = "Manual thread values (advanced) — 6 sliders (Sub 4 MVP: values are kept in state, full sliders UI in later iteration)",
                color = extra.textMuted,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                text = "Automatic thread pool allocation based on hardware",
                color = extra.textMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ChunkGenTab(
    settings: InstanceRuntimeSettings,
    onGpuChange: (Boolean) -> Unit,
    onUnloadDelayChange: (Int) -> Unit,
    onMemoryThresholdChange: (Int) -> Unit
) {
    val extra = LocalExtraPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Generowanie terenu",  // PN1 v1 fix
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )

        // GPU acceleration
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "GPU acceleration",
                color = extra.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = settings.gpuAcceleration, onCheckedChange = onGpuChange)
        }

        // Unload delay
        Column {
            Text("Chunk unload delay: ${settings.unloadDelaySec}s", color = extra.textSecondary)
            Spacer(Modifier.height(8.dp))
            Slider(
                value = settings.unloadDelaySec.toFloat(),
                onValueChange = { onUnloadDelayChange(it.toInt()) },
                valueRange = 60f..3600f
            )
        }

        // Memory threshold
        Column {
            Text("Memory threshold: ${settings.memoryThresholdPercent}%", color = extra.textSecondary)
            Spacer(Modifier.height(8.dp))
            Slider(
                value = settings.memoryThresholdPercent.toFloat(),
                onValueChange = { onMemoryThresholdChange(it.toInt()) },
                valueRange = 50f..95f
            )
        }
    }
}
