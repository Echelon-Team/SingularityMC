package com.singularity.launcher.ui.screens.instances

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.config.I18n
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.ui.components.EmptyState
import com.singularity.launcher.ui.components.FilterChipRow
import com.singularity.launcher.ui.components.InstanceTypeBadge
import com.singularity.launcher.ui.components.RunState
import com.singularity.launcher.ui.components.SearchBar
import com.singularity.launcher.ui.components.StatusDot
import com.singularity.launcher.ui.navigation.LocalNavigator
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Peer screen wyświetlający listę instancji. Trzy sekcje: header (tytuł + "Nowa instancja"),
 * toolbar (search + filter chips + sort dropdown + view-toggle), content (grid lub list).
 *
 * **Navigation:** klik na kartę instancji → `LocalNavigator.current.openInstancePanel(id)` —
 * drill-down do `InstancePanel` (Task 12). Zero prop drilling.
 *
 * **Lifecycle:** `DisposableEffect(vm) { onDispose { vm.onCleared() } }` — pattern z Task 10
 * HomeScreen, zapobiega wyciekowi `viewModelScope` przy nawigacji.
 *
 * **Empty states:**
 * 1. `instances.isEmpty()` → "Brak instancji. Utwórz pierwszą." + Button
 * 2. `filteredInstances.isEmpty()` (ale są instances) → "Brak wyników wyszukiwania."
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesScreen(
    instanceManager: InstanceManager
) {
    val vm = remember(instanceManager) { InstancesViewModel(instanceManager) }
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
                text = i18n["instances.title"],
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = extra.textPrimary
            )
            Button(onClick = { vm.openWizard() }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(i18n["instances.new_instance"])
            }
        }

        Spacer(Modifier.height(16.dp))

        // Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchBar(
                value = state.searchQuery,
                onValueChange = vm::setSearchQuery,
                placeholder = i18n["instances.toolbar.search"],
                modifier = Modifier.weight(1f)
            )

            FilterChipRow(
                options = InstanceFilter.entries.map { i18n[it.i18nKey] },
                selectedIndex = state.filter.ordinal,
                onSelect = { idx -> vm.setFilter(InstanceFilter.entries[idx]) }
            )

            SortDropdown(
                current = state.sortMode,
                onSelect = vm::setSortMode,
                i18n = i18n
            )

            ViewModeSegmented(
                current = state.viewMode,
                onSelect = vm::setViewMode,
                i18n = i18n
            )
        }

        Spacer(Modifier.height(16.dp))

        // Content
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.error != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${i18n["instances.error.load"]}: ${state.error}",
                    color = extra.statusError
                )
            }

            state.instances.isEmpty() -> EmptyState(
                title = i18n["instances.empty.title"],
                subtitle = i18n["instances.empty.subtitle"],
                action = {
                    Button(onClick = { vm.openWizard() }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(i18n["instances.new_instance"])
                    }
                }
            )

            state.filteredInstances.isEmpty() -> EmptyState(
                title = i18n["instances.no_results.title"],
                subtitle = i18n["instances.no_results.subtitle"]
            )

            state.viewMode == InstanceViewMode.GRID -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(state.filteredInstances, key = { it.id }) { instance ->
                    InstanceGridCard(
                        instance = instance,
                        onClick = { navigator.openInstancePanel(instance.id) }
                    )
                }
            }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.filteredInstances, key = { it.id }) { instance ->
                    InstanceListRow(
                        instance = instance,
                        onClick = { navigator.openInstancePanel(instance.id) }
                    )
                }
            }
        }
    }

    // Wizard dialog (Task 13)
    if (state.isWizardOpen) {
        com.singularity.launcher.ui.screens.instances.wizard.NewInstanceWizard(
            onCancel = vm::closeWizard,
            onCreate = { _ ->
                // Task 32 — real create call via instanceManager.create()
                vm.closeWizard()
                vm.refresh()
            }
        )
    }
}

