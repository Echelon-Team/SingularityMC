// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.components

/**
 * Sidebar pin state machine — zgodnie z prototypem (index.html:3302-3306, 4252-4265).
 *
 * - **AUTO** (default): sidebar expanded = isHovered (reaktywny na mysz)
 * - **PINNED_EXPANDED**: sidebar zawsze expanded, ignoruje hover
 * - **PINNED_COLLAPSED**: sidebar zawsze collapsed (60dp), ignoruje hover
 *
 * **Interakcje** (z prototypu):
 * - Single click na sidebar background → cycleOnClick: AUTO ↔ PINNED_EXPANDED
 * - Double click na sidebar background → toggleOnDoubleClick: AUTO ↔ PINNED_COLLAPSED
 *
 * State machine transitions:
 * ```
 *     single click                   single click
 *  AUTO ────────────────→ PINNED_EXPANDED ────────────────→ AUTO
 *   │                                                         │
 *   │ double click                      double click          │
 *   ↓                                         ↑               │
 *  PINNED_COLLAPSED ────────────────────────────────────────────┘
 *                      single click
 * ```
 */
enum class SidebarPinState {
    AUTO,
    PINNED_EXPANDED,
    PINNED_COLLAPSED;

    /** Czy sidebar powinien być rozszerzony biorąc pod uwagę pin state + hover. */
    fun isExpanded(isHovered: Boolean): Boolean = when (this) {
        AUTO -> isHovered
        PINNED_EXPANDED -> true
        PINNED_COLLAPSED -> false
    }

    /** Single click: AUTO → PINNED_EXPANDED, PINNED_EXPANDED → AUTO, PINNED_COLLAPSED → AUTO. */
    fun cycleOnClick(): SidebarPinState = when (this) {
        AUTO -> PINNED_EXPANDED
        PINNED_EXPANDED -> AUTO
        PINNED_COLLAPSED -> AUTO
    }

    /** Double click: AUTO → PINNED_COLLAPSED, PINNED_COLLAPSED → AUTO, PINNED_EXPANDED → PINNED_COLLAPSED. */
    fun toggleOnDoubleClick(): SidebarPinState = when (this) {
        AUTO -> PINNED_COLLAPSED
        PINNED_COLLAPSED -> AUTO
        PINNED_EXPANDED -> PINNED_COLLAPSED
    }
}
