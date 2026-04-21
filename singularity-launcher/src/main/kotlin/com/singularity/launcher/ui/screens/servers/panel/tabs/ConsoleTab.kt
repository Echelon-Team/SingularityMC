// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.servers.panel.tabs

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Console tab dla ServerPanel — LazyColumn z autoscroll gdy nowa linia.
 *
 * **Autoscroll logic (#11 edge-case):**
 * - Gdy user jest NA DOLE (isAtBottom == true), scroll auto na nową linię.
 * - Gdy user przewinął w górę (isAtBottom == false), autoscroll się pauzuje — user może
 *   czytać starsze linie bez "uciekania" do dołu.
 */
@Composable
fun ConsoleTab(
    lines: List<String>,
    inputValue: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current
    val listState = rememberLazyListState()

    // Check if user is at bottom
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem == null || lastVisibleItem.index >= lines.size - 1
        }
    }

    // Autoscroll na nowe linie gdy user at bottom
    LaunchedEffect(lines.size) {
        if (isAtBottom && lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Console output
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(extra.cardBg)
                .padding(8.dp)
        ) {
            LazyColumn(state = listState) {
                items(items = lines.withIndex().toList(), key = { (i, line) -> "$i:${line.hashCode()}" }) { (_, line) ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputValue,
                onValueChange = onInputChange,
                placeholder = { Text(i18n["server_panel.console.input.placeholder"]) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(i18n["server_panel.console.send"])
            }
        }
    }
}
