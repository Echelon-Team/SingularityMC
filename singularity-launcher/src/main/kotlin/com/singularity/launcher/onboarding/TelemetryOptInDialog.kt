package com.singularity.launcher.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.launcher.ui.theme.LocalExtraPalette

@Composable
fun TelemetryOptInContent(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    currentChoice: Boolean?
) {
    val extra = LocalExtraPalette.current

    Column {
        Text(
            "Telemetria",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        Text(
            "Pomóż ulepszać SingularityMC. Telemetria jest WYŁĄCZONA domyślnie i możesz zmienić wybór w dowolnym momencie w ustawieniach.",
            color = extra.textSecondary
        )

        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = extra.cardBg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Zbieramy:", fontWeight = FontWeight.Bold, color = extra.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text("- Wersja MC, launchera, modułu compat", color = extra.textSecondary)
                Text("- Liczba zainstalowanych modów (NIE nazwy)", color = extra.textSecondary)
                Text("- OS, Java, RAM, GPU", color = extra.textSecondary)
                Text("- Crash count (licznik)", color = extra.textSecondary)
                Text("- Enhanced vs Vanilla mode", color = extra.textSecondary)
                Text("- Czas sesji", color = extra.textSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = extra.cardBg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("NIE zbieramy:", fontWeight = FontWeight.Bold, color = extra.statusSuccess)
                Spacer(Modifier.height(4.dp))
                Text("- Nick, IP, dane konta", color = extra.textSecondary)
                Text("- Nazwy modów, nazwy światów", color = extra.textSecondary)
                Text("- Niczego personalnego", color = extra.textSecondary)
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (currentChoice == false) "Nie, dziękuję (wybrano)" else "Nie, dziękuję")
            }
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (currentChoice == true) "Tak, pomagam (wybrano)" else "Tak, pomagam")
            }
        }
    }
}
