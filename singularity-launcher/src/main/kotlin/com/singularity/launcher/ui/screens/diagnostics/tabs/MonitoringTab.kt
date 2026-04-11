package com.singularity.launcher.ui.screens.diagnostics.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.ChartData
import com.singularity.launcher.ui.components.RealTimeChart
import com.singularity.launcher.ui.screens.diagnostics.DiagnosticsScreenState
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Monitoring tab — 5 metric cards (FPS/TPS/RAM/CPU/GPU) + instance dropdown + counters.
 *
 * Layout: instance dropdown na górze, potem LazyVerticalGrid(Adaptive 300dp) z 5 metric
 * cards + 1 counters card, na dole suggestions card (jeśli są).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringTab(
    state: DiagnosticsScreenState,
    onInstanceChange: (String) -> Unit
) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Instance dropdown (#27 edge-case — multi-instance)
        if (state.availableInstances.isNotEmpty()) {
            InstanceDropdown(
                selectedId = state.selectedInstance,
                available = state.availableInstances,
                onSelect = onInstanceChange
            )
        }

        // 5 metric cards in 2-col grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 280.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(400.dp)
        ) {
            item {
                MetricCard(
                    label = "FPS",
                    data = ChartData(state.metrics.fpsHistory, state.currentSnapshot?.fps?.toFloat() ?: 0f),
                    minValue = 0f,
                    maxValue = 120f,
                    unit = "fps",
                    color = extra.statusSuccess
                )
            }
            item {
                MetricCard(
                    label = "TPS",
                    data = ChartData(state.metrics.tpsHistory, state.currentSnapshot?.tps?.toFloat() ?: 0f),
                    minValue = 0f,
                    maxValue = 22f,
                    unit = "tps",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                MetricCard(
                    label = "RAM",
                    data = ChartData(state.metrics.ramHistory, state.currentSnapshot?.ramUsedMb?.toFloat() ?: 0f),
                    minValue = 0f,
                    maxValue = (state.currentSnapshot?.ramTotalMb ?: 8192).toFloat(),
                    unit = "MB",
                    color = extra.statusInfo
                )
            }
            item {
                MetricCard(
                    label = "CPU",
                    data = ChartData(state.metrics.cpuHistory, state.currentSnapshot?.cpuPercent?.toFloat() ?: 0f),
                    minValue = 0f,
                    maxValue = 100f,
                    unit = "%",
                    color = extra.statusWarning
                )
            }
            item {
                MetricCard(
                    label = "GPU",
                    data = ChartData(state.metrics.gpuHistory, state.currentSnapshot?.gpuPercent?.toFloat() ?: 0f),
                    minValue = 0f,
                    maxValue = 100f,
                    unit = "%",
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        // Counters card (if connected)
        val snapshot = state.currentSnapshot
        if (snapshot != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = extra.cardBg)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = i18n["diagnostics.counters.title"],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = extra.textPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    CounterRow("Aktywne regiony", snapshot.activeRegions.toString())
                    CounterRow("Encje", snapshot.entityCount.toString())
                    CounterRow("Chunki", snapshot.chunkCount.toString())
                }
            }

            // Suggestions card
            if (snapshot.suggestions.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = extra.cardBg)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = i18n["diagnostics.suggestions.title"],
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = extra.textPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        snapshot.suggestions.forEach { suggestion ->
                            Text(
                                text = "• $suggestion",
                                style = MaterialTheme.typography.bodyMedium,
                                color = extra.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstanceDropdown(
    selectedId: String?,
    available: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    val i18n = LocalI18n.current
    var expanded by remember { mutableStateOf(false) }
    val selectedName = available.firstOrNull { it.first == selectedId }?.second ?: "—"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "${i18n["diagnostics.instance"]}: $selectedName",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            singleLine = true
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            available.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    data: ChartData,
    minValue: Float,
    maxValue: Float,
    unit: String,
    color: androidx.compose.ui.graphics.Color
) {
    val extra = LocalExtraPalette.current
    Card(colors = CardDefaults.cardColors(containerColor = extra.cardBg)) {
        Box(modifier = Modifier.padding(16.dp)) {
            RealTimeChart(
                data = data,
                minValue = minValue,
                maxValue = maxValue,
                label = label,
                unit = unit,
                lineColor = color
            )
        }
    }
}

@Composable
private fun CounterRow(label: String, value: String) {
    val extra = LocalExtraPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = extra.textMuted, fontSize = 13.sp)
        Text(value, color = extra.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
