// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.service.ipc.IpcClient
import com.singularity.launcher.ui.screens.diagnostics.tabs.BenchmarkTab
import com.singularity.launcher.ui.screens.diagnostics.tabs.CrashAnalyzerTab
import com.singularity.launcher.ui.screens.diagnostics.tabs.LogsTab
import com.singularity.launcher.ui.screens.diagnostics.tabs.MonitoringTab
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Peer screen Diagnostics z 4 zakładkami: Monitoring (pełny), Benchmark (placeholder do
 * Sub 5 live metrics), Crash Analyzer (parsuje logi/crash reports z instance folders),
 * Logs (live game logs z LazyColumn + filter).
 *
 * **DisposableEffect (#26 edge-case CRITICAL):** onDispose wywołuje vm.onCleared() który
 * cancels metricsPollJob i wywołuje ipcClient.disconnect(). Bez tego job wycieka.
 *
 * **Connection banner (#25 edge-case):** gdy !isConnected → InfoBox WARNING.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    ipcClient: IpcClient,
    instanceManager: InstanceManager
) {
    val vm = remember(ipcClient, instanceManager) { DiagnosticsViewModel(ipcClient, instanceManager) }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    val state by vm.state.collectAsState()
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        // Header
        Text(
            text = i18n["nav.diagnostics"],
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = extra.textPrimary
        )

        Spacer(Modifier.height(8.dp))

        // Connection banner (#25 edge-case)
        if (!state.isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(extra.statusWarning.copy(alpha = 0.15f))
                    .padding(12.dp)
            ) {
                Text(
                    text = i18n["diagnostics.banner.not_connected"],
                    color = extra.statusWarning,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Tab row
        TabRow(
            selectedTabIndex = state.selectedTab.ordinal,
            containerColor = extra.cardBg
        ) {
            DiagnosticsTab.entries.forEach { tab ->
                Tab(
                    selected = tab == state.selectedTab,
                    onClick = { vm.setTab(tab) },
                    text = { Text(i18n[tab.i18nKey]) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Tab content
        Box(modifier = Modifier.fillMaxSize()) {
            when (state.selectedTab) {
                DiagnosticsTab.MONITORING -> MonitoringTab(
                    state = state,
                    onInstanceChange = vm::setInstance
                )
                DiagnosticsTab.BENCHMARK -> BenchmarkTab(isGameRunning = state.isConnected)
                DiagnosticsTab.CRASH_ANALYZER -> CrashAnalyzerTab(instanceManager = instanceManager)
                DiagnosticsTab.LOGS -> LogsTab()
            }
        }
    }
}