/**
 * Sort dropdown z ExposedDropdownMenuBox + TextField readonly. Prefix "Sortuj: ..."
 * zgodnie z prototypem index.html:2111-2119.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortDropdown(
    current: InstanceSortMode,
    onSelect: (InstanceSortMode) -> Unit,
    i18n: I18n
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.widthIn(min = 200.dp, max = 320.dp)
    ) {
        OutlinedTextField(
            value = "${i18n["instances.toolbar.sort"]}: ${i18n[current.i18nKey]}",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            InstanceSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(i18n[mode.i18nKey]) },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * View mode toggle (GRID / LIST) jako SingleChoiceSegmentedButtonRow.
 * P22 v1 fix — prototype używa 2 osobne buttony, NIE jedna toggle IconButton.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewModeSegmented(
    current: InstanceViewMode,
    onSelect: (InstanceViewMode) -> Unit,
    i18n: I18n
) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = current == InstanceViewMode.GRID,
            onClick = { onSelect(InstanceViewMode.GRID) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = { Icon(Icons.Default.GridView, contentDescription = i18n["instances.view.grid"]) }
        ) { Text(i18n["instances.view.grid"]) }

        SegmentedButton(
            selected = current == InstanceViewMode.LIST,
            onClick = { onSelect(InstanceViewMode.LIST) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = { Icon(Icons.Default.ViewList, contentDescription = i18n["instances.view.list"]) }
        ) { Text(i18n["instances.view.list"]) }
    }
}

/**
 * Grid card layout (P11 v1 fix) — prototype index.html:2135-2165.
 * Struktura: Column { Row(header: icon 48dp + info) + Spacer + Row(footer: modCount + statusDot) }
 */
@Composable
private fun InstanceGridCard(
    instance: InstanceManager.Instance,
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
            // Header: gradient icon 48dp + info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(extra.playGradientStart, extra.playGradientEnd)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = instance.config.name.take(1).uppercase(),
                        color = extra.textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = instance.config.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = extra.textPrimary,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InstanceTypeBadge(instance.config.type)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "MC ${instance.config.minecraftVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.textMuted
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Footer: modCount + statusDot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = i18n["instances.card.mods"].replace("{count}", instance.modCount.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textMuted
                )
                StatusDot(state = RunState.STOPPED)  // Runtime state: Task 12 InstancePanelViewModel
            }
        }
    }
}

/**
 * List row layout (P10 v1 fix) — prototype index.html:2176-2185.
 * Struktura: Row { icon 32dp | name weight=2 | badge | version 80dp | modCount 80dp | lastPlayed 80dp | statusDot }
 */
@Composable
private fun InstanceListRow(
    instance: InstanceManager.Instance,
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon 32dp
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(extra.playGradientStart, extra.playGradientEnd)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = instance.config.name.take(1).uppercase(),
                    color = extra.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Name
            Text(
                text = instance.config.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = extra.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(2f)
            )

            // Badge
            InstanceTypeBadge(instance.config.type)

            // Version
            Text(
                text = "MC ${instance.config.minecraftVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted,
                modifier = Modifier.width(80.dp)
            )

            // Mod count
            Text(
                text = i18n["instances.card.mods"].replace("{count}", instance.modCount.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted,
                modifier = Modifier.width(80.dp)
            )

            // Last played
            Text(
                text = formatLastPlayedShort(instance.lastPlayedAt, i18n),
                style = MaterialTheme.typography.bodySmall,
                color = extra.textMuted,
                modifier = Modifier.width(80.dp)
            )

            // Status dot
            StatusDot(state = RunState.STOPPED)
        }
    }
}

/**
 * Krótki format lastPlayed dla list row: "2h", "3d", "—" (never).
 * Długi format (formatLastPlayedSubtitle) jest w Task 10 HomeScreen dla continue card.
 */
private fun formatLastPlayedShort(
    lastPlayedMs: Long?,
    i18n: I18n,
    now: Long = System.currentTimeMillis()
): String {
    if (lastPlayedMs == null) return "—"
    val diffMs = now - lastPlayedMs
    val diffMin = diffMs / 60_000
    val diffH = diffMin / 60
    val diffD = diffH / 24
    return when {
        diffMin < 1 -> i18n["time.just_now"]
        diffMin < 60 -> "${diffMin}m"
        diffH < 24 -> "${diffH}h"
        diffD < 30 -> "${diffD}d"
        else -> "${diffD / 30}mo"
    }
}
