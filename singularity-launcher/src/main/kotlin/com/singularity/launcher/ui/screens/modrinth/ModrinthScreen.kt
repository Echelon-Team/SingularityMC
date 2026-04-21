// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.modrinth

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.service.modrinth.ModrinthClient
import com.singularity.launcher.service.modrinth.ModrinthError
import com.singularity.launcher.service.modrinth.ModrinthSearchHit
import com.singularity.launcher.ui.components.EmptyState
import com.singularity.launcher.ui.components.ModalDialog
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.components.SearchBar
import com.singularity.launcher.ui.theme.LocalExtraPalette
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun ModrinthScreen(
    modrinthClient: ModrinthClient,
    instanceManager: InstanceManager? = null,
    httpClient: HttpClient? = null
) {
    val vm = remember(modrinthClient) {
        ModrinthViewModel(modrinthClient, instanceManager, httpClient)
    }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    val state by vm.state.collectAsState()
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        // Header
        Text(
            text = i18n["nav.modrinth"],
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = extra.textPrimary
        )

        // Offline mode banner — spec §4.11. ModrinthClientImpl returns
        // empty list when OfflineMode.isEnabled(), so the search results
        // are always empty in this mode. Banner tells the user WHY
        // instead of making them think their query has zero matches.
        if (com.singularity.launcher.config.OfflineMode.isEnabled()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = i18n["modrinth.offline.banner"],
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search bar
        SearchBar(
            value = state.query,
            onValueChange = vm::setQuery,
            placeholder = i18n["modrinth.search_placeholder"]
        )

        Spacer(Modifier.height(12.dp))

        // Filter row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Version filter
            FilterDropdown(
                label = "MC ${state.gameVersion}",
                options = listOf("1.20.1", "1.20.4", "1.21", "1.21.1", "1.21.4"),
                selected = state.gameVersion,
                onSelect = vm::setGameVersion
            )

            // Loader filter
            FilterDropdown(
                label = state.loader?.replaceFirstChar { it.uppercase() } ?: i18n["modrinth.filter.all_loaders"],
                options = listOf(null, "fabric", "forge", "neoforge", "quilt"),
                optionLabels = listOf(
                    i18n["modrinth.filter.all_loaders"], "Fabric", "Forge", "NeoForge", "Quilt"
                ),
                selected = state.loader,
                onSelect = vm::setLoader
            )

            // Category filter
            FilterDropdown(
                label = state.category?.replaceFirstChar { it.uppercase() } ?: i18n["modrinth.filter.all_categories"],
                options = listOf(null, "optimization", "technology", "adventure", "decoration",
                    "library", "worldgen", "storage", "magic", "utility", "equipment"),
                optionLabels = listOf(
                    i18n["modrinth.filter.all_categories"], "Optimization", "Technology", "Adventure",
                    "Decoration", "Library", "World Gen", "Storage", "Magic", "Utility", "Equipment"
                ),
                selected = state.category,
                onSelect = vm::setCategory
            )

            // Sort
            FilterDropdown(
                label = when (state.sortMode) {
                    "relevance" -> i18n["modrinth.sort.relevance"]
                    "downloads" -> i18n["modrinth.sort.downloads"]
                    "updated" -> i18n["modrinth.sort.updated"]
                    "newest" -> i18n["modrinth.sort.newest"]
                    else -> state.sortMode
                },
                options = listOf("relevance", "downloads", "updated", "newest"),
                optionLabels = listOf(
                    i18n["modrinth.sort.relevance"], i18n["modrinth.sort.downloads"],
                    i18n["modrinth.sort.updated"], i18n["modrinth.sort.newest"]
                ),
                selected = state.sortMode,
                onSelect = vm::setSortMode
            )
        }

        Spacer(Modifier.height(12.dp))

        // Install progress banner
        val progress = state.installProgress
        if (progress != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = when (progress.status) {
                    InstallStatus.DOWNLOADING, InstallStatus.SCANNING -> extra.statusInfo.copy(alpha = 0.15f)
                    InstallStatus.DONE -> extra.statusSuccess.copy(alpha = 0.15f)
                    InstallStatus.ERROR -> extra.statusError.copy(alpha = 0.15f)
                }),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = when (progress.status) {
                            InstallStatus.DOWNLOADING -> "${i18n["modrinth.installing"]}: ${progress.modName}"
                            InstallStatus.SCANNING -> "${i18n["modrinth.scanning"]}: ${progress.modName}"
                            InstallStatus.DONE -> progress.message
                            InstallStatus.ERROR -> "${i18n["modrinth.install_error"]}: ${progress.message}"
                        },
                        color = when (progress.status) {
                            InstallStatus.ERROR -> extra.statusError
                            InstallStatus.DONE -> extra.statusSuccess
                            else -> extra.statusInfo
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (progress.status == InstallStatus.DOWNLOADING || progress.status == InstallStatus.SCANNING) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading && state.results.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.error is ModrinthError.NoQuery -> EmptyState(
                    title = i18n["modrinth.no_query.title"],
                    subtitle = i18n["modrinth.no_query.subtitle"]
                )

                state.error is ModrinthError.EmptyResults -> EmptyState(
                    title = i18n["modrinth.no_results.title"],
                    subtitle = i18n["modrinth.no_results.subtitle"]
                )

                state.error is ModrinthError.RateLimit -> EmptyState(
                    title = i18n["modrinth.rate_limit.title"],
                    subtitle = "${i18n["modrinth.rate_limit.subtitle"]}: ${(state.error as ModrinthError.RateLimit).retryAfterSec}s"
                )

                state.error is ModrinthError.Offline -> EmptyState(
                    title = i18n["modrinth.offline.title"],
                    subtitle = i18n["modrinth.offline.subtitle"]
                )

                state.error is ModrinthError.Network -> EmptyState(
                    title = i18n["modrinth.network.title"],
                    subtitle = (state.error as ModrinthError.Network).message
                )

                state.error is ModrinthError.Server -> EmptyState(
                    title = i18n["modrinth.server.title"],
                    subtitle = "HTTP ${(state.error as ModrinthError.Server).statusCode}"
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(state.results, key = { it.projectId }) { hit ->
                        ModrinthCard(
                            hit = hit,
                            httpClient = httpClient,
                            onInstallClick = { vm.openInstallDialog(hit) }
                        )
                    }
                }
            }
        }
    }

    // Install dialog with instance picker
    val installDialog = state.installDialog
    if (installDialog != null) {
        VersionPickerDialog(
            title = installDialog.title,
            versions = installDialog.versions,
            instances = state.availableInstances,
            selectedInstanceId = state.selectedInstanceId,
            onSelectInstance = vm::selectInstance,
            onInstall = { version -> vm.installMod(version) },
            onDismiss = vm::closeInstallDialog
        )
    }
}

