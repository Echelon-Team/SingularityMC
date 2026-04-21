// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.ui.components.EmptyState
import com.singularity.launcher.ui.theme.LocalExtraPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.io.path.name

/**
 * Content tab dla `InstanceTab.MODS` — lista plików .jar z `<instance>/minecraft/mods/`.
 *
 * **Sub 4 scope:** nazwa z jar filename, count, file size. Real fabric.mod.json / mods.toml
 * parser wymaga Task 26 InstanceModsScanner — Sub 5.
 */
@Composable
fun ModsTabContent(instance: InstanceManager.Instance) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    var mods by remember { mutableStateOf<List<ModEntry>>(emptyList()) }

    LaunchedEffect(instance.rootDir) {
        mods = withContext(Dispatchers.IO) {
            val modsDir = instance.rootDir.resolve("minecraft").resolve("mods")
            if (!Files.exists(modsDir) || !Files.isDirectory(modsDir)) return@withContext emptyList()
            try {
                Files.list(modsDir).use { stream ->
                    stream.filter { it.name.lowercase().endsWith(".jar") }
                        .map { path ->
                            ModEntry(
                                filename = path.name,
                                sizeBytes = try { Files.size(path) } catch (e: Exception) { 0L }
                            )
                        }
                        .toList()
                        .sortedBy { it.filename.lowercase() }
                }
            } catch (e: Exception) { emptyList() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = i18n["instance_panel.mods.count"].replace("{count}", mods.size.toString()),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = extra.textSecondary
        )
        Spacer(Modifier.height(12.dp))

        if (mods.isEmpty()) {
            EmptyState(
                title = i18n["instance_panel.mods.empty.title"],
                subtitle = i18n["instance_panel.mods.empty.subtitle"]
            )
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(mods, key = { it.filename }) { mod ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = extra.cardBg)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Extension, contentDescription = null, tint = extra.badgeEnhanced)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mod.filename,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = extra.textPrimary,
                                maxLines = 1
                            )
                            Text(
                                text = "${mod.sizeBytes / 1024} KB",
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.textMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class ModEntry(
    val filename: String,
    val sizeBytes: Long
)
