package com.singularity.launcher.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.ModalDialog
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * UpdateNotificationDialog MEDIUM (550dp) — nowa wersja launchera dostępna.
 *
 * Pokazuje current vs new version + scrollable changelog + "Aktualizuj teraz" / "Później".
 * Wywoływany po starcie launchera gdy `UpdateChecker` znajdzie nowszą wersję
 * (Sub 5 integration — w Sub 4 jedynie struktura bez faktycznego update checking).
 */
@Composable
fun UpdateNotificationDialog(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    ModalDialog(
        title = i18n["dialog.update.title"],
        onDismiss = onDismiss,
        size = ModalSize.MEDIUM,
        actions = {
            OutlinedButton(onClick = onLater) {
                Text(i18n["dialog.update.later"])
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onUpdate) {
                Text(i18n["dialog.update.update_now"])
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = i18n["dialog.update.message"]
                    .replace("{current}", info.currentVersion)
                    .replace("{new}", info.newVersion),
                style = MaterialTheme.typography.bodyLarge,
                color = extra.textPrimary
            )

            Text(
                text = i18n["dialog.update.changelog"],
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = extra.textPrimary
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(extra.cardBg)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = info.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textSecondary
                )
            }
        }
    }
}
