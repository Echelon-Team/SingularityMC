package com.singularity.launcher.ui.screens.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.service.ServerManager
import com.singularity.launcher.service.ServerStatus
import com.singularity.launcher.ui.components.StatusDot
import com.singularity.launcher.ui.theme.LocalExtraPalette

@Composable
fun ServerListRow(
    server: ServerManager.Server,
    onClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusDot(state = serverRunState(server.status))

            Column(modifier = Modifier.weight(2f)) {
                Text(
                    text = server.config.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = extra.textPrimary
                )
                Text(
                    text = "MC ${server.config.minecraftVersion} • port ${server.config.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textMuted
                )
            }

            val display = when (server.status) {
                ServerStatus.RUNNING -> server.liveMetrics?.let {
                    ServerStatusLogic.runningMetricsDisplay(it)
                } ?: ServerStatusLogic.stoppedMetricsPlaceholder()
                else -> ServerStatusLogic.stoppedMetricsPlaceholder()
            }

            Text(
                text = "TPS ${display.tps}",
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted,
                modifier = Modifier.width(80.dp)
            )
            Text(
                text = display.players,
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted,
                modifier = Modifier.width(80.dp)
            )
            Text(
                text = display.ram,
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted,
                modifier = Modifier.width(120.dp)
            )
        }
    }
}
