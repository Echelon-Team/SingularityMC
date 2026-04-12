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
        if (current == OnboardingStep.COMPLETE) return
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
            // Phase 1: CPU + RAM (instant, JVM API)
            val quickInfo = hardwareDetector.detectQuick()
            val preset = hardwareDetector.recommendPreset(quickInfo)
            updateState { it.copy(hardwareInfo = quickInfo, recommendedPreset = preset) }

            // Phase 2: GPU (async, may take seconds or timeout)
            val gpuName = hardwareDetector.detectGpuAsync()
            if (gpuName != null) {
                updateState { it.copy(hardwareInfo = it.hardwareInfo?.copy(gpuName = gpuName)) }
            }
        }
    }
}
