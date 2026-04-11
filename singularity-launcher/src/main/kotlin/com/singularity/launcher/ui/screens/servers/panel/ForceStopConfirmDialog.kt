package com.singularity.launcher.ui.screens.servers.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.InfoBox
import com.singularity.launcher.ui.components.InfoBoxStyle
import com.singularity.launcher.ui.components.ModalDialog
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Confirm dialog dla Force Stop action (#15 edge-case CRITICAL).
 *
 * **Warning:** "Świat może ulec uszkodzeniu" — force stop bypass graceful shutdown
 * (nie flush saves, nie close connections). User musi confirm świadomie.
 */
@Composable
fun ForceStopConfirmDialog(
    serverName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    ModalDialog(
        title = i18n["server_panel.force_stop_confirm.title"],
        onDismiss = onDismiss,
        size = ModalSize.SMALL,
        actions = {
            OutlinedButton(onClick = onDismiss) {
                Text(i18n["action.cancel"])
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(i18n["server_panel.force_stop_confirm.confirm"])
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = i18n["server_panel.force_stop_confirm.message"].replace("{name}", serverName),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textPrimary
            )
            InfoBox(
                text = i18n["server_panel.force_stop_confirm.warning"],
                style = InfoBoxStyle.WARNING
            )
        }
    }
}
