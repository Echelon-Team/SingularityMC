package com.singularity.launcher.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.ui.theme.LocalExtraPalette

@Composable
fun OnboardingWizard(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val extra = LocalExtraPalette.current

    if (state.currentStep == OnboardingStep.COMPLETE) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Content
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (state.currentStep) {
                    OnboardingStep.WELCOME -> WelcomeStep()
                    OnboardingStep.LOGIN -> LoginStep(viewModel)
                    OnboardingStep.HARDWARE_DETECT -> HardwareDetectStep(state)
                    OnboardingStep.TELEMETRY -> TelemetryStep(viewModel, state)
                    OnboardingStep.TUTORIAL -> TutorialStep(viewModel)
                    OnboardingStep.FIRST_INSTANCE -> FirstInstanceStep()
                    OnboardingStep.COMPLETE -> {}
                }
            }

            // Navigation buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.currentStep != OnboardingStep.WELCOME) {
                    OutlinedButton(onClick = { viewModel.back() }) {
                        Text("Wstecz")
                    }
                }
                Spacer(Modifier.weight(1f))
                // Step indicator
                Text(
                    "${state.currentStep.ordinal + 1}/${OnboardingStep.entries.size - 1}",
                    color = extra.textMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.weight(1f))
                Button(onClick = { viewModel.next() }) {
                    Text(if (state.currentStep == OnboardingStep.FIRST_INSTANCE) "Zakoncz" else "Dalej")
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    val extra = LocalExtraPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "SingularityMC",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Wielowatkowy launcher Minecraft z obsluga Fabric, Forge i NeoForge",
            color = extra.textSecondary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Ten krotki tutorial przeprowadzi cie przez pierwsza konfiguracje.",
            color = extra.textPrimary
        )
    }
}

@Composable
private fun LoginStep(viewModel: OnboardingViewModel) {
    val extra = LocalExtraPalette.current
    var nonPremiumNick by remember { mutableStateOf("") }

    Column(modifier = Modifier.widthIn(max = 400.dp)) {
        Text("Wybierz typ konta:", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { /* Microsoft Auth — disabled, stub GUI banner in Sub 4 */ },
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Zaloguj sie kontem Microsoft (Premium)")
        }
        Text(
            "Logowanie kontem Microsoft nie zostalo jeszcze wprowadzone.",
            style = MaterialTheme.typography.bodySmall,
            color = extra.textMuted
        )

        Spacer(Modifier.height(24.dp))

        Text("Lub graj non-premium:")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = nonPremiumNick,
            onValueChange = { nonPremiumNick = it },
            label = { Text("Nick") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { if (nonPremiumNick.isNotBlank()) viewModel.setLoginComplete() },
            enabled = nonPremiumNick.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Graj non-premium jako $nonPremiumNick")
        }
    }
}

@Composable
private fun HardwareDetectStep(state: OnboardingState) {
    val extra = LocalExtraPalette.current
    Column(modifier = Modifier.widthIn(max = 400.dp)) {
        Text("Wykrywanie sprzetu", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        if (state.hardwareInfo == null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Skanowanie sprzetu...", color = extra.textMuted)
        } else {
            val info = state.hardwareInfo
            Text("CPU: ${info.cpuCores} rdzeni / ${info.cpuThreads} watkow", color = extra.textPrimary)
            Text("RAM: ${info.ramMb} MB", color = extra.textPrimary)
            Text("GPU: ${info.gpuName ?: "nie wykryto"}", color = extra.textPrimary)
            Text("OS: ${info.osName}", color = extra.textPrimary)
            Spacer(Modifier.height(16.dp))

            Text(
                "Rekomendowany preset wydajnosci: ${state.recommendedPreset}",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TelemetryStep(viewModel: OnboardingViewModel, state: OnboardingState) {
    Column(modifier = Modifier.widthIn(max = 500.dp)) {
        TelemetryOptInContent(
            onAccept = { viewModel.setTelemetryAccepted(true) },
            onDecline = { viewModel.setTelemetryAccepted(false) },
            currentChoice = state.telemetryAccepted
        )
    }
}

@Composable
private fun TutorialStep(viewModel: OnboardingViewModel) {
    val extra = LocalExtraPalette.current
    Column(modifier = Modifier.widthIn(max = 400.dp)) {
        Text("Szybkie wprowadzenie", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Text("- Instancje — tworzysz i zarzadzasz instancjami gry", color = extra.textPrimary)
        Text("- Modrinth — przegladasz i instalujesz mody", color = extra.textPrimary)
        Text("- Serwery — tworzysz wlasne serwery", color = extra.textPrimary)
        Text("- Diagnostyka — monitorujesz wydajnosc", color = extra.textPrimary)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { viewModel.skipTutorial() }) {
            Text("Znam sie, pomin tutorial")
        }
    }
}

@Composable
private fun FirstInstanceStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Gotowe!", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Text("Kliknij Zakoncz aby przejsc do ekranu glownego i utworzyc pierwsza instancje.")
    }
}
