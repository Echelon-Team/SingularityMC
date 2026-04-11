package com.singularity.launcher.ui.background

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Dekoracyjne tło Aether — pixel-perfect SVG z prototype.
 *
 * **Animation:** chmury gentle X offset sway przez `InfiniteTransition` (D9). Pause gdy
 * !focused (S2 perf v2).
 */
@Composable
fun AetherBackground(modifier: Modifier = Modifier) {
    val windowInfo = LocalWindowInfo.current
    val shouldAnimate = BackgroundAnimationLogic.shouldAnimate(windowInfo.isWindowFocused)

    val infinite = rememberInfiniteTransition(label = "aether_bg_clouds")
    val cloudOffset by infinite.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aether_cloud_offset"
    )

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource("backgrounds/aether.svg"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .offset(x = if (shouldAnimate) cloudOffset.dp else 0.dp)
        )
    }
}
