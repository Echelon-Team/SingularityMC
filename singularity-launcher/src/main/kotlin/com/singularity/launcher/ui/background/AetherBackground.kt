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

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Colors from prototype CSS
        val skyBlueTop = Color(red = 0x2B / 255f, green = 0x7A / 255f, blue = 0xE0 / 255f, alpha = 1f)
        val skyBlueMid = Color(red = 0x5B / 255f, green = 0xA8 / 255f, blue = 0xD4 / 255f, alpha = 1f)
        val goldenLow = Color(red = 0xD4 / 255f, green = 0xB9 / 255f, blue = 0x64 / 255f, alpha = 1f)
        val goldenHorizon = Color(red = 0xD4 / 255f, green = 0xA5 / 255f, blue = 0x37 / 255f, alpha = 1f)
        val greenGround = Color(red = 0x4C / 255f, green = 0xAF / 255f, blue = 0x6A / 255f, alpha = 1f)
        val sunBright = Color(red = 0xF5 / 255f, green = 0xC8 / 255f, blue = 0x3C / 255f, alpha = 1f)
        val sunGold = Color(red = 0xE6 / 255f, green = 0xAF / 255f, blue = 0x28 / 255f, alpha = 1f)
        val sunDeep = Color(red = 0xD4 / 255f, green = 0x9B / 255f, blue = 0x1E / 255f, alpha = 1f)
        val cloudWhite = Color.White

        // 1. Sky gradient (linear-gradient 180deg matching prototype)
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color(red = 0.90f, green = 0.94f, blue = 0.98f, alpha = 1f), // very light blue-white top
                    0.4f to Color(red = 0.73f, green = 0.87f, blue = 0.95f, alpha = 1f), // mid blue
                    0.75f to Color(red = 0.92f, green = 0.86f, blue = 0.70f, alpha = 1f), // golden horizon
                    1.0f to Color(red = 0.95f, green = 0.78f, blue = 0.52f, alpha = 1f)  // orange ground
                )
            )
        )

        // 2. Prototype .bg-glow overlay gradients
        // linear-gradient(180deg, rgba(43,122,224,0.08) 0%, rgba(91,168,212,0.05) 40%, rgba(212,185,100,0.10) 75%, rgba(212,165,55,0.15) 100%)
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to skyBlueTop.copy(alpha = 0.08f),
                    0.4f to skyBlueMid.copy(alpha = 0.05f),
                    0.75f to goldenLow.copy(alpha = 0.10f),
                    1.0f to goldenHorizon.copy(alpha = 0.15f)
                )
            )
        )
        // radial-gradient(ellipse 60% 30% at 20% 85%, rgba(76, 175, 106, 0.08) 0%, transparent 70%)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(greenGround.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(w * 0.2f, h * 0.85f),
                radius = w * 0.6f
            ),
            topLeft = Offset.Zero,
            size = Size(w, h)
        )
        // radial-gradient(ellipse 90% 20% at 50% 100%, rgba(212, 165, 55, 0.18) 0%, transparent 70%)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(goldenHorizon.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(w / 2f, h),
                radius = w * 0.9f
            ),
            topLeft = Offset.Zero,
            size = Size(w, h)
        )

        // 3. Sun — width: 100px top: 50px right: 120px
        val sunRadius = with(density) { 50.dp.toPx() }
        val sunOffsetRight = with(density) { 120.dp.toPx() }
        val sunOffsetTop = with(density) { 50.dp.toPx() }
        val sunCenter = Offset(w - sunOffsetRight - sunRadius, sunOffsetTop + sunRadius)

        // Outer halo — box-shadow: 0 0 160px 80px rgba(212, 155, 30, 0.05)
        val outerHaloRadius = sunRadius + with(density) { 240.dp.toPx() }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(sunDeep.copy(alpha = 0.05f), Color.Transparent),
                center = sunCenter,
                radius = outerHaloRadius
            ),
            radius = outerHaloRadius,
            center = sunCenter
        )

        // Mid halo — box-shadow: 0 0 80px 40px rgba(230, 175, 40, 0.1)
        val midHaloRadius = sunRadius + with(density) { 120.dp.toPx() }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(sunGold.copy(alpha = 0.1f), Color.Transparent),
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

        // Sun body — radial-gradient(circle at 50% 50%, ...)
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

        // 4. Clouds — 6 ellipse gradients z prototype `.bg-decorations`
        data class Cloud(
            val xFrac: Float,
            val yFrac: Float,
            val widthDp: Float,
            val heightDp: Float,
            val alpha: Float
        )

        val clouds = listOf(
            Cloud(0.12f, 0.15f, 250f, 55f, 0.75f),
            Cloud(0.55f, 0.08f, 200f, 45f, 0.65f),
            Cloud(0.78f, 0.25f, 280f, 50f, 0.60f),
            Cloud(0.25f, 0.45f, 220f, 45f, 0.50f),
            Cloud(0.65f, 0.58f, 180f, 40f, 0.45f),
            Cloud(0.40f, 0.75f, 240f, 42f, 0.40f)
        )

        clouds.forEach { cloud ->
            val cloudW = with(density) { cloud.widthDp.dp.toPx() }
            val cloudH = with(density) { cloud.heightDp.dp.toPx() }
            val cx = w * cloud.xFrac
            val cy = h * cloud.yFrac

            // Compose nie ma drawOval with brush directly — draw rect clipped przez radialGradient
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(cloudWhite.copy(alpha = cloud.alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = cloudW / 2f
                ),
                topLeft = Offset(cx - cloudW / 2f, cy - cloudH / 2f),
                size = Size(cloudW, cloudH)
            )
        }
    }
}