@Composable
private fun <T> FilterDropdown(
    label: String,
    options: List<T>,
    optionLabels: List<String>? = null,
    selected: T,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(optionLabels?.getOrNull(index) ?: option.toString()) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ModrinthCard(
    hit: ModrinthSearchHit,
    httpClient: HttpClient?,
    onInstallClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            // Icon — load from URL or fallback
            ModIcon(iconUrl = hit.iconUrl, title = hit.title, httpClient = httpClient)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = extra.textPrimary,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hit.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textMuted,
                    maxLines = 2
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    hit.loaders.take(3).forEach { loader ->
                        Text(
                            text = loader,
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.textMuted,
                            modifier = Modifier
                                .background(extra.cardHover, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatDownloads(hit.downloads)} ${i18n["modrinth.downloads"]}",
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.textMuted
                    )
                    Button(
                        onClick = onInstallClick,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(i18n["modrinth.install"], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModIcon(iconUrl: String?, title: String, httpClient: HttpClient?) {
    val extra = LocalExtraPalette.current
    var image by remember(iconUrl) { mutableStateOf<ImageBitmap?>(null) }

    if (iconUrl != null && httpClient != null) {
        LaunchedEffect(iconUrl) {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    httpClient.get(iconUrl).readRawBytes()
                }
                val skiaImage = SkiaImage.makeFromEncoded(bytes)
                image = skiaImage.toComposeImageBitmap()
            } catch (_: Exception) {
                image = null
            }
        }
    }

    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image!!,
            contentDescription = title,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        // Fallback: first letter on gradient circle
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(extra.playGradientStart, extra.playGradientEnd))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.firstOrNull()?.uppercase() ?: "?",
                color = extra.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun VersionPickerDialog(
    title: String,
    versions: List<com.singularity.launcher.service.modrinth.ModrinthVersion>,
    instances: List<Pair<String, String>>,
    selectedInstanceId: String?,
    onSelectInstance: (String) -> Unit,
    onInstall: (com.singularity.launcher.service.modrinth.ModrinthVersion) -> Unit,
    onDismiss: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current

    ModalDialog(
        title = "$title — ${i18n["modrinth.pick_version"]}",
        onDismiss = onDismiss,
        size = ModalSize.LARGE,
        actions = {
            OutlinedButton(onClick = onDismiss) {
                Text(i18n["action.cancel"])
            }
        }
    ) {
        // Instance picker
        if (instances.isNotEmpty()) {
            Text(
                text = i18n["modrinth.select_instance"],
                style = MaterialTheme.typography.titleSmall,
                color = extra.textPrimary
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                instances.forEach { (id, name) ->
                    FilterChip(
                        selected = id == selectedInstanceId,
                        onClick = { onSelectInstance(id) },
                        label = { Text(name) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Version list
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(versions, key = { it.id }) { version ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (selectedInstanceId != null) onInstall(version)
                    },
                    colors = CardDefaults.cardColors(containerColor = extra.cardBg)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = version.name,
                                fontWeight = FontWeight.SemiBold,
                                color = extra.textPrimary
                            )
                            Text(
                                text = "${version.versionNumber} — ${version.gameVersions.joinToString(", ")} — ${version.loaders.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.textMuted
                            )
                        }
                        Button(
                            onClick = { onInstall(version) },
                            enabled = selectedInstanceId != null
                        ) {
                            Text(i18n["modrinth.install"])
                        }
                    }
                }
            }
        }
    }
}

private fun formatDownloads(count: Int): String = when {
    count < 1000 -> count.toString()
    count < 1_000_000 -> "${count / 1000}k"
    else -> "${count / 1_000_000}M"
}
