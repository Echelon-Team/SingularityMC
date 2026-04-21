// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.di

import androidx.compose.runtime.compositionLocalOf
import com.singularity.launcher.service.mojang.MojangVersionClient

/**
 * CompositionLocal dla MojangVersionClient — wire w App.kt (Task 32).
 * Nullable — VersionStep fallback na `offlineFallbackVersions()` gdy null lub fetch fails.
 */
val LocalMojangClient = compositionLocalOf<MojangVersionClient?> { null }
