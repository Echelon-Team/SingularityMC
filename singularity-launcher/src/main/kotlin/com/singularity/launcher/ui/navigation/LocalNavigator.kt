package com.singularity.launcher.ui.navigation

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal dostarczający `Navigator` do dowolnego głębokiego composable bez
 * prop drilling przez wielu parentów.
 *
 * **Usage w App.kt** (Task 32):
 * ```kotlin
 * val navVm = rememberViewModel { NavigationViewModel() }
 * CompositionLocalProvider(LocalNavigator provides navVm) {
 *     // cały content launchera — wszystkie composables dostają navigator przez LocalNavigator.current
 * }
 * ```
 *
 * **Usage w dowolnym głębokim composable** (np. ModsTabContent w InstancePanel):
 * ```kotlin
 * @Composable
 * fun ModsTabContent(instance: Instance) {
 *     val navigator = LocalNavigator.current
 *     Button(onClick = { navigator.navigateTo(Screen.MODRINTH) }) {
 *         Text("Przeglądaj Modrinth")
 *     }
 * }
 * ```
 *
 * **Why `compositionLocalOf` not `staticCompositionLocalOf`:** dynamic — gdy navigator
 * zmieni się (teoretycznie nie robimy tego w Sub 4, ale future-proofness), Compose
 * triggeruje recomposition consumentów. Static lock'uje wartość przy pierwszym
 * provide — mniej flexibility.
 *
 * **Fail-fast**: default throws — composable NIE może być użyte poza `CompositionLocalProvider`.
 * To zabezpiecza przed zapomnieniem wire'owania w jakimś testowym standalone composable.
 */
val LocalNavigator = compositionLocalOf<Navigator> {
    error("LocalNavigator not provided — wrap content in CompositionLocalProvider(LocalNavigator provides ...) from App.kt")
}
