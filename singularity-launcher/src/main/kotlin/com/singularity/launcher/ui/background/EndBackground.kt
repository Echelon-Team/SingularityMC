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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource

/**
 * Dekoracyjne tło End Dimension — pixel-perfect SVG z prototype.
 *
 * **Animation:** subtle alpha pulse przez InfiniteTransition. Pause gdy
 * `LocalWindowInfo.current.isWindowFocused == false` (D9 decyzja + S2 perf v2).
 *
 * SVG layer approach uproszczone w Sub 4 — całe tło jako jeden image, subtle alpha
 * pulse daje wrażenie "życia" bez per-element splitting.
 */
@Composable
fun EndBackground(modifier: Modifier = Modifier) {
    val windowInfo = LocalWindowInfo.current
    val shouldAnimate = BackgroundAnimationLogic.shouldAnimate(windowInfo.isWindowFocused)

    val infinite = rememberInfiniteTransition(label = "end_bg_stars")
    val pulse by infinite.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "end_bg_pulse"
    )

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource("backgrounds/end_dimension.svg"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = if (shouldAnimate) pulse else 1f,
            modifier = Modifier.fillMaxSize()
        )
    }
}
