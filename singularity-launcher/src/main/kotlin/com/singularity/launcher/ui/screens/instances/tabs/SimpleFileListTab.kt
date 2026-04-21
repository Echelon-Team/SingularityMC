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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.EmptyState
import com.singularity.launcher.ui.theme.LocalExtraPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name

/**
 * Reusable file list tab content — wyświetla zawartość folderu (pliki pasujące do filter).
 *
 * **Features:**
 * - Auto-refresh przy rekompozycji (LaunchedEffect(folder))
 * - Open-in-system button per item (Desktop.getDesktop().open())
 * - Empty state gdy 0 plików
 * - Header counter "N plików"
 */
@Composable
fun SimpleFileListTab(
    folder: Path,
    filter: (Path) -> Boolean = { true },
    emptyStateTitleKey: String,
    emptyStateSubtitleKey: String,
    headerCountKey: String,
    iconForItem: (Path) -> ImageVector = { if (Files.isDirectory(it)) Icons.Default.Folder else Icons.Default.InsertDriveFile }
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current
    var items by remember { mutableStateOf<List<Path>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(folder) {
        isLoading = true
        items = withContext(Dispatchers.IO) {
            try {
                if (!Files.exists(folder) || !Files.isDirectory(folder)) return@withContext emptyList()
                Files.list(folder).use { stream ->
                    stream.filter(filter::invoke).toList()
                        .sortedWith(compareBy({ !Files.isDirectory(it) }, { it.name.lowercase() }))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = i18n[headerCountKey].replace("{count}", items.size.toString()),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = extra.textSecondary
        )
        Spacer(Modifier.height(12.dp))

        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            items.isEmpty() -> EmptyState(
                title = i18n[emptyStateTitleKey],
                subtitle = i18n[emptyStateSubtitleKey]
            )

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.toString() }) { path ->
                    FileRow(path = path, icon = iconForItem(path))
                }
            }
        }
    }
}

@Composable
private fun FileRow(path: Path, icon: ImageVector) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current
    val size = remember(path) {
        try {
            if (Files.isDirectory(path)) null else Files.size(path)
        } catch (e: Exception) { null }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = extra.textMuted)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = path.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = extra.textPrimary,
                    maxLines = 1
                )
                if (size != null) {
                    Text(
                        text = formatSize(size),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.textMuted
                    )
                }
            }
            IconButton(onClick = {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(path.toFile())
                    }
                } catch (e: Exception) {
                    // Silently fail — user nie widzi error ale może manualnie otworzyć
                }
            }) {
                Icon(Icons.Default.FolderOpen, contentDescription = i18n["common.open"])
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
