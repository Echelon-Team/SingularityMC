package com.singularity.launcher.onboarding

import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.launch

enum class OnboardingStep {
    WELCOME,
    LOGIN,
    HARDWARE_DETECT,
    TELEMETRY,
    TUTORIAL,
    FIRST_INSTANCE,
    COMPLETE
}

data class OnboardingState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    val hardwareInfo: HardwareDetector.HardwareInfo? = null,
    val recommendedPreset: String? = null,
    val telemetryAccepted: Boolean? = null,
    val loginComplete: Boolean = false
)

class OnboardingViewModel(
    private val hardwareDetector: HardwareDetector
) : BaseViewModel<OnboardingState>(OnboardingState()) {

    fun next() {
        val current = state.value.currentStep
        val nextStep = OnboardingStep.entries.getOrNull(current.ordinal + 1) ?: OnboardingStep.COMPLETE
        updateState { it.copy(currentStep = nextStep) }

        if (nextStep == OnboardingStep.HARDWARE_DETECT) {
            detectHardware()
        }
    }

    fun back() {
        val current = state.value.currentStep
        val prevStep = OnboardingStep.entries.getOrNull(current.ordinal - 1) ?: OnboardingStep.WELCOME
        updateState { it.copy(currentStep = prevStep) }
    }

    fun skipTutorial() {
        updateState { it.copy(currentStep = OnboardingStep.FIRST_INSTANCE) }
    }

    fun setLoginComplete() {
        updateState { it.copy(loginComplete = true) }
    }

    fun setTelemetryAccepted(accepted: Boolean) {
        updateState { it.copy(telemetryAccepted = accepted) }
    }

    private fun detectHardware() {
        viewModelScope.launch {
            val info = hardwareDetector.detect()
            val preset = hardwareDetector.recommendPreset(info)
            updateState { it.copy(hardwareInfo = info, recommendedPreset = preset) }
        }
    }
}
