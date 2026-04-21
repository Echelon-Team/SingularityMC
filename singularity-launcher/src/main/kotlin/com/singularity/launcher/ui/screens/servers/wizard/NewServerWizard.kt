// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.servers.wizard

import androidx.compose.runtime.Composable
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.service.ServerConfig
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.components.WizardContainer
import com.singularity.launcher.ui.components.WizardStep

/**
 * Wizard tworzenia nowego serwera — 7 kroków.
 *
 * @param availableInstances lista instancji do wyboru jako parent (wymagane Step 1)
 * @param usedPorts zestaw portów już używanych przez inne serwery (walidacja Step 6)
 */
@Composable
fun NewServerWizard(
    availableInstances: List<InstanceManager.Instance>,
    usedPorts: Set<Int>,
    onCancel: () -> Unit,
    onCreate: (ServerConfig) -> Unit
) {
    val i18n = LocalI18n.current

    val steps = listOf(
        WizardStep<NewServerForm>(
            title = i18n["new_server_wizard.step.instance"],
            validate = NewServerWizardLogic::instanceStepValidate,
            render = { state, onUpdate -> InstanceStep(state, onUpdate, availableInstances) }
        ),
        WizardStep(
            title = i18n["new_server_wizard.step.name"],
            validate = NewServerWizardLogic::nameStepValidate,
            render = { state, onUpdate -> ServerNameStep(state, onUpdate) }
        ),
        WizardStep(
            title = i18n["new_server_wizard.step.version"],
            validate = NewServerWizardLogic::versionStepValidate,
            render = { state, onUpdate -> ServerVersionStep(state, onUpdate) }
        ),
        WizardStep(
            title = i18n["new_server_wizard.step.loader"],
            validate = NewServerWizardLogic::loaderStepValidate,
            render = { state, onUpdate -> ServerLoaderStep(state, onUpdate) }
        ),
        WizardStep(
            title = i18n["new_server_wizard.step.mods_copy"],
            validate = NewServerWizardLogic::serverModsStepValidate,
            render = { state, onUpdate -> ServerModsCopyStep(state, onUpdate) }
        ),
        WizardStep(
            title = i18n["new_server_wizard.step.port"],
            validate = { NewServerWizardLogic.portStepValidate(it, usedPorts) },
            render = { state, onUpdate -> ServerPortRamStep(state, onUpdate, usedPorts) }
        ),
        WizardStep(
            title = i18n["new_server_wizard.step.summary"],
            validate = NewServerWizardLogic::summaryStepValidate,
            render = { state, onUpdate -> ServerSummaryStep(state, onUpdate) }
        )
    )

    WizardContainer(
        title = i18n["new_server_wizard.title"],
        initialState = NewServerForm(),
        steps = steps,
        onCancel = onCancel,
        onFinish = { form -> onCreate(NewServerWizardLogic.toServerConfig(form)) },
        size = ModalSize.LARGE
    )
}
