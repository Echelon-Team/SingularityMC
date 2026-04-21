// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.servers.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.ui.components.InfoBox
import com.singularity.launcher.ui.components.InfoBoxStyle
import com.singularity.launcher.ui.components.RamSlider
import com.singularity.launcher.ui.components.ThreadsSlider
import com.singularity.launcher.ui.theme.LocalExtraPalette

@Composable
fun InstanceStep(
    state: NewServerForm,
    onUpdate: (NewServerForm) -> Unit,
    availableInstances: List<InstanceManager.Instance>
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = i18n["new_server_wizard.instance.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        Text(
            text = i18n["new_server_wizard.instance.subtitle"],
            style = MaterialTheme.typography.bodySmall,
            color = extra.textMuted
        )
        Spacer(Modifier.height(8.dp))

        if (availableInstances.isEmpty()) {
            // #13 edge-case — empty instances list
            InfoBox(
                text = i18n["new_server_wizard.instance.empty"],
                style = InfoBoxStyle.WARNING
            )
        } else {
            availableInstances.forEach { instance ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.selectedInstanceId == instance.id,
                            onClick = { onUpdate(state.copy(
                                selectedInstanceId = instance.id,
                                mcVersion = instance.config.minecraftVersion
                            )) }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.selectedInstanceId == instance.id,
                        onClick = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = instance.config.name,
                            color = extra.textPrimary
                        )
                        Text(
                            text = "MC ${instance.config.minecraftVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.textMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ServerNameStep(state: NewServerForm, onUpdate: (NewServerForm) -> Unit) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = i18n["new_server_wizard.name.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = { onUpdate(state.copy(name = it)) },
            placeholder = { Text(i18n["new_server_wizard.name.placeholder"]) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun ServerVersionStep(state: NewServerForm, onUpdate: (NewServerForm) -> Unit) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = i18n["new_server_wizard.version.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        Text(
            text = i18n["new_server_wizard.version.subtitle"],
            style = MaterialTheme.typography.bodySmall,
            color = extra.textMuted
        )
        OutlinedTextField(
            value = state.mcVersion,
            onValueChange = { onUpdate(state.copy(mcVersion = it)) },
            singleLine = true,
            readOnly = state.selectedInstanceId != null,  // auto-locked to parent instance version
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Loader step — w Sub 4 Enhanced option jest DISABLED z banner "Wymaga Sub 5".
 */
@Composable
fun ServerLoaderStep(state: NewServerForm, onUpdate: (NewServerForm) -> Unit) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = i18n["new_server_wizard.loader.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )

        // Vanilla radio (enabled)
        Row(
            modifier = Modifier.fillMaxWidth().selectable(
                selected = !state.enhancedMode,
                onClick = { onUpdate(state.copy(enhancedMode = false)) }
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = !state.enhancedMode, onClick = null)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(i18n["new_server_wizard.loader.vanilla"], color = extra.textPrimary)
                Text(
                    text = i18n["new_server_wizard.loader.vanilla.desc"],
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textMuted
                )
            }
        }

        // Enhanced radio (disabled z banner Sub 5)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = false, onClick = null, enabled = false)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = i18n["new_server_wizard.loader.enhanced"],
                    color = extra.textDisabled
                )
                Text(
                    text = i18n["new_server_wizard.loader.enhanced.desc"],
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textDisabled
                )
            }
        }

        InfoBox(
            text = i18n["new_server_wizard.loader.enhanced_disabled"],
            style = InfoBoxStyle.WARNING
        )
    }
}

@Composable
fun ServerModsCopyStep(state: NewServerForm, onUpdate: (NewServerForm) -> Unit) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = i18n["new_server_wizard.mods_copy.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        InfoBox(
            text = i18n["new_server_wizard.mods_copy.info"],
            style = InfoBoxStyle.INFO
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = state.copyServerSideMods,
                onCheckedChange = { onUpdate(state.copy(copyServerSideMods = it)) }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = i18n["new_server_wizard.mods_copy.toggle"],
                color = extra.textPrimary
            )
        }
    }
}

@Composable
fun ServerPortRamStep(
    state: NewServerForm,
    onUpdate: (NewServerForm) -> Unit,
    usedPorts: Set<Int>
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = i18n["new_server_wizard.port.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )

        // Port field
        Column {
            Text(i18n["new_server_wizard.port.label"], color = extra.textSecondary)
            OutlinedTextField(
                value = state.port.toString(),
                onValueChange = { newVal ->
                    val parsed = newVal.toIntOrNull() ?: 0
                    onUpdate(state.copy(port = parsed))
                },
                singleLine = true,
                isError = state.port !in 1..65535 || state.port in usedPorts,
                supportingText = {
                    when {
                        state.port !in 1..65535 -> Text(i18n["new_server_wizard.port.invalid"])
                        state.port in usedPorts -> Text(i18n["new_server_wizard.port.conflict"])
                        else -> {}
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        RamSlider(
            value = state.ramMb,
            onValueChange = { onUpdate(state.copy(ramMb = it)) },
            label = i18n["new_server_wizard.ram"]
        )

        ThreadsSlider(
            value = state.threads,
            onValueChange = { onUpdate(state.copy(threads = it)) },
            label = i18n["new_server_wizard.threads"]
        )
    }
}

@Composable
fun ServerSummaryStep(state: NewServerForm, onUpdate: (NewServerForm) -> Unit) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = i18n["new_server_wizard.summary.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = extra.cardBg)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryRowItem(i18n["new_server_wizard.summary.name"], state.name)
                SummaryRowItem(i18n["new_server_wizard.summary.version"], state.mcVersion)
                SummaryRowItem(
                    i18n["new_server_wizard.summary.parent_instance"],
                    state.selectedInstanceId ?: "—"
                )
                SummaryRowItem(i18n["new_server_wizard.summary.port"], state.port.toString())
                SummaryRowItem(i18n["new_server_wizard.summary.ram"], "${state.ramMb} MB")
                SummaryRowItem(i18n["new_server_wizard.summary.threads"], state.threads.toString())
                SummaryRowItem(
                    i18n["new_server_wizard.summary.mods_copy"],
                    if (state.copyServerSideMods) i18n["common.yes"] else i18n["common.no"]
                )
            }
        }
    }
}

@Composable
private fun SummaryRowItem(label: String, value: String) {
    val extra = LocalExtraPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "• $label",
            style = MaterialTheme.typography.bodyMedium,
            color = extra.textMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
    }
}
