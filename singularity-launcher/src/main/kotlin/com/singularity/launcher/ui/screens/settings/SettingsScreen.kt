package com.singularity.launcher.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.config.LauncherSettings
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.config.UpdateChannel
import com.singularity.launcher.ui.navigation.SettingsSection
import com.singularity.launcher.ui.theme.LocalExtraPalette
import com.singularity.launcher.ui.theme.ThemeMode

/**
 * Settings peer screen — 5 sekcji (Appearance, Performance, Updates, Integrations, Advanced).
 * Sub-nav po lewej (Column z SettingsSection.entries), content po prawej.
 *
 * **Sub 4 MVP**: podstawowa struktura 5 sekcji z auto-save przez SettingsViewModel. Każda
 * sekcja ma niezbędne pola (theme toggle, language, update channel, JVM args, etc.).
 * Pełna elegancka rozbudowa (per-section dedicated composables z Figmy) to enhancement
 * po Sub 4 dev-local sanity check.
 *
 * **Auto-save:** każda mutacja triggeruje `viewModelScope.launch { saveSettings(...) }` —
 * user nie musi klikać "Save", wszystkie zmiany są persisted natychmiast.
 */
@Composable
fun SettingsScreen(
    loadSettings: () -> LauncherSettings,
    saveSettings: (LauncherSettings) -> Unit
) {
    val vm = remember { SettingsViewModel(loadSettings, saveSettings) }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    val state by vm.state.collectAsState()
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Row(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        // Sub-nav (left column, 240dp)
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .padding(end = 16.dp)
        ) {
            Text(
                text = i18n["nav.settings"],
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = extra.textPrimary
            )
            Spacer(Modifier.height(24.dp))

            SettingsSection.entries.forEach { section ->
                val isActive = section == state.currentSection
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isActive) extra.sidebarActive else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { vm.setSection(section) }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = i18n[section.displayKey],
                        color = if (isActive) extra.textPrimary else extra.textMuted,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // Vertical divider
        Divider(
            modifier = Modifier.fillMaxHeight().width(1.dp),
            color = extra.sidebarBorder
        )

        // Content (right, scrollable)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp)
        ) {
            when (state.currentSection) {
                SettingsSection.APPEARANCE -> AppearanceSection(
                    settings = state.settings,
                    onThemeChange = vm::setTheme,
                    onLanguageChange = vm::setLanguage
                )
                SettingsSection.PERFORMANCE -> PerformanceSection(state.settings)
                SettingsSection.UPDATES -> UpdatesSection(
                    settings = state.settings,
                    onChannelChange = vm::setUpdateChannel,
                    onAutoCheckChange = vm::setAutoCheckUpdates
                )
                SettingsSection.INTEGRATIONS -> IntegrationsSection(state.settings)
                SettingsSection.ADVANCED -> AdvancedSection(
                    settings = state.settings,
                    onJvmArgsChange = vm::setJvmExtraArgs,
                    onDebugLogsChange = vm::setDebugLogsEnabled
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    val extra = LocalExtraPalette.current
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = extra.textPrimary
    )
}

@Composable
private fun AppearanceSection(
    settings: LauncherSettings,
    onThemeChange: (ThemeMode) -> Unit,
    onLanguageChange: (String) -> Unit
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionHeader(i18n["settings.appearance"])

        // Theme
        Column {
            Text(i18n["settings.appearance.theme"], color = extra.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeToggleButton(
                    label = i18n["settings.appearance.theme.end"],
                    selected = settings.theme == ThemeMode.END,
                    onClick = { onThemeChange(ThemeMode.END) }
                )
                ThemeToggleButton(
                    label = i18n["settings.appearance.theme.aether"],
                    selected = settings.theme == ThemeMode.AETHER,
                    onClick = { onThemeChange(ThemeMode.AETHER) }
                )
            }
        }

        // Language
        Column {
            Text(i18n["settings.appearance.language"], color = extra.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeToggleButton(
                    label = "Polski",
                    selected = settings.language == "pl",
                    onClick = { onLanguageChange("pl") }
                )
                ThemeToggleButton(
                    label = "English",
                    selected = settings.language == "en",
                    onClick = { onLanguageChange("en") }
                )
            }
        }
    }
}

@Composable
private fun ThemeToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun PerformanceSection(settings: LauncherSettings) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader(i18n["settings.performance"])
        Text(
            text = "Performance settings will expand in Sub 5 ResourceManager (dynamic instance allocation, overbook warnings, preferred configs).",
            color = extra.textMuted
        )
    }
}

@Composable
private fun UpdatesSection(
    settings: LauncherSettings,
    onChannelChange: (UpdateChannel) -> Unit,
    onAutoCheckChange: (Boolean) -> Unit
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionHeader(i18n["settings.updates"])

        // Channel
        Column {
            Text("Update channel", color = extra.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeToggleButton(
                    label = "Stable",
                    selected = settings.updateChannel == UpdateChannel.STABLE,
                    onClick = { onChannelChange(UpdateChannel.STABLE) }
                )
                ThemeToggleButton(
                    label = "Beta",
                    selected = settings.updateChannel == UpdateChannel.BETA,
                    onClick = { onChannelChange(UpdateChannel.BETA) }
                )
            }
        }

        // Auto-check
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-check for updates", color = extra.textPrimary, modifier = Modifier.weight(1f))
            Switch(checked = settings.autoCheckUpdates, onCheckedChange = onAutoCheckChange)
        }
    }
}

@Composable
private fun IntegrationsSection(settings: LauncherSettings) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader(i18n["settings.integrations"])

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = extra.statusWarning.copy(alpha = 0.12f))
        ) {
            Text(
                text = "⚠ Discord Rich Presence — wymaga Sub 5 (integracja agentowa)",
                color = extra.textPrimary,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Discord Rich Presence", color = extra.textMuted, modifier = Modifier.weight(1f))
            Switch(checked = false, onCheckedChange = {}, enabled = false)
        }
    }
}

@Composable
private fun AdvancedSection(
    settings: LauncherSettings,
    onJvmArgsChange: (String) -> Unit,
    onDebugLogsChange: (Boolean) -> Unit
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionHeader(i18n["settings.advanced"])

        // JVM extra args
        Column {
            Text("Extra JVM args (global default)", color = extra.textSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.jvmExtraArgs,
                onValueChange = onJvmArgsChange,
                placeholder = { Text("-XX:+UseZGC -XX:+UnlockExperimentalVMOptions") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Debug logs
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Debug logs enabled", color = extra.textPrimary, modifier = Modifier.weight(1f))
            Switch(checked = settings.debugLogsEnabled, onCheckedChange = onDebugLogsChange)
        }
    }
}
