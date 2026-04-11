package com.singularity.launcher.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Status types z kolorem i animation hint.
 *
 * - **SUCCESS** (zielony): gra uruchomiona, backup OK, mod kompatybilny
 * - **WARNING** (żółty): możliwy konflikt, aktualizacja dostępna
 * - **ERROR** (czerwony): crash, incompatible
 * - **INFO** (niebieski): informational
 * - **IDLE** (szary, disabled): zatrzymany, nieużywany
 * - **LOADING** (żółty, **pulsing**): ładowanie, uruchamianie — jedyny animated state
 */
enum class Status(val isAnimated: Boolean = false) {
    SUCCESS,
    WARNING,
    ERROR,
    INFO,
    IDLE,
    LOADING(isAnimated = true)
}

/**
 * Wskaźnik statusu — kolorowa kropka + opcjonalny label.
 *
 * LOADING state ma `InfiniteTransition` alpha 0.4→1.0 z `tween(800) RepeatMode.Reverse`
 * — matcha prototyp CSS pulsing animation. Inne statusy są statyczne.
 *
 * **Performance uwaga**: `rememberInfiniteTransition` działa przez cały czas gdy
 * composable jest w composition — w Task 32 App.kt dodamy `LocalWindowInfo.isWindowFocused`
 * check globalny (Task 25 backgrounds pattern), żeby pause gdy launcher nie ma focusa.
 */
@Composable
fun StatusIndicator(
    status: Status,
    label: String? = null,
    size: Int = 10
) {
    val extra = LocalExtraPalette.current
    val color = when (status) {
        Status.SUCCESS -> extra.statusSuccess
        Status.WARNING -> extra.statusWarning
        Status.ERROR -> extra.statusError
        Status.INFO -> extra.statusInfo
        Status.IDLE -> extra.textDisabled
        Status.LOADING -> extra.statusWarning  // yellow, like warning, ale z pulse
    }

    val alpha = if (status.isAnimated) {
        val transition = rememberInfiniteTransition(label = "status-pulse")
        val animatedAlpha by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "status-alpha"
        )
        animatedAlpha
    } else {
        1.0f
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(color)
                .alpha(alpha)
        )
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = extra.textSecondary,
                fontSize = 12.sp
            )
        }
    }
}
