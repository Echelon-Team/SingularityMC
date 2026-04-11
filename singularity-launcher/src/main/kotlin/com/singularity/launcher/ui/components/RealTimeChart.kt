package com.singularity.launcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.ui.theme.LocalExtraPalette
import java.util.Locale

/**
 * Wykres liniowy real-time — Compose Canvas Bezier path + area fill.
 *
 * **Perf optymalizacje (C1/C2/C3 perf-v1/v2):**
 * - `ChartData` @Immutable + FloatArray (no boxing)
 * - `remember { Path() }` + `path.reset()` per frame (zero alokacji Path)
 * - `derivedStateOf { format(currentValue) }` — String.format tylko gdy value się zmieni
 *
 * **Bezier smooth curves** (tension 0.4) — matcha Chart.js prototyp (index.html
 * chart.js config `tension: 0.4`). Eliminuje kanciaste lineTo.
 *
 * **Area fill** — alpha 0.12 * lineColor pod linią (prototyp `fill: true, backgroundColor: color+'20'`).
 *
 * **Layout** (P24 ui-v1):
 * - label (11sp text-muted) TOP
 * - value (28sp bold lineColor) BELOW label — nie obok
 * - Canvas 50dp (nie 80dp)
 *
 * @param data ChartData z FloatArray samples + currentValue
 * @param minValue dolny limit Y (np. 0 dla %)
 * @param maxValue górny limit Y (np. 100 dla %, 20 dla TPS)
 * @param label nazwa metryki
 * @param unit jednostka (np. "FPS", "MB", "%")
 * @param lineColor kolor linii (fill to ten sam color × alpha 0.12)
 * @param tension Bezier tension — 0.4 matcha Chart.js; 0 = lineTo, 0.5 = max smooth
 */
@Composable
fun RealTimeChart(
    data: ChartData,
    minValue: Float,
    maxValue: Float,
    label: String,
    unit: String,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    tension: Float = 0.4f
) {
    val extra = LocalExtraPalette.current

    // derivedStateOf: format tylko gdy currentValue się zmienia (nie per recompose innych pól)
    // Locale.ROOT żeby mieć spójny decimal separator (kropka) niezależnie od systemu.
    val formattedValue by remember(data.currentValue, unit) {
        derivedStateOf { "${String.format(Locale.ROOT, "%.1f", data.currentValue)} $unit" }
    }

    // remember { Path() } — zero alokacji per recompose (C2 perf-v1 fix)
    val linePath = remember { Path() }
    val fillPath = remember { Path() }

    Column(modifier = modifier) {
        // Label (11sp muted) TOP — prototyp layout
        Text(
            text = label,
            fontSize = 11.sp,
            color = extra.textMuted
        )

        // Value (28sp bold) POD label, NIE obok
        Text(
            text = formattedValue,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = lineColor
        )

        Spacer(Modifier.height(4.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)  // Prototyp: chart-height 50px, nie 80px
        ) {
            if (data.samples.size < 2) return@Canvas

            val w = size.width
            val h = size.height

            // Grid lines — 4 horizontal linie co 25%
            val gridColor = extra.textDisabled.copy(alpha = 0.2f)
            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }

            // Build paths (reset previously allocated — zero new allocations)
            linePath.reset()
            fillPath.reset()

            val xStep = w / (data.samples.size - 1).toFloat()

            // Start line path
            val firstY = RealTimeChartMath.scaleY(data.samples[0], minValue, maxValue, h)
            linePath.moveTo(0f, firstY)

            // Bezier cubicTo dla smooth curves (Catmull-Rom approx with tension)
            for (i in 1 until data.samples.size) {
                val x0 = (i - 1) * xStep
                val x1 = i * xStep
                val y0 = RealTimeChartMath.scaleY(data.samples[i - 1], minValue, maxValue, h)
                val y1 = RealTimeChartMath.scaleY(data.samples[i], minValue, maxValue, h)

                // Control points — horizontal offset × tension factor
                val dx = (x1 - x0) * tension
                val cx1 = x0 + dx
                val cx2 = x1 - dx

                linePath.cubicTo(cx1, y0, cx2, y1, x1, y1)
            }

            // Build fill path — line path + closing bottom corners
            fillPath.addPath(linePath)
            fillPath.lineTo(w, h)
            fillPath.lineTo(0f, h)
            fillPath.close()

            // Draw area fill (alpha 0.12)
            drawPath(
                path = fillPath,
                color = lineColor.copy(alpha = 0.12f),
                style = Fill
            )

            // Draw line on top
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 2f, cap = StrokeCap.Round)
            )
        }
    }
}
