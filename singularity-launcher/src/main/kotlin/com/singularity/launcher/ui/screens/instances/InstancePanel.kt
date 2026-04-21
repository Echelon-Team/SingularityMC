// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.common.model.InstanceType
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.ui.components.DiskUsageBar
import com.singularity.launcher.ui.components.DiskUsageBarMath
import com.singularity.launcher.ui.components.InstanceTypeBadge
import com.singularity.launcher.ui.components.RunState
import com.singularity.launcher.ui.components.StatusDot
import com.singularity.launcher.ui.navigation.LocalNavigator
import com.singularity.launcher.ui.screens.instances.settings.InstanceSettingsModal
import com.singularity.launcher.ui.screens.instances.tabs.ModsTabContent
import com.singularity.launcher.ui.screens.instances.tabs.PreGenTabContent
import com.singularity.launcher.ui.screens.instances.tabs.SimpleFileListTab
import com.singularity.launcher.ui.theme.LocalExtraPalette
import java.nio.file.Files
import kotlin.io.path.name

/**
 * Drill-down screen dla pojedynczej instancji.
 *
 * **Layout:**
 * 1. Header Surface: InstanceIcon gradient + info column (nazwa + badge + version + DiskUsageBar) + StatusDot + actions (Settings / GRAJ)
 * 2. Back button Row pod headerem
 * 3. ScrollableTabRow 9 zakładek
 * 4. Tab content Box
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancePanel(
    instanceId: String,
    instanceManager: InstanceManager,
    onLaunch: (String) -> Unit = { _ -> }
) {
    val vm = remember(instanceId, instanceManager) { InstancePanelViewModel(instanceManager, instanceId) }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    val state by vm.state.collectAsState()
    val navigator = LocalNavigator.current
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    var selectedTab by rememberSaveable { androidx.compose.runtime.mutableStateOf(InstanceTab.MODS) }
    var showSettings by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    when {
        state.isLoading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }

        state.error != null && state.instance == null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = state.error!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { navigator.backFromPanel() }) {
                    Text(i18n["action.back"])
                }
            }
        }

        state.instance != null -> Column(modifier = Modifier.fillMaxSize()) {
            val instance = state.instance!!
            // Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = extra.cardBg,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Gradient icon 48dp
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(extra.playGradientStart, extra.playGradientEnd)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = instance.config.name.firstOrNull()?.uppercase() ?: "?",
                            color = extra.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Info column
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = instance.config.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = extra.textPrimary
                            )
                            Spacer(Modifier.width(12.dp))
                            InstanceTypeBadge(instance.config.type)
                        }
                        Text(
                            text = "MC ${instance.config.minecraftVersion} • ${instance.modCount} ${i18n["instance_panel.mods_label"]}",
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.textMuted
                        )
                        Spacer(Modifier.height(8.dp))
                        // DiskUsageBar — real scan of instance directory
                        val diskInfo = remember(instance.rootDir) {
                            fun dirSizeGB(base: java.nio.file.Path, subpath: String): Float {
                                val dir = base.resolve(subpath)
                                if (!Files.exists(dir)) return 0f
                                return try {
                                    var total = 0L
                                    Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.forEach { total += Files.size(it) } }
                                    total / (1024f * 1024f * 1024f)
                                } catch (_: Exception) { 0f }
                            }
                            val root = instance.rootDir
                            val worldsGB = dirSizeGB(root, "minecraft/saves") + dirSizeGB(root, "minecraft/world")
                            val modsGB = dirSizeGB(root, "minecraft/mods") + dirSizeGB(root, "mods")
                            val backupsGB = dirSizeGB(root, "backups")
                            val configGB = dirSizeGB(root, "minecraft/config")
                            val totalUsed = worldsGB + modsGB + backupsGB + configGB
                            val totalGB = (totalUsed * 1.2f).coerceAtLeast(1f)
                            Triple(
                                DiskUsageBarMath.calculateSegmentWidths(totalGB, worldsGB, modsGB, backupsGB, configGB),
                                "%.1f GB".format(totalGB),
                                "%.1f GB ${i18n["instance_panel.disk.used"]}".format(totalUsed)
                            )
                        }
                        DiskUsageBar(
                            widths = diskInfo.first,
                            totalLabel = diskInfo.second,
                            usedLabel = diskInfo.third
                        )
                    }

                    // Status dot
                    StatusDot(
                        state = if (state.isLaunching) RunState.LOADING else RunState.STOPPED
                    )

                    // Actions
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = i18n["instance_panel.settings"])
                    }
                    Button(
                        onClick = { onLaunch(instanceId) },
                        enabled = !state.isLaunching
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(i18n["action.play"])
                    }
                }
            }

            // Launch progress bar — appears below header when launching
            if (state.isLaunching) {
                val progress = state.launchProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // Back button row
            TextButton(
                onClick = { navigator.backFromPanel() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(i18n["instance_panel.back_to_instances"])
            }

            // ScrollableTabRow
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                InstanceTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(i18n[tab.i18nKey]) }
                    )
                }
            }

            // Tab content
            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                val mcDir = instance.rootDir.resolve("minecraft")
                when (selectedTab) {
                    InstanceTab.MODS -> ModsTabContent(instance = instance)
                    InstanceTab.PRE_GEN -> PreGenTabContent(
                        settings = state.runtimeSettings,
                        onRadiusChange = vm::updatePreGenRadius,
                        onPresetSelect = vm::applyPreGenPreset
                    )
                    InstanceTab.RESOURCE_PACKS -> SimpleFileListTab(
                        folder = mcDir.resolve("resourcepacks"),
                        filter = { Files.isDirectory(it) || it.name.lowercase().endsWith(".zip") },
                        emptyStateTitleKey = "instance_panel.resource_packs.empty.title",
                        emptyStateSubtitleKey = "instance_panel.resource_packs.empty.subtitle",
                        headerCountKey = "instance_panel.resource_packs.count"
                    )
                    InstanceTab.SHADERS -> SimpleFileListTab(
                        folder = mcDir.resolve("shaderpacks"),
                        filter = { Files.isDirectory(it) || it.name.lowercase().endsWith(".zip") },
                        emptyStateTitleKey = "instance_panel.shaders.empty.title",
                        emptyStateSubtitleKey = "instance_panel.shaders.empty.subtitle",
                        headerCountKey = "instance_panel.shaders.count"
                    )
                    InstanceTab.DATAPACKS -> SimpleFileListTab(
                        folder = mcDir.resolve("saves"),
                        filter = { Files.isDirectory(it) && Files.exists(it.resolve("level.dat")) },
                        emptyStateTitleKey = "instance_panel.datapacks.no_worlds.title",
                        emptyStateSubtitleKey = "instance_panel.datapacks.no_worlds.subtitle",
                        headerCountKey = "instance_panel.datapacks.count"
                    )
                    InstanceTab.SERVERS -> com.singularity.launcher.ui.components.EmptyState(
                        title = i18n["instance_panel.servers.empty.title"],
                        subtitle = i18n["instance_panel.servers.empty.subtitle"]
                    )
                    InstanceTab.WORLDS -> SimpleFileListTab(
                        folder = mcDir.resolve("saves"),
                        filter = { Files.isDirectory(it) && Files.exists(it.resolve("level.dat")) },
                        emptyStateTitleKey = "instance_panel.worlds.empty.title",
                        emptyStateSubtitleKey = "instance_panel.worlds.empty.subtitle",
                        headerCountKey = "instance_panel.worlds.count",
                        iconForItem = { Icons.Default.Public }
                    )
                    InstanceTab.BACKUPS -> SimpleFileListTab(
                        folder = instance.rootDir.resolve(".singularity").resolve("backups"),
                        filter = { it.fileName.toString().lowercase().endsWith(".zip") },
                        emptyStateTitleKey = "instance_panel.backups.empty.title",
                        emptyStateSubtitleKey = "instance_panel.backups.empty.subtitle",
                        headerCountKey = "instance_panel.backups.count",
                        iconForItem = { Icons.Default.Archive }
                    )
                    InstanceTab.SCREENSHOTS -> SimpleFileListTab(
                        folder = mcDir.resolve("screenshots"),
                        filter = { it.fileName.toString().lowercase().endsWith(".png") },
                        emptyStateTitleKey = "instance_panel.screenshots.empty.title",
                        emptyStateSubtitleKey = "instance_panel.screenshots.empty.subtitle",
                        headerCountKey = "instance_panel.screenshots.count"
                    )
                }
            }
        }
    }

    // Settings modal
    if (showSettings && state.instance != null) {
        InstanceSettingsModal(
            instanceDir = state.instance!!.rootDir,
            onClose = { showSettings = false }
        )
    }
}
