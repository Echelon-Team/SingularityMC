package com.singularity.launcher.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.ModalDialog
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * ImportWarningDialog SMALL (420dp) — fractureiser scan results po imporcie modpacka.
 *
 * Wyświetla total JARs + conditional warning (gdy fractureiser detected → listę
 * suspicious files) + OK button (jedyna akcja — dismiss).
 *
 * Fractureiser to znany malware mod który rozprzestrzenił się w MC ecosystem 2023-06.
 * Scan heurystyczny na znane sygnatury JAR.
 */
@Composable
fun ImportWarningDialog(
    result: ImportScanResult,
    onOk: () -> Unit,
    onDismiss: () -> Unit
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    ModalDialog(
        title = i18n["dialog.import.title"],
        onDismiss = onDismiss,
        size = ModalSize.SMALL,
        actions = {
            Button(onClick = onOk) {
                Text(i18n["common.ok"])
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = i18n["dialog.import.scanned"]
                    .replace("{count}", result.totalJars.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textPrimary
            )

            if (result.fractureiserDetected) {
                // Warning box (inline styling zamiast InfoBox composable — minimalistyczne)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(extra.statusWarning.copy(alpha = 0.15f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = i18n["dialog.import.fractureiser_detected"],
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.statusWarning,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = i18n["dialog.import.suspicious_list"],
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = extra.textPrimary
                )
                result.suspiciousJars.forEach { jar ->
                    Text(
                        text = "• $jar",
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.statusError
                    )
                }
            } else {
                // Clean scan success box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(extra.statusSuccess.copy(alpha = 0.15f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = i18n["dialog.import.clean"],
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.statusSuccess,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
