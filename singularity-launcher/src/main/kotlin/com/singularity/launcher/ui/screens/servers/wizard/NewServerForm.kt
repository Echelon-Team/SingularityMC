// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.servers.wizard

import com.singularity.launcher.service.ServerConfig

/**
 * Mutable form state dla NewServerWizard. 7 kroków: Instance → Name → Version → Loader
 * → ServerMods → Port → Summary.
 */
data class NewServerForm(
    val selectedInstanceId: String? = null,
    val name: String = "",
    val mcVersion: String = "",
    val enhancedMode: Boolean = false,  // w Sub 4 disabled — Enhanced wymaga Sub 5
    val copyServerSideMods: Boolean = true,
    val port: Int = 25565,
    val ramMb: Int = 4096,
    val threads: Int = 4,
    val motd: String = "A Minecraft Server",
    val maxPlayers: Int = 20
)

/**
 * Pure logic dla NewServerWizard — walidacja per step, conflict detection portów, transform.
 */
object NewServerWizardLogic {

    const val MAX_NAME_LENGTH = 64
    const val MIN_RAM_MB = 1024

    fun instanceStepValidate(form: NewServerForm): Boolean =
        form.selectedInstanceId != null

    fun nameStepValidate(form: NewServerForm): Boolean {
        val trimmed = form.name.trim()
        return trimmed.isNotBlank() && trimmed.length <= MAX_NAME_LENGTH
    }

    fun versionStepValidate(form: NewServerForm): Boolean =
        form.mcVersion.isNotBlank()

    /**
     * Enhanced mode w Sub 4 jest DISABLED — user nie może wybrać Enhanced, więc `enhancedMode`
     * zawsze false. Jeśli (jakimś cudem) jest true → walidacja fails (nie proceeds). Vanilla
     * (enhancedMode=false) zawsze valid.
     */
    fun loaderStepValidate(form: NewServerForm): Boolean = !form.enhancedMode

    fun serverModsStepValidate(form: NewServerForm): Boolean = true  // info only, no input required

    fun portStepValidate(
        form: NewServerForm,
        usedPorts: Set<Int> = emptySet()
    ): Boolean {
        val portOk = form.port in 1..65535 && form.port !in usedPorts
        val ramOk = form.ramMb >= MIN_RAM_MB
        val threadsOk = form.threads in 1..32
        return portOk && ramOk && threadsOk
    }

    fun summaryStepValidate(form: NewServerForm): Boolean = true

    fun toServerConfig(form: NewServerForm): ServerConfig {
        return ServerConfig(
            name = form.name.trim(),
            minecraftVersion = form.mcVersion,
            parentInstanceId = form.selectedInstanceId,
            port = form.port,
            ramMb = form.ramMb,
            threads = form.threads,
            motd = form.motd,
            maxPlayers = form.maxPlayers
        )
    }
}
