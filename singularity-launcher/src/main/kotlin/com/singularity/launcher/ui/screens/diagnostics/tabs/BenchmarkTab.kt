package com.singularity.launcher.ui.screens.diagnostics.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Benchmark tab — stub w Sub 4 z banner "Wymaga Sub 5 IPC".
 *
 * **Why stub:** Benchmark wymaga IpcClient real (Sub 5) do triggerowania benchmark w grze
 * przez agent IPC. W Sub 4 IpcClient jest mock (Task 31), więc nie można faktycznie
 * zrobić benchmark.
 *
 * **Sub 5 Task 20 Step 3:** real benchmark trigger wire + history display.
 */
@Composable
fun BenchmarkTab(isGameRunning: Boolean = false) {
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = i18n["diagnostics.benchmark.title"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = extra.textPrimary
        )

        // Warning banner (inline styling)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(extra.statusWarning.copy(alpha = 0.15f))
                .padding(12.dp)
        ) {
            Text(
                text = i18n["diagnostics.benchmark.sub5_banner"],
                style = MaterialTheme.typography.bodyMedium,
                color = extra.statusWarning,
                fontWeight = FontWeight.SemiBold
            )
        }

        Button(
            onClick = { /* Sub 5 — real benchmark trigger */ },
            enabled = false  // Sub 4 stub
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(i18n["diagnostics.benchmark.run"])
        }

        Text(
            text = i18n["diagnostics.benchmark.results_placeholder"],
            style = MaterialTheme.typography.bodyMedium,
            color = extra.textMuted
        )
    }
}
