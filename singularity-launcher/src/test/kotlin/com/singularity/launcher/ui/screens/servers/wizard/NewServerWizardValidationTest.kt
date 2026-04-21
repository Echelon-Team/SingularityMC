// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.servers.wizard

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NewServerWizardValidationTest {

    @Test
    fun `instanceStepValidate requires non-null selectedInstanceId`() {
        assertFalse(NewServerWizardLogic.instanceStepValidate(NewServerForm(selectedInstanceId = null)))
        assertTrue(NewServerWizardLogic.instanceStepValidate(NewServerForm(selectedInstanceId = "inst-123")))
    }

    @Test
    fun `nameStepValidate requires non-blank name`() {
        assertFalse(NewServerWizardLogic.nameStepValidate(NewServerForm(name = "")))
        assertFalse(NewServerWizardLogic.nameStepValidate(NewServerForm(name = "   ")))
        assertTrue(NewServerWizardLogic.nameStepValidate(NewServerForm(name = "Survival Server")))
    }

    @Test
    fun `nameStepValidate rejects too long name`() {
        assertFalse(NewServerWizardLogic.nameStepValidate(NewServerForm(name = "a".repeat(65))))
        assertTrue(NewServerWizardLogic.nameStepValidate(NewServerForm(name = "a".repeat(64))))
    }

    @Test
    fun `versionStepValidate requires non-blank version`() {
        assertFalse(NewServerWizardLogic.versionStepValidate(NewServerForm(mcVersion = "")))
        assertTrue(NewServerWizardLogic.versionStepValidate(NewServerForm(mcVersion = "1.20.1")))
    }

    @Test
    fun `loaderStepValidate Enhanced is always invalid in Sub 4 (disabled branch)`() {
        val form = NewServerForm(enhancedMode = true)
        assertFalse(
            NewServerWizardLogic.loaderStepValidate(form),
            "Enhanced disabled w Sub 4 — can't proceed"
        )
    }

    @Test
    fun `loaderStepValidate Vanilla is valid`() {
        val form = NewServerForm(enhancedMode = false)
        assertTrue(NewServerWizardLogic.loaderStepValidate(form))
    }

    @Test
    fun `portStepValidate accepts valid port range 1-65535`() {
        assertTrue(NewServerWizardLogic.portStepValidate(
            NewServerForm(port = 25565),
            usedPorts = emptySet()
        ))
        assertTrue(NewServerWizardLogic.portStepValidate(
            NewServerForm(port = 1),
            usedPorts = emptySet()
        ))
        assertTrue(NewServerWizardLogic.portStepValidate(
            NewServerForm(port = 65535),
            usedPorts = emptySet()
        ))
    }

    @Test
    fun `portStepValidate rejects port outside 1-65535`() {
        assertFalse(NewServerWizardLogic.portStepValidate(
            NewServerForm(port = 0),
            usedPorts = emptySet()
        ))
        assertFalse(NewServerWizardLogic.portStepValidate(
            NewServerForm(port = 65536),
            usedPorts = emptySet()
        ))
        assertFalse(NewServerWizardLogic.portStepValidate(
            NewServerForm(port = -1),
            usedPorts = emptySet()
        ))
    }

    @Test
    fun `portStepValidate rejects port conflict`() {
        assertFalse(NewServerWizardLogic.portStepValidate(
            NewServerForm(port = 25565),
            usedPorts = setOf(25565, 25566)
        ))
        assertTrue(NewServerWizardLogic.portStepValidate(
            NewServerForm(port = 25567),
            usedPorts = setOf(25565, 25566)
        ))
    }

    @Test
    fun `portStepValidate also validates RAM and threads`() {
        assertFalse(
            NewServerWizardLogic.portStepValidate(
                NewServerForm(port = 25565, ramMb = 512, threads = 4),
                usedPorts = emptySet()
            ),
            "RAM below 1024 invalid"
        )
        assertFalse(
            NewServerWizardLogic.portStepValidate(
                NewServerForm(port = 25565, ramMb = 4096, threads = 0),
                usedPorts = emptySet()
            ),
            "Threads below 1 invalid"
        )
    }

    @Test
    fun `defaultForm has sensible defaults`() {
        val form = NewServerForm()
        assertNull(form.selectedInstanceId)
        assertEquals("", form.name)
        assertEquals("", form.mcVersion)
        assertFalse(form.enhancedMode)
        assertEquals(25565, form.port)
        assertEquals(4096, form.ramMb)
        assertEquals(4, form.threads)
    }

    @Test
    fun `toServerConfig builds correct config`() {
        val form = NewServerForm(
            selectedInstanceId = "inst-1",
            name = "Test Server",
            mcVersion = "1.20.1",
            port = 25566,
            ramMb = 6144,
            threads = 8,
            motd = "Custom MOTD"
        )
        val config = NewServerWizardLogic.toServerConfig(form)
        assertEquals("Test Server", config.name)
        assertEquals("1.20.1", config.minecraftVersion)
        assertEquals("inst-1", config.parentInstanceId)
        assertEquals(25566, config.port)
        assertEquals(6144, config.ramMb)
        assertEquals(8, config.threads)
        assertEquals("Custom MOTD", config.motd)
    }
}
