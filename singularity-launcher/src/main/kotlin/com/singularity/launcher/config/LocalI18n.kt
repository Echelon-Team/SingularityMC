// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.config

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal dostarczający `I18n` do dowolnego głębokiego composable.
 *
 * **Usage w App.kt** (Task 32):
 * ```
 * val launcherSettings = remember { LauncherSettings.load() }
 * val i18n = remember(launcherSettings.language) {
 *     I18n.loadFromResources(defaultLanguage = launcherSettings.language)
 * }
 * CompositionLocalProvider(LocalI18n provides i18n) {
 *     // app content
 * }
 * ```
 *
 * **Usage w dowolnym composable:**
 * ```
 * val i18n = LocalI18n.current
 * Text(i18n["nav.home"])
 * Text(i18n["action.save"])
 * ```
 *
 * Fail-fast default — error gdy LocalI18n nie jest dostarczone. Zapobiega silent
 * fallbackom które utrudniają debug brakującego wire'owania.
 */
val LocalI18n = compositionLocalOf<I18n> {
    error("LocalI18n not provided — wrap content in CompositionLocalProvider(LocalI18n provides ...) from App.kt")
}
