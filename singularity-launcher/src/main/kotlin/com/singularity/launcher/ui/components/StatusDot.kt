package com.singularity.launcher.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * 3-state status dot dla instancji/serwera — prototype index.html:2073-2077 + 2155-2156.
 *
 * **States (P2 v2 fix — poprzednio 2 stany Boolean):**
 * - **STOPPED**: szara (extra.textMuted) — idle instance/server
 * - **LOADING**: żółta pulsująca (extra.statusWarning) — uruchamianie w toku
 * - **RUNNING**: zielona solidna (extra.statusSuccess) — running
 *
 * Rozszerzona wersja z 6 stanów jest w `StatusIndicator` (Task 4, pełna semantyka).
 * Ten `StatusDot` jest minimalny do list rows / grid footers / panel header.
 *
 * **LOADING pulse**: InfiniteTransition alpha 0.4->1.0 tween(800) Reverse.
 */
enum class RunState { STOPPED, LOADING, RUNNING }

@Composable
fun StatusDot(
    state: RunState,
    modifier: Modifier = Modifier
) {
    val extra = LocalExtraPalette.current
    val baseColor = when (state) {
        RunState.STOPPED -> extra.textMuted
        RunState.LOADING -> extra.statusWarning
        RunState.RUNNING -> extra.statusSuccess
    }

    // LOADING pulse animation
    val alpha = if (state == RunState.LOADING) {
        val infinite = rememberInfiniteTransition(label = "status_dot_pulse")
        val pulseAlpha by infinite.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "status_dot_pulse_alpha"
        )
        pulseAlpha
    } else 1f

    Surface(
        modifier = modifier.size(10.dp).clip(CircleShape),
        color = baseColor.copy(alpha = alpha)
    ) {}
}

/**
 * Backward-compat overload dla starego pattern `StatusDot(isRunning = ...)` — mapa
 * na RunState.RUNNING/STOPPED (bez LOADING). Nowy kod powinien używać głównego overload
 * z RunState enum parametrem.
 */
@Deprecated(
    message = "Use StatusDot(state = RunState.X) for 3-state semantic",
    replaceWith = ReplaceWith("StatusDot(state = if (isRunning) RunState.RUNNING else RunState.STOPPED, modifier = modifier)")
)
@Composable
fun StatusDot(
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    StatusDot(
        state = if (isRunning) RunState.RUNNING else RunState.STOPPED,
        modifier = modifier
    )
}
