// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.servers

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.ServerManager
import com.singularity.launcher.ui.components.EmptyState
import com.singularity.launcher.ui.navigation.LocalNavigator
import com.singularity.launcher.ui.screens.servers.wizard.NewServerWizard
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Peer screen — dashboard wszystkich serwerów. Vanilla only w Sub 4 (D2 decyzja).
 *
 * **Layout:**
 * 1. Header: tytuł "Serwery" + view-toggle SegmentedButton + "+ Nowy serwer" button
 * 2. Content: LazyVerticalGrid(300.dp) lub LazyColumn (view mode)
 * 3. Empty state gdy 0 serwerów
 *
 * **Polling:** ViewModel startuje periodic poll co 5s w init. DisposableEffect cancel na dispose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    serverManager: ServerManager
) {
    val vm = remember(serverManager) { ServersViewModel(serverManager) }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    val state by vm.state.collectAsState()
    val i18n = LocalI18n.current
    val navigator = LocalNavigator.current
    val extra = LocalExtraPalette.current

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = i18n["servers.title"],
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = extra.textPrimary
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = state.viewMode == ServerViewMode.GRID,
                        onClick = { vm.setViewMode(ServerViewMode.GRID) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.GridView, contentDescription = null) }
                    ) { Text(i18n["servers.view.grid"]) }
                    SegmentedButton(
                        selected = state.viewMode == ServerViewMode.LIST,
                        onClick = { vm.setViewMode(ServerViewMode.LIST) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null) }
                    ) { Text(i18n["servers.view.list"]) }
                }
                Button(onClick = vm::openWizard) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(i18n["servers.new_server"])
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading && state.servers.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.servers.isEmpty() -> EmptyState(
                    title = i18n["servers.empty.title"],
                    subtitle = i18n["servers.empty.subtitle"],
                    action = {
                        Button(onClick = vm::openWizard) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(i18n["servers.new_server"])
                        }
                    }
                )

                state.viewMode == ServerViewMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(state.servers, key = { it.id }) { server ->
                        ServerGridCard(
                            server = server,
                            onClick = { navigator.openServerPanel(server.id) }
                        )
                    }
                }

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.servers, key = { it.id }) { server ->
                        ServerListRow(
                            server = server,
                            onClick = { navigator.openServerPanel(server.id) }
                        )
                    }
                }
            }
        }
    }

    // Wizard (Task 17 NewServerWizard)
    if (state.isWizardOpen) {
        NewServerWizard(
            availableInstances = emptyList(),  // Task 32 will inject from InstanceManager
            usedPorts = state.servers.map { it.config.port }.toSet(),
            onCancel = vm::closeWizard,
            onCreate = { _ ->
                // Task 32 — real create call via serverManager.create()
                vm.closeWizard()
                vm.refresh()
            }
        )
    }
}
