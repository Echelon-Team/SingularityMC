package com.singularity.launcher.ui.screens.instances.wizard

import com.singularity.common.model.InstanceConfig
import com.singularity.common.model.InstanceType
import com.singularity.common.model.LoaderType

/**
 * Mutable form state dla NewInstanceWizard.
 */
data class NewInstanceForm(
    val name: String = "",
    val selectedVersion: VersionOption? = null,
    val loader: LoaderType = LoaderType.NONE,
    val ramMb: Int = 4096,
    val threads: Int = 4
)

/**
 * Wersja Minecraft dostępna w wizardzie.
 *
 * - **id**: unique key dla LazyColumn (np. "1.20.1-enhanced", "1.16.5-vanilla")
 * - **mcVersion**: actual MC version string
 * - **type**: Enhanced (SingularityMC compat layer) lub Vanilla (czysty MC)
 * - **label**: display name
 * - **subtitle**: description
 */
data class VersionOption(
    val id: String,
    val mcVersion: String,
    val type: InstanceType,
    val label: String,
    val subtitle: String
)

/**
 * Pure logic helpers dla NewInstanceWizard — testowalne bez Compose UI.
 */
object NewInstanceWizardLogic {

    const val MAX_NAME_LENGTH = 64
    const val MIN_RAM_MB = 1024

    fun nameStepValidate(form: NewInstanceForm): Boolean {
        val trimmed = form.name.trim()
        return trimmed.isNotBlank() && trimmed.length <= MAX_NAME_LENGTH
    }

    fun versionStepValidate(form: NewInstanceForm): Boolean =
        form.selectedVersion != null

    fun loaderStepValidate(form: NewInstanceForm): Boolean {
        // Enhanced ignoruje loader, Vanilla (w tym NONE) zawsze valid
        return true
    }

    fun resourcesStepValidate(
        form: NewInstanceForm,
        totalRam: Int = com.singularity.launcher.service.HardwareInfo.totalRamMB,
        maxThreads: Int = com.singularity.launcher.service.HardwareInfo.maxAssignableThreads
    ): Boolean {
        val ramOk = form.ramMb in MIN_RAM_MB..totalRam
        val threadsOk = form.threads in 2..maxThreads
        return ramOk && threadsOk
    }

    /**
     * Live fetch versions z Mojang piston-meta. Fallback na offlineFallbackVersions() gdy
     * client null lub fetch fails.
     */
    suspend fun loadVersionOptions(
        mojangClient: com.singularity.launcher.service.mojang.MojangVersionClient?
    ): List<VersionOption> {
        val mojangVersions = if (mojangClient != null) {
            mojangClient.fetchReleaseVersions().getOrElse { emptyList() }
        } else emptyList()

        val vanillaOptions = mojangVersions.map { mv ->
            VersionOption(
                id = "${mv.id}-vanilla",
                mcVersion = mv.id,
                type = InstanceType.VANILLA,
                label = "${mv.id} — Vanilla",
                subtitle = "czysty Minecraft (Mojang ${mv.releaseTime.take(10)})"
            )
        }

        val enhanced = VersionOption(
            id = "1.20.1-enhanced",
            mcVersion = "1.20.1",
            type = InstanceType.ENHANCED,
            label = "1.20.1 — Enhanced",
            subtitle = "wielowątkowość + compat layer"
        )

        return if (vanillaOptions.isEmpty()) {
            listOf(enhanced) + offlineFallbackVersions().filter { it.type == InstanceType.VANILLA }
        } else {
            listOf(enhanced) + vanillaOptions
        }
    }

    /**
     * Offline fallback — hardcoded lista gdy Mojang API nie odpowiada.
     */
    fun offlineFallbackVersions(): List<VersionOption> = listOf(
        VersionOption("1.20.1-enhanced", "1.20.1", InstanceType.ENHANCED, "1.20.1 — Enhanced", "wielowątkowość + compat layer"),
        VersionOption("1.20.4-vanilla", "1.20.4", InstanceType.VANILLA, "1.20.4 — Vanilla", "czysty Minecraft"),
        VersionOption("1.20.1-vanilla", "1.20.1", InstanceType.VANILLA, "1.20.1 — Vanilla", "czysty Minecraft"),
        VersionOption("1.19.4-vanilla", "1.19.4", InstanceType.VANILLA, "1.19.4 — Vanilla", "czysty Minecraft"),
        VersionOption("1.18.2-vanilla", "1.18.2", InstanceType.VANILLA, "1.18.2 — Vanilla", "czysty Minecraft"),
        VersionOption("1.16.5-vanilla", "1.16.5", InstanceType.VANILLA, "1.16.5 — Vanilla", "czysty Minecraft"),
        VersionOption("1.12.2-vanilla", "1.12.2", InstanceType.VANILLA, "1.12.2 — Vanilla", "czysty Minecraft"),
        VersionOption("1.8.9-vanilla", "1.8.9", InstanceType.VANILLA, "1.8.9 — Vanilla", "czysty Minecraft")
    )

    fun toInstanceConfig(form: NewInstanceForm): InstanceConfig {
        val version = requireNotNull(form.selectedVersion) { "selectedVersion must not be null at finish" }
        return InstanceConfig(
            name = form.name.trim(),
            minecraftVersion = version.mcVersion,
            type = version.type,
            loader = form.loader,
            ramMb = form.ramMb,
            threads = form.threads
        )
    }
}
