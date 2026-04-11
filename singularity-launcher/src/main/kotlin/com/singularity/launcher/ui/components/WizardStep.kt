package com.singularity.launcher.ui.components

import androidx.compose.runtime.Composable

/**
 * Definicja pojedynczego kroku wizarda.
 *
 * @param title Tytuł kroku — pokazywany w tytule modalu ("Wizard title — krok X z N")
 *              i jako log w progress tracking. Może być hardcoded lub i18n key.
 * @param validate Funkcja sprawdzająca czy state jest valid żeby user mógł iść dalej.
 *                 Default: always valid (`{ true }`).
 * @param render Composable renderujący content tego kroku. Dostaje current `state: T`
 *               i lambda `onUpdate: (T) -> Unit` żeby mógł emit zmiany.
 */
data class WizardStep<T>(
    val title: String,
    val validate: (T) -> Boolean = { true },
    val render: @Composable (state: T, onUpdate: (T) -> Unit) -> Unit = { _, _ -> }
)
