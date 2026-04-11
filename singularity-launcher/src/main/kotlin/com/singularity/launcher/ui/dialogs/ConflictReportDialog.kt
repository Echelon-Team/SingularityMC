package com.singularity.launcher.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.ModalDialog
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.components.TextBadge
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * ConflictReportDialog XLARGE (900dp) — lista konfliktów modów z severity + actions.
 *
 * Używany po kliknięciu GRAJ gdy ModConflictDetector (Sub 5) znajdzie konflikty między
 * zainstalowanymi modami. User może wybrać "Uruchom mimo to" (error-colored, risky)
 * lub "Wróć do modów" aby posortować.
 */
@Composable
fun ConflictReportDialog(
    conflicts: List<ModConflict>,
    onRunAnyway: () -> Unit,
    onBackToMods: () -> Unit,
    onDismiss: () -> Unit
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    ModalDialog(
        title = i18n["dialog.conflict.title"],
        onDismiss = onDismiss,
        size = ModalSize.XLARGE,
        actions = {
            OutlinedButton(onClick = onBackToMods) {
                Text(i18n["dialog.conflict.back_to_mods"])
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onRunAnyway,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(i18n["dialog.conflict.run_anyway"])
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = i18n["dialog.conflict.subtitle"].replace("{count}", conflicts.size.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textMuted
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(conflicts) { conflict ->
                    ConflictRow(conflict)
                }
            }
        }
    }
}

@Composable
private fun ConflictRow(conflict: ModConflict) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current
    val (color, label) = when (conflict.severity) {
        ConflictSeverity.WARNING -> extra.statusWarning to i18n["dialog.conflict.severity.warning"]
        ConflictSeverity.ERROR -> extra.statusError to i18n["dialog.conflict.severity.error"]
        ConflictSeverity.CRITICAL -> extra.statusError to i18n["dialog.conflict.severity.critical"]
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${conflict.modA} ⚠ ${conflict.modB}",
                    fontWeight = FontWeight.SemiBold,
                    color = extra.textPrimary
                )
                TextBadge(text = label, backgroundColor = color, textColor = color)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = conflict.description,
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted
            )
        }
    }
}
