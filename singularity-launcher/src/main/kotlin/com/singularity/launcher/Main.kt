// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.singularity.launcher.config.OfflineMode
import com.singularity.launcher.ui.App

fun main(args: Array<String>) {
    // Must run before any UI / network / ViewModel init so downstream checks are correct.
    OfflineMode.parseArgs(args)

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "SingularityMC",
            state = rememberWindowState(
                width = 1600.dp,
                height = 900.dp,
            ),
        ) {
            App()
        }
    }
}
