// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.ModalDialog
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.theme.LocalExtraPalette
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Composable
fun ScreenshotPreviewDialog(
    entry: ScreenshotEntry,
    thumbnailCache: ScreenshotLruCache,
    onDismiss: () -> Unit
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    val image: ImageBitmap? = remember(entry.path) { thumbnailCache.loadOrCache(entry.path) }

    ModalDialog(
        title = "${entry.instanceName} / ${entry.filename}",
        onDismiss = onDismiss,
        size = ModalSize.XLARGE,
        actions = {
            OutlinedButton(onClick = { exportScreenshot(entry.path) }) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(i18n["screenshots.export"])
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDismiss) {
                Text(i18n["common.close"])
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = entry.filename,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = i18n["screenshots.preview.load_error"],
                        color = extra.textMuted
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${entry.sizeBytes / 1024} KB • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(entry.lastModified))}",
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted
            )
        }
    }
}

private fun exportScreenshot(source: Path) {
    val dialog = FileDialog(null as Frame?, "Save Screenshot", FileDialog.SAVE).apply {
        file = source.fileName.toString()
        isVisible = true
    }
    val dir = dialog.directory ?: return
    val filename = dialog.file ?: return
    val target = Path.of(dir, filename)
    try {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        // Silently fail — user saw file dialog, error handling minimalne
    }
}
