package com.singularity.launcher.ui.screens.skins

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.EmptyState
import com.singularity.launcher.ui.components.InfoBox
import com.singularity.launcher.ui.components.InfoBoxStyle
import com.singularity.launcher.ui.theme.LocalExtraPalette
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

/**
 * Peer screen dla skinów — gallery + upload + preview.
 *
 * **Layout:** LazyVerticalGrid cards z preview każdego skin + upload button + selected skin preview.
 * **Premium gate (#19):** offline accounts disabled upload z banner.
 */
@Composable
fun SkinsScreen(
    skinsDir: Path,
    isPremiumProvider: () -> Boolean = { false }
) {
    val vm = remember(skinsDir) {
        SkinsViewModel(skinsDir = skinsDir, isPremiumProvider = isPremiumProvider)
    }
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
                text = i18n["skins.title"],
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = extra.textPrimary
            )
            Button(
                onClick = {
                    val dialog = FileDialog(null as Frame?, i18n["skins.upload.title"], FileDialog.LOAD).apply {
                        file = "*.png"
                        isVisible = true
                    }
                    val dir = dialog.directory
                    val filename = dialog.file
                    if (dir != null && filename != null) {
                        vm.uploadSkin(java.io.File(dir, filename))
                    }
                },
                enabled = state.canUpload
            ) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(i18n["skins.upload"])
            }
        }

        Spacer(Modifier.height(16.dp))

        // Premium warning (#19)
        if (!state.canUpload) {
            InfoBox(
                text = i18n["skins.premium_required"],
                style = InfoBoxStyle.WARNING
            )
            Spacer(Modifier.height(16.dp))
        }

        // Upload status
        state.uploadStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("Invalid") || status.startsWith("Upload failed"))
                    extra.statusError else extra.statusSuccess
            )
            Spacer(Modifier.height(8.dp))
        }

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.skins.isEmpty() -> EmptyState(
                    title = i18n["skins.empty.title"],
                    subtitle = i18n["skins.empty.subtitle"]
                )

                else -> Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Gallery
                    Box(modifier = Modifier.weight(1f)) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.skins, key = { it.path.toString() }) { skin ->
                                SkinCard(
                                    skin = skin,
                                    isSelected = state.selectedSkin?.path == skin.path,
                                    onClick = { vm.selectSkin(skin) }
                                )
                            }
                        }
                    }

                    // Preview
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        val selected = state.selectedSkin
                        if (selected != null) {
                            SkinPreview(skin = selected)
                        } else {
                            Text(
                                text = i18n["skins.select_to_preview"],
                                color = extra.textMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkinCard(
    skin: SkinEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val image = remember(skin.path) { loadSkinBitmap(skin.path) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isSelected)
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    // Head preview — 8x8 section from PNG (UV 8..16, 8..16)
                    Image(
                        bitmap = image,
                        contentDescription = skin.name,
                        modifier = Modifier.size(64.dp),
                        contentScale = ContentScale.FillBounds
                    )
                } else {
                    Box(modifier = Modifier.size(64.dp).background(extra.textDisabled.copy(alpha = 0.2f)))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = skin.name,
                style = MaterialTheme.typography.bodySmall,
                color = extra.textPrimary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SkinPreview(skin: SkinEntry) {
    val extra = LocalExtraPalette.current
    val image = remember(skin.path) { loadSkinBitmap(skin.path) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = skin.name,
                modifier = Modifier
                    .size(256.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.FillBounds
            )
        } else {
            Box(
                modifier = Modifier.size(256.dp).background(extra.textDisabled.copy(alpha = 0.2f))
            )
        }
        Text(
            text = skin.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        Text(
            text = skin.model.name,
            style = MaterialTheme.typography.bodySmall,
            color = extra.textMuted
        )
    }
}

private fun loadSkinBitmap(path: Path): ImageBitmap? {
    if (!Files.exists(path)) return null
    return try {
        val bytes = Files.readAllBytes(path)
        val awtImage = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
            ?: return null
        awtImage.toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}
