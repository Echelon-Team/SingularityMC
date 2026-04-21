// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.di

import androidx.compose.runtime.compositionLocalOf
import com.singularity.launcher.service.auth.AuthManager

/**
 * CompositionLocal dla AuthManager — wire w App.kt. Używane przez SidebarAvatar
 * żeby pokazać default account nick + first letter, oraz przez AccountOverlay.
 */
val LocalAuthManager = compositionLocalOf<AuthManager> {
    error("LocalAuthManager not provided — wrap content w CompositionLocalProvider(LocalAuthManager provides ...) z App.kt")
}
