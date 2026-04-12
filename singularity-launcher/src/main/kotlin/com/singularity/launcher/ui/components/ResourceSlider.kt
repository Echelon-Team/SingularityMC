package com.singularity.launcher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.service.HardwareInfo
import com.singularity.launcher.ui.theme.LocalExtraPalette
import java.util.Locale

/**
 * Resource usage level — color category.
 */
enum class ResourceLevel { GREEN, YELLOW, RED }

/**
 * Pure helper: mapa percent usage → color level.
 * Extracted dla testowalności (nie wymaga Compose UI).
 */
fun resourceSliderColorLevel(value: Int, maxValue: Int): ResourceLevel {
    val pct = value.toFloat() / maxValue.toFloat()
    return when {
        pct < 0.60f -> ResourceLevel.GREEN
        pct < 0.85f -> ResourceLevel.YELLOW
        else -> ResourceLevel.RED
    }
}

/**
 * Format MB jako GB string — "8 GB" (whole) lub "8.5 GB" (fractional).
 * Prototyp używa GB (index.html: "8 GB", "16 GB"), internal MB dla precyzji.
 */
fun formatMBasGB(mb: Int): String {
    val gb = mb.toFloat() / 1024f
    return if (gb == gb.toInt().toFloat()) "${gb.toInt()} GB"
    else "${String.format(Locale.ROOT, "%.1f", gb)} GB"
}

/**
 * Threads low warning — design spec + prototyp index.html:3612.
 * ≤4 wątki = za mało dla MC + agent + JVM background threads = warning.
 */
fun threadsLowWarning(threads: Int): Boolean = threads <= 4

/**
 * Suwak dla RAM i wątków — dynamic max z HardwareInfo (Task 7).
 *
 * **Kolorowa wizualizacja** (P19 Mateusz decyzja — plan trzyma kolorową):
 * - zielony: OK (<60% max)
 * - żółty: mało zostaje (60-85% max)
 * - czerwony: przekroczono (>85% max, warning — NIE blokuje)
 *
 * **Reasonable step size** (S1 ui-v1 fix):
 * - RAM: 256 MB per step (nie 1 MB → 15358 tick marks)
 * - Threads: 1 per step (1-N)
 *
 * **Dynamic max z HardwareInfo** (C5 v3 ui-v2 fix):
 * - NIE hardcoded 16GB — user z 8GB dostał crash przy ustawieniu 16GB
 * - `HardwareInfo.totalRamMB` / `HardwareInfo.totalCores` używane jako max
 *
 * **Display format:**
 * - Dla unit="MB": `formatMBasGB(value)` — user widzi "8 GB"
 * - Dla unit="threads": raw count
 *
 * @param value aktualna wartość
 * @param onValueChange callback przy zmianie
 * @param range zakres (min..max) — max = HardwareInfo.totalRamMB lub .totalCores
 * @param label opis suwaka (np. "RAM", "Wątki")
 * @param unit "MB" lub "threads" — wpływa na display format
 * @param stepSize rozmiar kroku (256 dla RAM MB, 1 dla threads)
 */
@Composable
fun ResourceSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    label: String,
    unit: String,
    stepSize: Int = if (unit == "MB") 256 else 1,
    modifier: Modifier = Modifier
) {
    val extra = LocalExtraPalette.current
    val level = resourceSliderColorLevel(value, range.last)

    val sliderColor = when (level) {
        ResourceLevel.GREEN -> extra.statusSuccess
        ResourceLevel.YELLOW -> extra.statusWarning
        ResourceLevel.RED -> extra.statusError
    }

    // Display: GB dla MB unit, raw count dla threads
    val displayValue = when (unit) {
        "MB" -> formatMBasGB(value)
        else -> "$value"
    }
    val displayMax = when (unit) {
        "MB" -> formatMBasGB(range.last)
        else -> "${range.last}"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textPrimary
            )
            Text(
                text = "$displayValue / $displayMax",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = sliderColor
            )
        }

        Spacer(Modifier.height(4.dp))

        val totalSteps = ((range.last - range.first) / stepSize - 1).coerceAtLeast(0)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange((it.toInt() / stepSize) * stepSize) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = totalSteps,
            colors = SliderDefaults.colors(
                thumbColor = sliderColor,
                activeTrackColor = sliderColor,
                inactiveTrackColor = sliderColor.copy(alpha = 0.3f)
            )
        )

        // Warning: przekroczono 85% zasobów
        if (level == ResourceLevel.RED) {
            Text(
                text = "⚠ Może spowodować problemy z systemem — inne aplikacje zostaną zwolnione",
                style = MaterialTheme.typography.bodySmall,
                color = extra.statusError
            )
        }

        // Warning: za mało wątków (threads only)
        if (unit == "threads" && threadsLowWarning(value)) {
            Text(
                text = "⚠ 4 wątki to minimum — gra + agent + background threads mogą zwalniać",
                style = MaterialTheme.typography.bodySmall,
                color = extra.statusWarning
            )
        }
    }
}

/**
 * Convenience helper: ResourceSlider dla RAM z defaults z HardwareInfo (Task 7).
 * Used w NewInstanceWizard Task 13, InstanceSettingsModal Task 14.
 */
@Composable
fun RamSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String = "RAM",
    modifier: Modifier = Modifier
) {
    ResourceSlider(
        value = value,
        onValueChange = onValueChange,
        range = 1024..HardwareInfo.totalRamMB,
        label = label,
        unit = "MB",
        modifier = modifier
    )
}

/**
 * Convenience helper: ResourceSlider dla threads z defaults z HardwareInfo (Task 7).
 */
@Composable
fun ThreadsSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String = "Wątki",
    modifier: Modifier = Modifier
) {
    ResourceSlider(
        value = value,
        onValueChange = onValueChange,
        range = 2..HardwareInfo.maxAssignableThreads,
        label = label,
        unit = "threads",
        modifier = modifier
    )
}
