package com.singularity.launcher.ui.screens.instances.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.common.model.InstanceType
import com.singularity.common.model.LoaderType
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.InfoBox
import com.singularity.launcher.ui.components.InfoBoxStyle
import com.singularity.launcher.ui.components.InstanceTypeBadge
import com.singularity.launcher.ui.components.RamSlider
import com.singularity.launcher.ui.components.ThreadsSlider
import com.singularity.launcher.ui.di.LocalMojangClient
import com.singularity.launcher.ui.theme.LocalExtraPalette
import java.util.Locale

@Composable
fun NameStep(state: NewInstanceForm, onUpdate: (NewInstanceForm) -> Unit) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = i18n["wizard.new_instance.name.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        Text(
            text = i18n["wizard.new_instance.name.subtitle"],
            style = MaterialTheme.typography.bodySmall,
            color = extra.textMuted
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.name,
            onValueChange = { onUpdate(state.copy(name = it)) },
            placeholder = { Text(i18n["wizard.new_instance.name.placeholder"]) },
            singleLine = true,
            isError = state.name.length > 64,
            supportingText = {
                if (state.name.length > 64) {
                    Text(i18n["wizard.new_instance.name.too_long"])
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun VersionStep(state: NewInstanceForm, onUpdate: (NewInstanceForm) -> Unit) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    var versions by remember { mutableStateOf<List<VersionOption>>(emptyList()) }
    val mojangClient = LocalMojangClient.current

    LaunchedEffect(mojangClient) {
        versions = NewInstanceWizardLogic.loadVersionOptions(mojangClient)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = i18n["wizard.new_instance.version.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        Text(
            text = i18n["wizard.new_instance.version.subtitle"],
            style = MaterialTheme.typography.bodySmall,
            color = extra.textMuted
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 350.dp).fillMaxWidth()
        ) {
            items(versions, key = { it.id }) { version ->
                val isSelected = state.selectedVersion?.id == version.id
                val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
                                  else extra.textDisabled.copy(alpha = 0.3f)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            else extra.cardBg
                        )
                        .selectable(
                            selected = isSelected,
                            onClick = { onUpdate(state.copy(selectedVersion = version)) }
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = version.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = extra.textPrimary
                        )
                        if (version.subtitle.isNotEmpty()) {
                            Text(
                                text = version.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.textMuted
                            )
                        }
                    }
                    InstanceTypeBadge(version.type)
                }
            }
        }
    }
}

@Composable
fun LoaderStep(state: NewInstanceForm, onUpdate: (NewInstanceForm) -> Unit) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = i18n["wizard.new_instance.loader.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )

        val version = state.selectedVersion
        if (version?.type == InstanceType.ENHANCED) {
            InfoBox(
                text = i18n["wizard.new_instance.loader.enhanced.info"],
                style = InfoBoxStyle.INFO
            )
            Spacer(Modifier.height(8.dp))
            InfoBox(
                text = i18n["wizard.new_instance.loader.enhanced.server_warning"],
                style = InfoBoxStyle.WARNING
            )
        } else {
            Text(
                text = i18n["wizard.new_instance.loader.vanilla.subtitle"],
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted
            )
            val options = listOf(
                LoaderType.NONE to i18n["wizard.new_instance.loader.option.none"],
                LoaderType.FABRIC to i18n["wizard.new_instance.loader.option.fabric"],
                LoaderType.FORGE to i18n["wizard.new_instance.loader.option.forge"],
                LoaderType.NEOFORGE to i18n["wizard.new_instance.loader.option.neoforge"]
            )
            options.forEach { (loader, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.loader == loader,
                            onClick = { onUpdate(state.copy(loader = loader)) }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.loader == loader,
                        onClick = { onUpdate(state.copy(loader = loader)) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = label, color = extra.textPrimary)
                }
            }
        }
    }
}

@Composable
fun ResourcesStep(state: NewInstanceForm, onUpdate: (NewInstanceForm) -> Unit) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = i18n["wizard.new_instance.resources.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        Text(
            text = i18n["wizard.new_instance.resources.subtitle"],
            style = MaterialTheme.typography.bodySmall,
            color = extra.textMuted
        )

        RamSlider(
            value = state.ramMb,
            onValueChange = { onUpdate(state.copy(ramMb = it)) },
            label = i18n["wizard.new_instance.resources.ram"]
        )

        ThreadsSlider(
            value = state.threads,
            onValueChange = { onUpdate(state.copy(threads = it)) },
            label = i18n["wizard.new_instance.resources.threads"]
        )
    }
}

@Composable
fun SummaryStep(state: NewInstanceForm, onUpdate: (NewInstanceForm) -> Unit) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current
    val version = state.selectedVersion

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = i18n["wizard.new_instance.summary.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        Text(
            text = i18n["wizard.new_instance.summary.subtitle"],
            style = MaterialTheme.typography.bodySmall,
            color = extra.textMuted
        )
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = extra.cardBg)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryRow(i18n["wizard.new_instance.summary.name"], state.name)
                SummaryRow(
                    i18n["wizard.new_instance.summary.version"],
                    version?.label ?: "—"
                )
                val loaderLabel = if (version?.type == InstanceType.ENHANCED) {
                    i18n["wizard.new_instance.summary.loader.enhanced_auto"]
                } else when (state.loader) {
                    LoaderType.NONE -> i18n["wizard.new_instance.loader.option.none"]
                    LoaderType.FABRIC -> i18n["wizard.new_instance.loader.option.fabric"]
                    LoaderType.FORGE -> i18n["wizard.new_instance.loader.option.forge"]
                    LoaderType.NEOFORGE -> i18n["wizard.new_instance.loader.option.neoforge"]
                    else -> state.loader.name
                }
                SummaryRow(i18n["wizard.new_instance.summary.loader"], loaderLabel)
                val gb = String.format(Locale.ROOT, "%.1f", state.ramMb / 1024f)
                SummaryRow(
                    i18n["wizard.new_instance.summary.ram"],
                    "${state.ramMb} MB ($gb GB)"
                )
                SummaryRow(
                    i18n["wizard.new_instance.summary.threads"],
                    "${state.threads}"
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
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
