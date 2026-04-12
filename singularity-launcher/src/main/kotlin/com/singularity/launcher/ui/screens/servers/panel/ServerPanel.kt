package com.singularity.launcher.ui.screens.servers.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.ServerManager
import com.singularity.launcher.service.ServerStatus
import com.singularity.launcher.ui.components.RunState
import com.singularity.launcher.ui.components.StatusDot
import com.singularity.launcher.ui.navigation.LocalNavigator
import com.singularity.launcher.ui.screens.servers.panel.tabs.ConsoleTab
import com.singularity.launcher.ui.screens.servers.serverRunState
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Drill-down screen dla pojedynczego serwera. Wywoływane z `ServersScreen.ServerGridCard`
 * przez `LocalNavigator.openServerPanel(id)`.
 *
 * **Layout:**
 * 1. Header Surface: StatusDot + name + actions (Settings + Start/Stop/Restart + Force Stop)
 * 2. Back button Row pod headerem
 * 3. ScrollableTabRow 6 tabs
 * 4. Tab content Box
 *
 * **Force Stop button**: dostępny tylko gdy RUNNING/STARTING (#16 edge-case).
 * **ConsoleTab** pełny, inne tabs placeholder (Sub 5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPanel(
    serverId: String,
    serverManager: ServerManager,
    onStartServer: (String) -> Unit = { _ -> },
    onStopServer: (String) -> Unit = { _ -> }
) {
    val vm = remember(serverId, serverManager) { ServerPanelViewModel(serverManager, serverId) }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    val state by vm.state.collectAsState()
    val navigator = LocalNavigator.current
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    when {
        state.isLoading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }

        state.error != null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = state.error!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { navigator.backFromPanel() }) {
                    Text(i18n["action.back"])
                }
            }
        }

        state.server != null -> Column(modifier = Modifier.fillMaxSize()) {
            val server = state.server!!
            // Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = extra.cardBg,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(state = serverRunState(server.status))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.config.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = extra.textPrimary
                        )
                        Text(
                            text = "MC ${server.config.minecraftVersion} • port ${server.config.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.textMuted
                        )
                    }

                    // Actions
                    IconButton(onClick = { /* Sub 5 — Settings modal for server */ }) {
                        Icon(Icons.Default.Settings, contentDescription = i18n["server_panel.settings"])
                    }

                    when (server.status) {
                        ServerStatus.OFFLINE, ServerStatus.CRASHED -> {
                            Button(onClick = { onStartServer(serverId) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(i18n["server_panel.start"])
                            }
                        }
                        ServerStatus.RUNNING -> {
                            OutlinedButton(onClick = { onStopServer(serverId) }) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(i18n["server_panel.stop"])
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                onStopServer(serverId)
                                onStartServer(serverId)
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(i18n["server_panel.restart"])
                            }
                        }
                        ServerStatus.STARTING, ServerStatus.STOPPING -> {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }

                    // Force Stop button — tylko gdy RUNNING/STARTING (#16 edge-case)
                    if (server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { vm.setForceStopConfirmOpen(true) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(i18n["server_panel.force_stop"])
                        }
                    }
                }
            }

            // Back button row (pod headerem)
            TextButton(
                onClick = { navigator.backFromPanel() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(i18n["server_panel.back_to_servers"])
            }

            // ScrollableTabRow
            ScrollableTabRow(
                selectedTabIndex = state.selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                ServerTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { vm.setSelectedTab(tab) },
                        text = { Text(i18n[tab.i18nKey]) }
                    )
                }
            }

            // Tab content
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                when (state.selectedTab) {
                    ServerTab.CONSOLE -> ConsoleTab(
                        lines = state.consoleLines,
                        inputValue = state.consoleInput,
                        onInputChange = vm::setConsoleInput,
                        onSend = { vm.sendConsoleCommand() }
                    )
                    else -> PlaceholderTabContent(i18n[state.selectedTab.i18nKey])
                }
            }
        }
    }

    // Force Stop confirm dialog (#15 edge-case CRITICAL)
    if (state.isForceStopConfirmOpen && state.server != null) {
        ForceStopConfirmDialog(
            serverName = state.server!!.config.name,
            onConfirm = { vm.confirmForceStop() },
            onDismiss = { vm.setForceStopConfirmOpen(false) }
        )
    }
}

@Composable
private fun PlaceholderTabContent(tabName: String) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = tabName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = i18n["server_panel.tab.coming_soon"],
            style = MaterialTheme.typography.bodySmall,
            color = extra.textMuted
        )
    }
}
