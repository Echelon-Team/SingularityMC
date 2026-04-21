// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances.wizard

import androidx.compose.runtime.Composable
import com.singularity.common.model.InstanceConfig
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.components.WizardContainer
import com.singularity.launcher.ui.components.WizardStep

/**
 * NewInstanceWizard — 5 kroków STAŁY (D5 Mateusz):
 * 1. Name → 2. Version → 3. Loader → 4. Resources → 5. Summary
 */
@Composable
fun NewInstanceWizard(
    onCancel: () -> Unit,
    onCreate: (InstanceConfig) -> Unit
) {
    val i18n = LocalI18n.current

    val steps = listOf(
        WizardStep<NewInstanceForm>(
            title = i18n["wizard.new_instance.step.name"],
            validate = NewInstanceWizardLogic::nameStepValidate,
            render = { state, onUpdate -> NameStep(state, onUpdate) }
        ),
        WizardStep(
            title = i18n["wizard.new_instance.step.version"],
            validate = NewInstanceWizardLogic::versionStepValidate,
            render = { state, onUpdate -> VersionStep(state, onUpdate) }
        ),
        WizardStep(
            title = i18n["wizard.new_instance.step.loader"],
            validate = NewInstanceWizardLogic::loaderStepValidate,
            render = { state, onUpdate -> LoaderStep(state, onUpdate) }
        ),
        WizardStep(
            title = i18n["wizard.new_instance.step.resources"],
            validate = { NewInstanceWizardLogic.resourcesStepValidate(it) },
            render = { state, onUpdate -> ResourcesStep(state, onUpdate) }
        ),
        WizardStep(
            title = i18n["wizard.new_instance.step.summary"],
            validate = { true },
            render = { state, onUpdate -> SummaryStep(state, onUpdate) }
        )
    )

    WizardContainer(
        title = i18n["wizard.new_instance.title"],
        initialState = NewInstanceForm(),
        steps = steps,
        onCancel = onCancel,
        onFinish = { form -> onCreate(NewInstanceWizardLogic.toInstanceConfig(form)) },
        size = ModalSize.LARGE
    )
}
