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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.FilterChipRow
import com.singularity.launcher.ui.components.SearchBar
import com.singularity.launcher.ui.screens.diagnostics.LogEntry
import com.singularity.launcher.ui.screens.diagnostics.LogLevel
import com.singularity.launcher.ui.screens.diagnostics.LogSource
import com.singularity.launcher.ui.screens.diagnostics.LogsTabStateData
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * LogsTab — LazyColumn + level/source filter + search + autoscroll toggle.
 *
 * **Sub 4 MVP:** jest simple in-memory log display. Real paged read z dysku dla 500MB+
 * log files wymaga Sub 5 LogFileReader (windowed read). Aktualne Sub 4 wyświetla
 * entries z in-memory state (pusta lista domyślnie — nie ma real log streamu).
 *
 * **#30 edge-case LazyColumn critical** — NIE verticalScroll(rememberScrollState)
 * bo 500MB log files → OOM. LazyColumn jest pamięciowo-bezpieczny.
 */
@Composable
fun LogsTab() {
    // In-memory state dla Sub 4 (nie zintegrowane z real log sources)
    var state by remember { mutableStateOf(LogsTabStateData()) }
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = i18n["diagnostics.logs.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )

        Spacer(Modifier.height(12.dp))

        // Toolbar: search + level filter + source filter + autoscroll toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchBar(
                value = state.searchQuery,
                onValueChange = { state = state.copy(searchQuery = it) },
                placeholder = i18n["diagnostics.logs.search"],
                modifier = Modifier.weight(1f)
            )

            // Level filter: ALL / INFO / WARN / ERROR
            val levelOptions = listOf(
                i18n["diagnostics.logs.level.all"],
                "INFO", "WARN", "ERROR"
            )
            val levelIdx = state.levelFilter?.ordinal?.plus(1) ?: 0
            FilterChipRow(
                options = levelOptions,
                selectedIndex = levelIdx,
                onSelect = { idx ->
                    state = state.copy(
                        levelFilter = if (idx == 0) null else LogLevel.entries[idx - 1]
                    )
                }
            )

            // Autoscroll toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(i18n["diagnostics.logs.autoscroll"], color = extra.textMuted, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Switch(
                    checked = state.autoScroll,
                    onCheckedChange = { state = state.copy(autoScroll = it) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // LazyColumn log entries (#30 edge-case — paged, not scrollState)
        if (state.filteredEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = i18n["diagnostics.logs.empty"],
                    color = extra.textMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(state.filteredEntries, key = { it.timestamp.toString() + it.message }) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val extra = LocalExtraPalette.current
    val color = when (entry.level) {
        LogLevel.INFO -> extra.textSecondary
        LogLevel.WARN -> extra.statusWarning
        LogLevel.ERROR -> extra.statusError
    }
    Text(
        text = "[${entry.level.name}] [${entry.sourceName}] ${entry.message}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        modifier = Modifier.fillMaxWidth()
    )
}
