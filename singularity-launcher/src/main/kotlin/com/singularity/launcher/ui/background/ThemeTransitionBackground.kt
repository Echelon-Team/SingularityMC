package com.singularity.launcher.ui.background

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.launcher.ui.theme.ThemeMode

/**
 * Host composable dla dekoracyjnego tła z Crossfade transition między End i Aether.
 *
 * **Crossfade vs AnimatedContent:** Crossfade preserves obu composable state podczas
 * transition, NIE niszczy ich state (jak AnimatedContent w Task 2 v1 bug). Tu jest to
 * mniej istotne bo tła są stateless, ale używamy tego samego wzorca konsystentnie.
 *
 * **400ms tween** — wystarczająco do smooth wizualnego feel, nie za wolno.
 */
@Composable
fun ThemeTransitionBackground(
    theme: ThemeMode,
    modifier: Modifier = Modifier
) {
    Crossfade(
        targetState = theme,
        animationSpec = tween(durationMillis = 400),
        label = "theme_transition_background",
        modifier = modifier.fillMaxSize()
    ) { currentTheme ->
        when (currentTheme) {
            ThemeMode.END -> EndBackground()
            ThemeMode.AETHER -> AetherBackground()
        }
    }
}
