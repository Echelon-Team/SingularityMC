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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.modrinth.ModrinthClient
import com.singularity.launcher.service.modrinth.ModrinthError
import com.singularity.launcher.service.modrinth.ModrinthSearchHit
import com.singularity.launcher.ui.components.EmptyState
import com.singularity.launcher.ui.components.ModalDialog
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.components.SearchBar
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * ModrinthScreen — live API browser dla modów z Modrinth.com.
 *
 * **Sub 4 MVP:** search + grid results + install dialog (wire install jest Sub 5 —
 * wymagane LibraryDownloader + mods folder wire do InstancePanel).
 *
 * Layout: toolbar z SearchBar + content grid / empty states / error banners.
 */
@Composable
fun ModrinthScreen(modrinthClient: ModrinthClient) {
    val vm = remember(modrinthClient) { ModrinthViewModel(modrinthClient) }
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

        Spacer(Modifier.height(16.dp))

        // Search bar
        SearchBar(
            value = state.query,
            onValueChange = vm::setQuery,
            placeholder = i18n["modrinth.search_placeholder"]
        )

        Spacer(Modifier.height(16.dp))

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
                    columns = GridCells.Adaptive(minSize = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(state.results, key = { it.projectId }) { hit ->
                        ModrinthCard(
                            hit = hit,
                            onInstallClick = { vm.openInstallDialog(hit) }
                        )
                    }
                }
            }
        }
    }

    // Install dialog
    val installDialog = state.installDialog
    if (installDialog != null) {
        VersionPickerDialog(
            title = installDialog.title,
            versions = installDialog.versions,
            onInstall = { /* Sub 5 — wire real install via LibraryDownloader */ vm.closeInstallDialog() },
            onDismiss = vm::closeInstallDialog
        )
    }
}

@Composable
private fun ModrinthCard(
    hit: ModrinthSearchHit,
    onInstallClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Column {
            // Banner placeholder gradient (AsyncImage w przyszłości)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(extra.playGradientStart, extra.playGradientEnd)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = hit.title.firstOrNull()?.uppercase() ?: "?",
                    color = extra.textPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Body
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = extra.textPrimary,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hit.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textMuted,
                    maxLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatDownloads(hit.downloads)} ${i18n["modrinth.downloads"]}",
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.textMuted
                    )
                    Button(onClick = onInstallClick) {
                        Text(i18n["modrinth.install"])
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionPickerDialog(
    title: String,
    versions: List<com.singularity.launcher.service.modrinth.ModrinthVersion>,
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
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(versions, key = { it.id }) { version ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onInstall(version) },
                    colors = CardDefaults.cardColors(containerColor = extra.cardBg)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
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
