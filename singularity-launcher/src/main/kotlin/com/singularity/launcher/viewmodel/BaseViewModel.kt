package com.singularity.launcher.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing

/**
 * Bazowy ViewModel dla Compose Desktop.
 *
 * Nie używamy `androidx.lifecycle.ViewModel` (wymaga Android dependencies).
 * Zamiast tego własny lightweight ViewModel z `CoroutineScope` i `StateFlow`.
 *
 * **Dispatchers.Swing** — nie `Dispatchers.Main`. Compose Desktop renderuje przez
 * Swing EDT (AWT EventQueue). `kotlinx-coroutines-swing` (libs.versions.toml linia 21)
 * dostarcza `Dispatchers.Swing` który jest jednoznaczny. `Dispatchers.Main` też działa
 * bo main dispatcher factory rejestruje się w service loader, ale `Swing` jest
 * eksplicytny i safer (nie ma class-loading race na startup).
 *
 * **Każdy screen ma własny ViewModel** z `DisposableEffect(onDispose { vm.onCleared() })`
 * w kontenerze screen — inaczej coroutines wyciekają przy nawigacji (patrz Task 32
 * App.kt integration gdzie jest helper `rememberViewModel`).
 */
abstract class BaseViewModel<S>(initialState: S) {

    protected val viewModelScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + kotlinx.coroutines.Dispatchers.Swing
    )

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    protected fun updateState(update: (S) -> S) {
        _state.value = update(_state.value)
    }

    protected fun setState(newState: S) {
        _state.value = newState
    }

    open fun onCleared() {
        viewModelScope.cancel()
    }
}
