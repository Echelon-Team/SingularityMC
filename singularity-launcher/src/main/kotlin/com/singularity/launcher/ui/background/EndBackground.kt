package com.singularity.launcher.ui.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Dekoracyjne tło End Dimension — **Canvas-based drawing matching prototype CSS**.
 *
 * Source of truth: `docs/visual-companion/index.html` body + `.bg-glow` + `.bg-orb` + `.bg-decorations`
 * dla `:root[data-theme="end"]`.
 *
 * **4 warstwy (od tła do góry):**
 * 1. Void base color `#0A0614`
 * 2. Ambient glow — 3 radial gradients subtle purple (matching `.bg-glow`)
 * 3. Moon 140dp top:60 right:100 — radial gradient 45%42% offset + multi-layer halo
 * 4. 16 stars w positions matching `.bg-decorations` radial-gradient positions
 *
 * **Performance fix (2026-04-11):** Wcześniej był `rememberInfiniteTransition` pulse
 * alpha → constant recomposition → 15 FPS + 20% GPU. Usunięte — tło statyczne, żadnych
 * animacji na tle. Crossfade theme switching przez `ThemeTransitionBackground` wystarczy.
 */
@Composable
fun EndBackground(modifier: Modifier = Modifier) {
    val density = LocalDensity.current

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Helper for hex RGB + alpha
        val cream = Color(red = 0xDD / 255f, green = 0xDF / 255f, blue = 0xA5 / 255f, alpha = 1f)
        val purple = Color(red = 0xA6 / 255f, green = 0x79 / 255f, blue = 0xA6 / 255f, alpha = 1f)
        val blueGrey = Color(red = 0x81 / 255f, green = 0x85 / 255f, blue = 0xC9 / 255f, alpha = 1f)
        val pink = Color(red = 0xDA / 255f, green = 0x73 / 255f, blue = 0xDE / 255f, alpha = 1f)
        val ambientPurple = Color(red = 0x2F / 255f, green = 0x19 / 255f, blue = 0x5F / 255f, alpha = 1f)
        val deepVoid = Color(red = 0x14 / 255f, green = 0x12 / 255f, blue = 0x1D / 255f, alpha = 1f)
        val haloPurple = Color(red = 0x7F / 255f, green = 0x3F / 255f, blue = 0xB2 / 255f, alpha = 1f)

        // 1. Void base — #0A0614 (prototype --base dla end theme)
        drawRect(color = Color(red = 0x0A / 255f, green = 0x06 / 255f, blue = 0x14 / 255f, alpha = 1f))

        // 2. Ambient glow layers (matching .bg-glow)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(ambientPurple.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(w * 0.7f, h * 0.25f),
                radius = w * 0.6f
            ),
            topLeft = Offset.Zero,
            size = Size(w, h)
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(ambientPurple.copy(alpha = 0.06f), Color.Transparent),
                center = Offset(w * 0.2f, h * 0.7f),
                radius = w * 0.5f
            ),
            topLeft = Offset.Zero,
            size = Size(w, h)
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(deepVoid.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(w / 2f, h / 2f),
                radius = w * 0.8f
            ),
            topLeft = Offset.Zero,
            size = Size(w, h)
        )

        // 3. Moon — width: 140px top: 60px right: 100px (pixels from prototype CSS)
        val moonRadius = with(density) { 70.dp.toPx() }
        val moonOffsetRight = with(density) { 100.dp.toPx() }
        val moonOffsetTop = with(density) { 60.dp.toPx() }
        val moonCenter = Offset(w - moonOffsetRight - moonRadius, moonOffsetTop + moonRadius)

        // Outer halo — box-shadow: 0 0 200px 100px rgba(127, 63, 178, 0.04)
        val outerHaloRadius = moonRadius + with(density) { 300.dp.toPx() }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(haloPurple.copy(alpha = 0.04f), Color.Transparent),
                center = moonCenter,
                radius = outerHaloRadius
            ),
            radius = outerHaloRadius,
            center = moonCenter
        )

        // Mid halo — box-shadow: 0 0 100px 50px rgba(221, 223, 165, 0.06)
        val midHaloRadius = moonRadius + with(density) { 150.dp.toPx() }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cream.copy(alpha = 0.06f), Color.Transparent),
                center = moonCenter,
                radius = midHaloRadius
            ),
            radius = midHaloRadius,
            center = moonCenter
        )

        // Inner halo — box-shadow: 0 0 40px 20px rgba(221, 223, 165, 0.12)
        val innerHaloRadius = moonRadius + with(density) { 60.dp.toPx() }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cream.copy(alpha = 0.12f), Color.Transparent),
                center = moonCenter,
                radius = innerHaloRadius
            ),
            radius = innerHaloRadius,
            center = moonCenter
        )

        // Moon body — radial-gradient(circle at 45% 42%, ...)
        val moonGradientCenter = Offset(
            moonCenter.x - moonRadius * 0.1f,
            moonCenter.y - moonRadius * 0.16f
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    cream.copy(alpha = 0.45f),
                    cream.copy(alpha = 0.25f),
                    purple.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = moonGradientCenter,
                radius = moonRadius
            ),
            radius = moonRadius,
            center = moonCenter
        )

        // 4. Stars — 16 positions z prototype `.bg-decorations` radial-gradients
        data class Star(val xFrac: Float, val yFrac: Float, val rDp: Float, val color: Color)

        val stars = listOf(
            // Creamy (rgba(221,223,165))
            Star(0.08f, 0.12f, 2f, cream.copy(alpha = 0.7f)),
            Star(0.52f, 0.06f, 2.5f, cream.copy(alpha = 0.8f)),
            Star(0.83f, 0.22f, 2f, cream.copy(alpha = 0.6f)),
            Star(0.12f, 0.78f, 1.5f, cream.copy(alpha = 0.4f)),
            Star(0.58f, 0.85f, 2.5f, cream.copy(alpha = 0.5f)),
            Star(0.75f, 0.10f, 2f, cream.copy(alpha = 0.7f)),
            Star(0.92f, 0.52f, 1.5f, cream.copy(alpha = 0.5f)),
            Star(0.65f, 0.32f, 3f, cream.copy(alpha = 0.6f)),

            // Purple (rgba(166,121,166))
            Star(0.22f, 0.38f, 1.5f, purple.copy(alpha = 0.5f)),
            Star(0.38f, 0.62f, 2f, purple.copy(alpha = 0.5f)),
            Star(0.03f, 0.45f, 1.5f, purple.copy(alpha = 0.35f)),

            // Blue-grey (rgba(129,133,201))
            Star(0.68f, 0.48f, 1.5f, blueGrey.copy(alpha = 0.5f)),
            Star(0.88f, 0.68f, 2f, blueGrey.copy(alpha = 0.5f)),
            Star(0.45f, 0.30f, 2f, blueGrey.copy(alpha = 0.4f)),

            // Pink (rgba(218,115,222))
            Star(0.32f, 0.18f, 1.5f, pink.copy(alpha = 0.4f)),
            Star(0.18f, 0.92f, 2f, pink.copy(alpha = 0.3f))
        )

        stars.forEach { star ->
            drawCircle(
                color = star.color,
                radius = with(density) { star.rDp.dp.toPx() },
                center = Offset(w * star.xFrac, h * star.yFrac)
            )
        }
    }
}
