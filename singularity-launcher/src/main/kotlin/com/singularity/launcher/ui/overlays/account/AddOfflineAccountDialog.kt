package com.singularity.launcher.ui.overlays.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.ModalDialog
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Dialog SMALL (420dp) dla dodawania offline account — input field z nickiem +
 * validation (blank + max 16 chars) + Utwórz / Anuluj buttons.
 */
@Composable
fun AddOfflineAccountDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current
    var nick by remember { mutableStateOf("") }

    ModalDialog(
        title = i18n["account_overlay.add_offline.title"],
        onDismiss = onDismiss,
        size = ModalSize.SMALL,
        actions = {
            OutlinedButton(onClick = onDismiss) {
                Text(i18n["action.cancel"])
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onCreate(nick) },
                enabled = nick.isNotBlank() && nick.length <= 16
            ) {
                Text(i18n["account_overlay.add_offline.create"])
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = i18n["account_overlay.add_offline.subtitle"],
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textMuted
            )

            OutlinedTextField(
                value = nick,
                onValueChange = { nick = it.take(16) },  // Cap at 16
                label = { Text(i18n["account_overlay.add_offline.nick"]) },
                singleLine = true,
                isError = error != null,
                supportingText = if (error != null) {
                    { Text(error, color = extra.statusError) }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = i18n["account_overlay.add_offline.note"],
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted
            )
        }
    }
}
