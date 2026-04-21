// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Modal dialog sizes — matcha prototype index.html:1463-1466.
 *
 * - **SMALL** (420dp): short dialogs, confirmations, simple inputs (np. "Czy na pewno usunąć?")
 * - **MEDIUM** (550dp): standard modals, most wizards (np. NewInstanceWizard)
 * - **LARGE** (720dp): settings modals, detailed forms (np. InstanceSettingsModal)
 * - **XLARGE** (900dp): crash reports, import warnings, complex layouts
 */
enum class ModalSize(val width: Dp) {
    SMALL(420.dp),
    MEDIUM(550.dp),
    LARGE(720.dp),
    XLARGE(900.dp)
}
