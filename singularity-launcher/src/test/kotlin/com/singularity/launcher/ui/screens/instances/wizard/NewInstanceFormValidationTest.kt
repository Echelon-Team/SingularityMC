package com.singularity.launcher.ui.screens.instances.wizard

import com.singularity.common.model.InstanceType
import com.singularity.common.model.LoaderType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NewInstanceFormValidationTest {

    @Test
    fun `empty form has default values`() {
        val form = NewInstanceForm()
        assertEquals("", form.name)
        assertNull(form.selectedVersion)
        assertEquals(LoaderType.NONE, form.loader)
        assertEquals(4096, form.ramMb)
        assertEquals(4, form.threads)
    }

    @Test
    fun `nameStepValidate requires non-blank name`() {
        assertFalse(NewInstanceWizardLogic.nameStepValidate(NewInstanceForm(name = "")))
        assertFalse(NewInstanceWizardLogic.nameStepValidate(NewInstanceForm(name = "   ")))
        assertTrue(NewInstanceWizardLogic.nameStepValidate(NewInstanceForm(name = "Survival")))
    }

    @Test
    fun `nameStepValidate rejects too long name (max 64 chars)`() {
        val longName = "a".repeat(65)
        assertFalse(NewInstanceWizardLogic.nameStepValidate(NewInstanceForm(name = longName)))

        val exactly64 = "a".repeat(64)
        assertTrue(NewInstanceWizardLogic.nameStepValidate(NewInstanceForm(name = exactly64)))
    }

    @Test
    fun `versionStepValidate requires selected version`() {
        assertFalse(NewInstanceWizardLogic.versionStepValidate(NewInstanceForm(selectedVersion = null)))
        val v = VersionOption(
            id = "1.20.1-enhanced",
            mcVersion = "1.20.1",
            type = InstanceType.ENHANCED,
            label = "1.20.1 — Enhanced",
            subtitle = "wielowątkowość"
        )
        assertTrue(NewInstanceWizardLogic.versionStepValidate(NewInstanceForm(selectedVersion = v)))
    }

    @Test
    fun `loaderStepValidate Enhanced is always valid`() {
        val v = VersionOption(
            id = "1.20.1-enhanced", mcVersion = "1.20.1", type = InstanceType.ENHANCED,
            label = "1.20.1 — Enhanced", subtitle = ""
        )
        val form = NewInstanceForm(selectedVersion = v, loader = LoaderType.NONE)
        assertTrue(NewInstanceWizardLogic.loaderStepValidate(form))
    }

    @Test
    fun `loaderStepValidate Vanilla allows NONE (vanilla instance)`() {
        val v = VersionOption("1.20.1-vanilla", "1.20.1", InstanceType.VANILLA, "1.20.1 — Vanilla", "")
        assertTrue(NewInstanceWizardLogic.loaderStepValidate(NewInstanceForm(selectedVersion = v, loader = LoaderType.NONE)))
    }

    @Test
    fun `loaderStepValidate Vanilla accepts Fabric`() {
        val v = VersionOption("1.20.1-vanilla", "1.20.1", InstanceType.VANILLA, "1.20.1 — Vanilla", "")
        assertTrue(NewInstanceWizardLogic.loaderStepValidate(NewInstanceForm(selectedVersion = v, loader = LoaderType.FABRIC)))
    }

    @Test
    fun `resourcesStepValidate ram range 1024 to totalRam`() {
        // maxThreads jawnie — bez tego default = HardwareInfo.maxAssignableThreads
        // (totalCores - 2), co na 4-CPU GH runnerze = 2 i threads=4 leci w false
        // od strony threadsOk, rozwalając ten RAM-focused test.
        assertFalse(NewInstanceWizardLogic.resourcesStepValidate(NewInstanceForm(ramMb = 512, threads = 4), totalRam = 16384, maxThreads = 8))
        assertTrue(NewInstanceWizardLogic.resourcesStepValidate(NewInstanceForm(ramMb = 1024, threads = 4), totalRam = 16384, maxThreads = 8))
        assertTrue(NewInstanceWizardLogic.resourcesStepValidate(NewInstanceForm(ramMb = 16384, threads = 4), totalRam = 16384, maxThreads = 8))
        assertFalse(NewInstanceWizardLogic.resourcesStepValidate(NewInstanceForm(ramMb = 20000, threads = 4), totalRam = 16384, maxThreads = 8))
    }

    @Test
    fun `resourcesStepValidate threads range 2 to maxThreads`() {
        assertFalse(NewInstanceWizardLogic.resourcesStepValidate(NewInstanceForm(ramMb = 4096, threads = 1), totalRam = 16384, maxThreads = 8))
        assertTrue(NewInstanceWizardLogic.resourcesStepValidate(NewInstanceForm(ramMb = 4096, threads = 2), totalRam = 16384, maxThreads = 8))
        assertTrue(NewInstanceWizardLogic.resourcesStepValidate(NewInstanceForm(ramMb = 4096, threads = 8), totalRam = 16384, maxThreads = 8))
        assertFalse(NewInstanceWizardLogic.resourcesStepValidate(NewInstanceForm(ramMb = 4096, threads = 16), totalRam = 16384, maxThreads = 8))
    }

    @Test
    fun `offlineFallbackVersions returns non-empty list`() {
        val versions = NewInstanceWizardLogic.offlineFallbackVersions()
        assertTrue(versions.isNotEmpty(), "Sub 4 has hardcoded version list")
        assertTrue(versions.any { it.type == InstanceType.ENHANCED })
        assertTrue(versions.any { it.type == InstanceType.VANILLA })
    }

    @Test
    fun `offlineFallbackVersions Enhanced first`() {
        val versions = NewInstanceWizardLogic.offlineFallbackVersions()
        assertEquals(InstanceType.ENHANCED, versions.first().type, "Enhanced first")
    }

    @Test
    fun `toInstanceConfig builds correct config from valid form`() {
        val v = VersionOption(
            id = "1.20.1-enhanced",
            mcVersion = "1.20.1",
            type = InstanceType.ENHANCED,
            label = "1.20.1 — Enhanced",
            subtitle = ""
        )
        val form = NewInstanceForm(
            name = "My Instance",
            selectedVersion = v,
            loader = LoaderType.NONE,
            ramMb = 6144,
            threads = 8
        )
        val config = NewInstanceWizardLogic.toInstanceConfig(form)
        assertEquals("My Instance", config.name)
        assertEquals("1.20.1", config.minecraftVersion)
        assertEquals(InstanceType.ENHANCED, config.type)
        assertEquals(LoaderType.NONE, config.loader)
        assertEquals(6144, config.ramMb)
        assertEquals(8, config.threads)
    }

    @Test
    fun `toInstanceConfig for vanilla with fabric loader`() {
        val v = VersionOption("1.20.1-vanilla", "1.20.1", InstanceType.VANILLA, "", "")
        val form = NewInstanceForm(
            name = "Vanilla Fabric",
            selectedVersion = v,
            loader = LoaderType.FABRIC
        )
        val config = NewInstanceWizardLogic.toInstanceConfig(form)
        assertEquals(LoaderType.FABRIC, config.loader)
        assertEquals(InstanceType.VANILLA, config.type)
    }
}
