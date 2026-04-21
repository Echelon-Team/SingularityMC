// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.ServerManager
import com.singularity.launcher.service.ServerStatus
import com.singularity.launcher.ui.components.RunState
import com.singularity.launcher.ui.components.StatusDot
import com.singularity.launcher.ui.components.TextBadge
import com.singularity.launcher.ui.theme.LocalExtraPalette
import com.singularity.launcher.ui.theme.SingularityExtraPalette

@Composable
fun ServerGridCard(
    server: ServerManager.Server,
    onClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: name + status dot
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state = serverRunState(server.status))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = server.config.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = extra.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                StatusBadgeInternal(server.status)
            }

            Spacer(Modifier.height(4.dp))

            // Parent instance info
            if (server.config.parentInstanceId != null) {
                Text(
                    text = i18n["servers.card.parent_instance"],
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textMuted
                )
            }

            Spacer(Modifier.height(12.dp))

            // Metrics (STARTING → spinner, OFFLINE → "—", RUNNING → values)
            when (server.status) {
                ServerStatus.STARTING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = i18n["servers.status.starting"],
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.textMuted
                        )
                    }
                }
                ServerStatus.RUNNING -> {
                    val display = server.liveMetrics?.let { ServerStatusLogic.runningMetricsDisplay(it) }
                        ?: ServerStatusLogic.stoppedMetricsPlaceholder()
                    MetricsRowInternal(display, extra, i18n["servers.metric.players"])
                }
                else -> {
                    val display = ServerStatusLogic.stoppedMetricsPlaceholder()
                    MetricsRowInternal(display, extra, i18n["servers.metric.players"])
                }
            }
        }
    }
}

@Composable
private fun MetricsRowInternal(
    display: ServerStatusLogic.MetricsDisplay,
    extra: SingularityExtraPalette,
    playersLabel: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MetricCell(label = "TPS", value = display.tps, extra = extra)
        MetricCell(label = playersLabel, value = display.players, extra = extra)
        MetricCell(label = "RAM", value = display.ram, extra = extra)
    }
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    extra: SingularityExtraPalette
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = extra.textMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (value == "—") extra.textDisabled else extra.textPrimary
        )
    }
}

fun serverRunState(status: ServerStatus): RunState = when (status) {
    ServerStatus.RUNNING -> RunState.RUNNING
    ServerStatus.STARTING, ServerStatus.STOPPING -> RunState.LOADING
    ServerStatus.OFFLINE, ServerStatus.CRASHED -> RunState.STOPPED
}

@Composable
private fun StatusBadgeInternal(status: ServerStatus) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current
    val (label, color) = when (status) {
        ServerStatus.OFFLINE -> i18n["servers.status.offline"] to extra.textMuted
        ServerStatus.STARTING -> i18n["servers.status.starting"] to extra.statusInfo
        ServerStatus.RUNNING -> i18n["servers.status.running"] to extra.statusSuccess
        ServerStatus.STOPPING -> i18n["servers.status.stopping"] to extra.statusWarning
        ServerStatus.CRASHED -> i18n["servers.status.crashed"] to extra.statusError
    }
    TextBadge(
        text = label,
        backgroundColor = color,
        textColor = color
    )
}
