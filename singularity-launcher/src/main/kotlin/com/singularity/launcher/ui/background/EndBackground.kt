// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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

    // Base void color — prototype --base dla end theme
    val baseVoid = Color(red = 0x14 / 255f, green = 0x12 / 255f, blue = 0x1D / 255f, alpha = 1f)
    val darkPurpleTop = Color(red = 0x0A / 255f, green = 0x06 / 255f, blue = 0x14 / 255f, alpha = 1f)
    val mediumPurpleBottom = Color(red = 0x1B / 255f, green = 0x10 / 255f, blue = 0x28 / 255f, alpha = 1f)

    // Palette for stars/glows
    val cream = Color(red = 0xDD / 255f, green = 0xDF / 255f, blue = 0xA5 / 255f, alpha = 1f)
    val purple = Color(red = 0xA6 / 255f, green = 0x79 / 255f, blue = 0xA6 / 255f, alpha = 1f)
    val blueGrey = Color(red = 0x81 / 255f, green = 0x85 / 255f, blue = 0xC9 / 255f, alpha = 1f)
    val pink = Color(red = 0xDA / 255f, green = 0x73 / 255f, blue = 0xDE / 255f, alpha = 1f)
    val ambientPurple = Color(red = 0x2F / 255f, green = 0x19 / 255f, blue = 0x5F / 255f, alpha = 1f)
    val deepVoid = Color(red = 0x14 / 255f, green = 0x12 / 255f, blue = 0x1D / 255f, alpha = 1f)
    val haloPurple = Color(red = 0x7F / 255f, green = 0x3F / 255f, blue = 0xB2 / 255f, alpha = 1f)

    // Moon colors — solid dark base disc + bright center gradient
    // Ściemniony purple (prototype feel: deep purple moon) — 40% darker niż było
    val moonDarkBase = Color(red = 0x20 / 255f, green = 0x12 / 255f, blue = 0x3A / 255f, alpha = 1f)
    // Bright core też ściemniony o 40%
    val moonBrightCore = Color(red = 0x92 / 255f, green = 0x8C / 255f, blue = 0x6A / 255f, alpha = 1f)

    Box(modifier = modifier.fillMaxSize()) {
        // Canvas layer 1 — sky base + ambient + moon + stars
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. Vertical sky gradient — jednolity bez radial "glow spots" które dawały
            //    jaśniejszy środek + ciemniejsze rogi. Clean top→bottom gradient only.
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to darkPurpleTop,
                        0.5f to baseVoid,
                        1.0f to mediumPurpleBottom
                    )
                )
            )

            // 3. Moon — 140px top:60 right:100 (pixels from prototype CSS)
            val moonRadius = with(density) { 70.dp.toPx() }
            val moonOffsetRight = with(density) { 100.dp.toPx() }
            val moonOffsetTop = with(density) { 60.dp.toPx() }
            val moonCenter = Offset(w - moonOffsetRight - moonRadius, moonOffsetTop + moonRadius)

            // Outer halo
            val outerHaloRadius = moonRadius + with(density) { 300.dp.toPx() }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(haloPurple.copy(alpha = 0.08f), Color.Transparent),
                    center = moonCenter,
                    radius = outerHaloRadius
                ),
                radius = outerHaloRadius,
                center = moonCenter
            )

            // Mid halo
            val midHaloRadius = moonRadius + with(density) { 150.dp.toPx() }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cream.copy(alpha = 0.10f), Color.Transparent),
                    center = moonCenter,
                    radius = midHaloRadius
                ),
                radius = midHaloRadius,
                center = moonCenter
            )

            // Inner halo
            val innerHaloRadius = moonRadius + with(density) { 60.dp.toPx() }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cream.copy(alpha = 0.22f), Color.Transparent),
                    center = moonCenter,
                    radius = innerHaloRadius
                ),
                radius = innerHaloRadius,
                center = moonCenter
            )

            // MOON SOLID BASE DISC — dark purple solid za moon body, żeby dać "kulę" feel
            drawCircle(
                color = moonDarkBase,
                radius = moonRadius,
                center = moonCenter
            )

            // Moon gradient body — bright core w góra-lewa oświetlony, dark edge
            val moonGradientCenter = Offset(
                moonCenter.x - moonRadius * 0.15f,
                moonCenter.y - moonRadius * 0.2f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        moonBrightCore.copy(alpha = 0.95f),
                        cream.copy(alpha = 0.7f),
                        purple.copy(alpha = 0.3f),
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
                Star(0.08f, 0.12f, 2f, cream.copy(alpha = 0.7f)),
                Star(0.52f, 0.06f, 2.5f, cream.copy(alpha = 0.8f)),
                Star(0.83f, 0.22f, 2f, cream.copy(alpha = 0.6f)),
                Star(0.12f, 0.78f, 1.5f, cream.copy(alpha = 0.4f)),
                Star(0.58f, 0.85f, 2.5f, cream.copy(alpha = 0.5f)),
                Star(0.75f, 0.10f, 2f, cream.copy(alpha = 0.7f)),
                Star(0.92f, 0.52f, 1.5f, cream.copy(alpha = 0.5f)),
                Star(0.65f, 0.32f, 3f, cream.copy(alpha = 0.6f)),
                Star(0.22f, 0.38f, 1.5f, purple.copy(alpha = 0.5f)),
                Star(0.38f, 0.62f, 2f, purple.copy(alpha = 0.5f)),
                Star(0.03f, 0.45f, 1.5f, purple.copy(alpha = 0.35f)),
                Star(0.68f, 0.48f, 1.5f, blueGrey.copy(alpha = 0.5f)),
                Star(0.88f, 0.68f, 2f, blueGrey.copy(alpha = 0.5f)),
                Star(0.45f, 0.30f, 2f, blueGrey.copy(alpha = 0.4f)),
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

        // Layer 2 — Islands SVG overlay (1:1 copy z prototype HTML bg-islands.end-el)
        //
        // **Horizontal fill fix (2026-04-12):** viewBox is -400 -300 2400 1500 (aspect 1.6)
        // ale content jest tylko w 0..1600 strip. Po Crop scale 0.667, content ląduje
        // w window x=267..1333 (1066 wide), zostawiając 267px przezroczyste paski po bokach.
        // graphicsLayer(scaleX=1.5) stretchuje rendering 1.5× horyzontalnie around center —
        // content (267..1333) → (0..1600) fill exact, transparent margins wypadają z window.
        // Wertykalna pozycja (y=150..750) pozostaje BEZ ZMIAN (scaleY = 1.0 default).
        // Trade-off: aspect non-uniform → wyspy ~1.5× szersze niż były. Akceptowalne wg Mateusza.
        Image(
            painter = painterResource("backgrounds/end_islands.svg"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f)
        )
    }
}
