package com.singularity.launcher.ui.screens.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.ui.components.EmptyState
import com.singularity.launcher.ui.components.FilterChipRow
import com.singularity.launcher.ui.theme.LocalExtraPalette

@Composable
fun ScreenshotsScreen(instanceManager: InstanceManager) {
    val vm = remember(instanceManager) { ScreenshotsViewModel(instanceManager) }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    val state by vm.state.collectAsState()
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = i18n["screenshots.title"],
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = extra.textPrimary
            )
            IconButton(onClick = vm::refresh) {
                Icon(Icons.Default.Refresh, contentDescription = i18n["common.refresh"])
            }
        }

        Spacer(Modifier.height(16.dp))

        // Instance filter (#21 edge-case)
        if (state.availableInstances.isNotEmpty()) {
            val filterOptions = listOf(i18n["screenshots.filter.all"]) +
                state.availableInstances.map { it.second }
            val selectedIndex = if (state.instanceFilter == null) 0
                else state.availableInstances.indexOfFirst { it.first == state.instanceFilter } + 1

            FilterChipRow(
                options = filterOptions,
                selectedIndex = selectedIndex,
                onSelect = { idx ->
                    if (idx == 0) vm.setInstanceFilter(null)
                    else vm.setInstanceFilter(state.availableInstances[idx - 1].first)
                }
            )
            Spacer(Modifier.height(16.dp))
        }

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.filteredEntries.isEmpty() -> EmptyState(
                    title = i18n["screenshots.empty.title"],
                    subtitle = i18n["screenshots.empty.subtitle"]
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(220.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.filteredEntries, key = { it.path.toString() }) { entry ->
                        ScreenshotThumbnail(
                            entry = entry,
                            cache = vm.thumbnailCache,
                            onClick = { vm.openPreview(entry) }
                        )
                    }
                }
            }
        }
    }

    state.previewEntry?.let { entry ->
        ScreenshotPreviewDialog(
            entry = entry,
            thumbnailCache = vm.thumbnailCache,
            onDismiss = vm::closePreview
        )
    }
}

@Composable
private fun ScreenshotThumbnail(
    entry: ScreenshotEntry,
    cache: ScreenshotLruCache,
    onClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val image = remember(entry.path) { cache.loadOrCache(entry.path) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Column {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = entry.filename,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(extra.textDisabled.copy(alpha = 0.2f))
                )
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = entry.filename,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textPrimary,
                    maxLines = 1
                )
                Text(
                    text = entry.instanceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textMuted,
                    maxLines = 1
                )
            }
        }
    }
}
