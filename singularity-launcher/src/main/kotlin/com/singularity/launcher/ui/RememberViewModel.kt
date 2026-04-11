package com.singularity.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.singularity.launcher.viewmodel.BaseViewModel

/**
 * Helper composable eliminating boilerplate dla per-screen ViewModels.
 *
 * Automatycznie wywołuje `onCleared()` przy exit compositione — zapobiega coroutine leaks
 * przy nawigacji między screens.
 *
 * **Usage:**
 * ```
 * val vm = rememberViewModel { MyScreenViewModel(dependency) }
 * ```
 */
@Composable
fun <T : BaseViewModel<*>> rememberViewModel(factory: () -> T): T {
    val vm = remember { factory() }
    DisposableEffect(vm) {
        onDispose { vm.onCleared() }
    }
    return vm
}
