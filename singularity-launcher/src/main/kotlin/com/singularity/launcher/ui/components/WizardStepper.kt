package com.singularity.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Wizard stepper — thin progress bars 4px.
 *
 * **Pixel-perfect z prototypu index.html:1420-1440:**
 * ```
 * .wizard-steps { display: flex; gap: 8px; margin-bottom: 20px; }
 * .wizard-step {
 *     flex: 1;
 *     height: 4px;
 *     border-radius: 2px;
 *     background: var(--surface-1);
 * }
 * .wizard-step.done { background: var(--accent-primary); }
 * .wizard-step.current { background: var(--accent-secondary); }
 * ```
 *
 * **NOT numbered circles** — to był stary design plan, zmieniony w rewrite 2026-04-11
 * (C6 v3 design review). Thin bars są minimalistyczne, zero labels (labels są w tytule
 * modalu "Wizard — krok X z N"), pozwalają więcej miejsca na content.
 */
@Composable
fun WizardStepper(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(totalSteps) { index ->
            val color = when {
                index < currentStep -> MaterialTheme.colorScheme.primary
                index == currentStep -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}
