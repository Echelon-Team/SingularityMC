// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.skins

import java.nio.file.Path

/**
 * Model type — Steve (classic, 4px arms) vs Alex (slim, 3px arms).
 */
enum class SkinModel { STEVE, ALEX }

/**
 * Skin entry — metadata dla single skin file.
 */
data class SkinEntry(
    val path: Path,
    val name: String,
    val model: SkinModel,
    val isDefault: Boolean = false
)
