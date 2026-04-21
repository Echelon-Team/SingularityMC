// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.module

import com.singularity.common.contracts.ModuleDescriptorData
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ContractValidatorTest {

    private fun descriptor(requiredContracts: Set<String>) = ModuleDescriptorData(
        moduleId = "test",
        moduleVersion = "1.0.0",
        minecraftVersion = "1.20.1",
        supportedLoaders = setOf("fabric"),
        requiredContracts = requiredContracts,
        entrypoint = "com.singularity.compat.v1_20_1.CompatModule1201"
    )

    @Test
    fun `valid when agent offers all required contracts`() {
        val agentContracts = setOf("metadata", "remapping", "loader_emulation", "bridges", "hooks")
        val moduleDesc = descriptor(setOf("metadata", "remapping"))

        val result = ContractValidator.validate(agentContracts, moduleDesc)
        assertTrue(result.isValid)
        assertTrue(result.missingContracts.isEmpty())
    }

    @Test
    fun `valid when agent offers exact same contracts`() {
        val contracts = setOf("metadata", "remapping")
        val result = ContractValidator.validate(contracts, descriptor(contracts))
        assertTrue(result.isValid)
    }

    @Test
    fun `valid when module requires empty set`() {
        val result = ContractValidator.validate(setOf("metadata"), descriptor(emptySet()))
        assertTrue(result.isValid)
    }

    @Test
    fun `invalid when agent missing required contract`() {
        val agentContracts = setOf("metadata")
        val moduleDesc = descriptor(setOf("metadata", "remapping", "bridges"))

        val result = ContractValidator.validate(agentContracts, moduleDesc)
        assertFalse(result.isValid)
        assertEquals(setOf("remapping", "bridges"), result.missingContracts)
    }

    @Test
    fun `invalid when agent has no contracts`() {
        val result = ContractValidator.validate(emptySet(), descriptor(setOf("metadata")))
        assertFalse(result.isValid)
        assertEquals(setOf("metadata"), result.missingContracts)
    }

    @Test
    fun `error message is human-readable`() {
        val agentContracts = setOf("metadata")
        val moduleDesc = descriptor(setOf("metadata", "remapping"))

        val result = ContractValidator.validate(agentContracts, moduleDesc)
        val msg = result.errorMessage
        assertNotNull(msg)
        assertTrue(msg!!.contains("remapping"))
        assertTrue(msg.contains("test")) // moduleId
    }
}
