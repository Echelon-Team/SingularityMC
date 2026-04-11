package com.singularity.launcher.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Pure logic wizarda — testowalne bez Compose UI.
 */
class WizardLogic<T>(private val steps: List<WizardStep<T>>) {
    val totalSteps: Int get() = steps.size

    fun canAdvance(currentStep: Int, state: T): Boolean {
        if (currentStep !in steps.indices) return false
        return steps[currentStep].validate(state)
    }

    fun canGoBack(currentStep: Int): Boolean = currentStep > 0

    fun isLastStep(currentStep: Int): Boolean = currentStep == steps.size - 1

    fun nextStep(currentStep: Int): Int = (currentStep + 1).coerceAtMost(steps.size - 1)

    fun previousStep(currentStep: Int): Int = (currentStep - 1).coerceAtLeast(0)

    fun getStep(currentStep: Int): WizardStep<T>? = steps.getOrNull(currentStep)
}

/**
 * Reusable wizard container — eliminuje duplication w NewInstanceWizard, NewServerWizard,
 * CloneInstanceWizard, ServerVersionUpgradeWizard (4 wizards, każdy miał ~100 linijek
 * navigation + validation + stepper + buttons logic).
 *
 * Layout: ModalDialog z WizardStepper (thin bars) + current step render + actions row
 * z Wstecz/Anuluj/Dalej (lub Utwórz na ostatnim kroku).
 *
 * Usage example (NewInstanceWizard Task 13):
 * ```
 * val steps = listOf(
 *     WizardStep<NewInstanceForm>("Nazwa", validate = { it.name.isNotBlank() }) { state, onUpdate ->
 *         NameStep(state, onUpdate)
 *     },
 *     WizardStep("Wersja", validate = { it.version != null }) { state, onUpdate ->
 *         VersionStep(state, onUpdate)
 *     },
 * )
 * WizardContainer(
 *     title = "Nowa instancja",
 *     initialState = NewInstanceForm(),
 *     steps = steps,
 *     size = ModalSize.LARGE,
 *     onCancel = { viewModel.closeWizard() },
 *     onFinish = { form -> viewModel.createInstance(form) }
 * )
 * ```
 */
@Composable
fun <T> WizardContainer(
    title: String,
    initialState: T,
    steps: List<WizardStep<T>>,
    onCancel: () -> Unit,
    onFinish: (T) -> Unit,
    size: ModalSize = ModalSize.MEDIUM
) {
    val logic = remember(steps) { WizardLogic(steps) }
    var currentStep by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf(initialState) }

    val currentStepDef = logic.getStep(currentStep) ?: return
    val canAdvance = logic.canAdvance(currentStep, state)
    val canGoBack = logic.canGoBack(currentStep)
    val isLast = logic.isLastStep(currentStep)

    ModalDialog(
        title = "$title — krok ${currentStep + 1} z ${logic.totalSteps}",
        onDismiss = onCancel,
        size = size,
        actions = {
            if (canGoBack) {
                OutlinedButton(onClick = { currentStep = logic.previousStep(currentStep) }) {
                    Text("← Wstecz")
                }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = onCancel) {
                Text("Anuluj")
            }
            Spacer(Modifier.width(8.dp))
            if (isLast) {
                Button(
                    onClick = { onFinish(state) },
                    enabled = canAdvance
                ) {
                    Text("Utwórz")
                }
            } else {
                Button(
                    onClick = { currentStep = logic.nextStep(currentStep) },
                    enabled = canAdvance
                ) {
                    Text("Dalej →")
                }
            }
        }
    ) {
        Column {
            WizardStepper(
                currentStep = currentStep,
                totalSteps = logic.totalSteps
            )
            Spacer(Modifier.height(20.dp))
            currentStepDef.render(state) { newState -> state = newState }
        }
    }
}
