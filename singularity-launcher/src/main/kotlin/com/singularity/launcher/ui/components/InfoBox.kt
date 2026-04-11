package com.singularity.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Style dla InfoBox — determinuje kolor i ikonę.
 */
enum class InfoBoxStyle { INFO, WARNING, ERROR, SUCCESS }

/**
 * Prosty banner info/warning/error z ikoną + tekstem. Używany w wizardach, dialogach,
 * panel sekcjach gdy trzeba pokazać contextual info.
 *
 * **Design:** Rounded Box 8dp, tło kolor statusu z alpha 0.12f, border w kolorze statusu,
 * ikonka Material + tekst bodyMedium.
 */
@Composable
fun InfoBox(
    text: String,
    style: InfoBoxStyle = InfoBoxStyle.INFO,
    modifier: Modifier = Modifier
) {
    val extra = LocalExtraPalette.current
    val (color, icon) = when (style) {
        InfoBoxStyle.INFO -> extra.statusInfo to Icons.Default.Info
        InfoBoxStyle.WARNING -> extra.statusWarning to Icons.Default.Warning
        InfoBoxStyle.ERROR -> extra.statusError to Icons.Default.Error
        InfoBoxStyle.SUCCESS -> extra.statusSuccess to Icons.Default.CheckCircle
    }

    InfoBoxLayout(
        text = text,
        color = color,
        icon = icon,
        modifier = modifier
    )
}

@Composable
private fun InfoBoxLayout(
    text: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier
) {
    val extra = LocalExtraPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textPrimary
            )
        }
    }
}
