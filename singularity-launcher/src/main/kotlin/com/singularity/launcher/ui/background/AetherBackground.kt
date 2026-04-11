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
 * Dekoracyjne tło Aether — **Canvas-based drawing matching prototype CSS**.
 *
 * Source of truth: `docs/visual-companion/index.html` `.bg-glow` + `.bg-orb` + `.bg-decorations`
 * dla `:root[data-theme="aether"]`.
 *
 * **4 warstwy:**
 * 1. Sky gradient — blue day → golden horizon (matching linear-gradient 180deg)
 * 2. Ambient ground glow — 2 radial gradients dla golden ground reflection
 * 3. Sun 100px top:50 right:120 — radial gradient + multi-layer halo
 * 4. 6 clouds jako ellipse gradients
 *
 * **Performance fix:** Usunięte cloudOffset animation — tło statyczne.
 */
@Composable
fun AetherBackground(modifier: Modifier = Modifier) {
    val density = LocalDensity.current

    // Prototype --base dla aether = #9EBCD2 (sky blue — rich saturated)
    // BLUE dominuje, golden tylko bardzo subtle hint przy samym dole (Mateusz: za dużo pomarańczu)
    val skyTopLight = Color(red = 0xBE / 255f, green = 0xD9 / 255f, blue = 0xEC / 255f, alpha = 1f)  // very light blue top
    val skyMid = Color(red = 0x9E / 255f, green = 0xBC / 255f, blue = 0xD2 / 255f, alpha = 1f)      // --base
    val skyLow = Color(red = 0xA8 / 255f, green = 0xC0 / 255f, blue = 0xD0 / 255f, alpha = 1f)      // paler blue lower
    val skyBottom = Color(red = 0xB0 / 255f, green = 0xBE / 255f, blue = 0xC4 / 255f, alpha = 1f)   // very subtle greyish blue

    val skyBlueTop = Color(red = 0x2B / 255f, green = 0x7A / 255f, blue = 0xE0 / 255f, alpha = 1f)
    val greenGround = Color(red = 0x4C / 255f, green = 0xAF / 255f, blue = 0x6A / 255f, alpha = 1f)
    val goldenHorizon = Color(red = 0xD4 / 255f, green = 0xA5 / 255f, blue = 0x37 / 255f, alpha = 1f)
    val sunBright = Color(red = 0xF5 / 255f, green = 0xC8 / 255f, blue = 0x3C / 255f, alpha = 1f)
    val sunGold = Color(red = 0xE6 / 255f, green = 0xAF / 255f, blue = 0x28 / 255f, alpha = 1f)
    val sunDeep = Color(red = 0xD4 / 255f, green = 0x9B / 255f, blue = 0x1E / 255f, alpha = 1f)
    val cloudWhite = Color.White

    Box(modifier = modifier.fillMaxSize()) {
        // Canvas layer 1 — sky base + ambient + sun + clouds
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. Sky gradient — clean vertical, bez radial spots które tworzyły
            //    "paski po bokach". Top→bottom only, 95% blue dominance.
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to skyTopLight,
                        0.35f to skyMid,
                        0.75f to skyLow,
                        1.0f to skyBottom
                    )
                )
            )

            // 3. Sun — width: 100px top: 50px right: 120px
            val sunRadius = with(density) { 50.dp.toPx() }
            val sunOffsetRight = with(density) { 120.dp.toPx() }
            val sunOffsetTop = with(density) { 50.dp.toPx() }
            val sunCenter = Offset(w - sunOffsetRight - sunRadius, sunOffsetTop + sunRadius)

            // Outer halo
            val outerHaloRadius = sunRadius + with(density) { 240.dp.toPx() }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(sunDeep.copy(alpha = 0.08f), Color.Transparent),
                    center = sunCenter,
                    radius = outerHaloRadius
                ),
                radius = outerHaloRadius,
                center = sunCenter
            )

            // Mid halo
            val midHaloRadius = sunRadius + with(density) { 120.dp.toPx() }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(sunGold.copy(alpha = 0.15f), Color.Transparent),
                    center = sunCenter,
                    radius = midHaloRadius
                ),
                radius = midHaloRadius,
                center = sunCenter
            )

            // Inner halo — box-shadow: 0 0 30px 15px rgba(245, 200, 60, 0.2)
            val innerHaloRadius = sunRadius + with(density) { 45.dp.toPx() }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(sunBright.copy(alpha = 0.2f), Color.Transparent),
                    center = sunCenter,
                    radius = innerHaloRadius
                ),
                radius = innerHaloRadius,
                center = sunCenter
            )

            // Sun body — radial-gradient tylko (bez solid disc — Mateusz chce poprzednie)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        sunBright.copy(alpha = 0.65f),
                        sunGold.copy(alpha = 0.4f),
                        sunDeep.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = sunCenter,
                    radius = sunRadius
                ),
                radius = sunRadius,
                center = sunCenter
            )

            // 4. Clouds — drawOval dla prawdziwych ellips (nie drawRect z radial gradient)
            data class Cloud(
                val xFrac: Float,
                val yFrac: Float,
                val widthDp: Float,
                val heightDp: Float,
                val alpha: Float
            )

            val clouds = listOf(
                Cloud(0.12f, 0.15f, 250f, 55f, 0.70f),
                Cloud(0.55f, 0.08f, 200f, 45f, 0.60f),
                Cloud(0.78f, 0.25f, 280f, 50f, 0.55f),
                Cloud(0.25f, 0.45f, 220f, 45f, 0.45f),
                Cloud(0.65f, 0.58f, 180f, 40f, 0.40f),
                Cloud(0.40f, 0.75f, 240f, 42f, 0.35f)
            )

            clouds.forEach { cloud ->
                val cloudW = with(density) { cloud.widthDp.dp.toPx() }
                val cloudH = with(density) { cloud.heightDp.dp.toPx() }
                val cx = w * cloud.xFrac
                val cy = h * cloud.yFrac

                // drawOval with radialGradient fade — true ellipse shape
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            cloudWhite.copy(alpha = cloud.alpha),
                            cloudWhite.copy(alpha = cloud.alpha * 0.4f),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = cloudW / 2f
                    ),
                    topLeft = Offset(cx - cloudW / 2f, cy - cloudH / 2f),
                    size = Size(cloudW, cloudH)
                )
            }
        }

        // Layer 2 — Islands SVG overlay (1:1 copy z prototype HTML bg-islands.aether-el)
        //
        // **Horizontal fill fix (2026-04-12):** viewBox -400 -300 2400 1500 (aspect 1.6) +
        // content w 0..1600 strip → po Crop scale 0.667 zostają 267px paski po bokach.
        // graphicsLayer(scaleX=1.5) stretchuje horyzontalnie around center. Wertykalna
        // pozycja bez zmian (scaleY default 1.0). Aspekt wysp 1.5× szerszy niż SVG source.
        Image(
            painter = painterResource("backgrounds/aether_islands.svg"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f)
        )
    }
}
