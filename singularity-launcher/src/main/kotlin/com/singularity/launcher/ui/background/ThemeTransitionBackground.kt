// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.background

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.launcher.ui.theme.ThemeMode

/**
 * Host composable dla dekoracyjnego tła z Crossfade transition między End i Aether.
 *
 * **Crossfade vs AnimatedContent:** Crossfade preserves obu composable state podczas
 * transition, NIE niszczy ich state. Tu to mniej istotne bo tła są stateless, ale
 * używamy tego samego wzorca konsystentnie.
 *
 * **250ms tween FastOutSlowInEasing** — perceptual ~300ms, dość szybkie dla theme switch
 * bez "lag feel" (wcześniej 400ms + default easing dawało perceptual ~1s).
 */
@Composable
fun ThemeTransitionBackground(
    theme: ThemeMode,
    modifier: Modifier = Modifier
) {
    Crossfade(
        targetState = theme,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "theme_transition_background",
        modifier = modifier.fillMaxSize()
    ) { currentTheme ->
        when (currentTheme) {
            ThemeMode.END -> EndBackground()
            ThemeMode.AETHER -> AetherBackground()
        }
    }
}
