package com.singularity.launcher.ui.screens.diagnostics.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.ui.components.EmptyState
import com.singularity.launcher.ui.screens.diagnostics.CrashAnalyzerViewModel
import com.singularity.launcher.ui.screens.diagnostics.CrashLogParser
import com.singularity.launcher.ui.theme.LocalExtraPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashAnalyzerTab — pełny w Sub 4.
 *
 * Layout: lista crash reports po lewej (z metadata: fileName, time, size, description)
 * + szczegóły po prawej (raw content w monospace, scrollable).
 *
 * Master-detail pattern: klik na listę → update selectedReport w ViewModel → detail pane
 * renderuje markdown formatted content.
 */
@Composable
fun CrashAnalyzerTab(instanceManager: InstanceManager) {
    val vm = remember(instanceManager) { CrashAnalyzerViewModel(instanceManager) }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    val state by vm.state.collectAsState()
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header with refresh button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = i18n["diagnostics.crash.title"],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = extra.textPrimary
            )
            OutlinedButton(onClick = { vm.refresh() }) {
                Text(i18n["action.refresh"])
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.error != null -> Text(
                text = "${i18n["diagnostics.crash.error"]}: ${state.error}",
                color = extra.statusError
            )

            state.reports.isEmpty() -> EmptyState(
                title = i18n["diagnostics.crash.empty.title"],
                subtitle = i18n["diagnostics.crash.empty.subtitle"]
            )

            else -> Row(modifier = Modifier.fillMaxSize()) {
                // Master: list
                LazyColumn(
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.reports, key = { it.fileName }) { report ->
                        CrashReportRow(
                            report = report,
                            isSelected = report == state.selectedReport,
                            onClick = { vm.setSelectedReport(report) }
                        )
                    }
                }

                // Divider
                Divider(
                    modifier = Modifier.fillMaxHeight().width(1.dp),
                    color = extra.sidebarBorder
                )

                // Detail: selected report content
                val selected = state.selectedReport
                if (selected != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = selected.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = extra.textPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${i18n["diagnostics.crash.time"]}: ${selected.time}",
                            color = extra.textMuted
                        )
                        Text(
                            text = "${i18n["diagnostics.crash.description"]}: ${selected.description}",
                            color = extra.textSecondary
                        )
                        Spacer(Modifier.height(16.dp))
                        // Raw content in monospace style
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = extra.cardBg)
                        ) {
                            Text(
                                text = selected.rawContent,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                color = extra.textPrimary
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = i18n["diagnostics.crash.select_prompt"],
                            color = extra.textMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashReportRow(
    report: CrashLogParser.CrashReport,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) extra.sidebarActive else extra.cardBg
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = report.fileName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = extra.textPrimary,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = report.description,
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted,
                maxLines = 2
            )
            if (report.lastModifiedMs > 0) {
                Spacer(Modifier.height(2.dp))
                val dateFmt = remember {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                }
                Text(
                    text = dateFmt.format(Date(report.lastModifiedMs)),
                    fontSize = 10.sp,
                    color = extra.textDisabled
                )
            }
        }
    }
}
