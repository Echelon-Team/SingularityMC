// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.common.contracts

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CompatModuleTest {

    @Test
    fun `CompatModule interface is implementable with stub`() {
        val stub = object : CompatModule {
            override val moduleId = "compat-1.20.1"
            override val moduleVersion = "1.0.0"
            override val minecraftVersion = "1.20.1"
            override val supportedLoaders = setOf("fabric", "forge", "neoforge")
            override val requiredContracts = setOf("metadata", "remapping", "loader_emulation", "bridges", "hooks")

            override fun initialize() {
                // no-op w stubie
            }

            override fun getMappingTables(): Map<String, Map<String, String>> {
                return mapOf(
                    "obf-to-mojmap" to mapOf("a" to "net.minecraft.world.level.Level"),
                    "srg-to-mojmap" to mapOf("m_12345_" to "someMethod"),
                    "intermediary-to-mojmap" to mapOf("method_9876" to "anotherMethod")
                )
            }
        }

        assertEquals("compat-1.20.1", stub.moduleId)
        assertEquals("1.0.0", stub.moduleVersion)
        assertEquals("1.20.1", stub.minecraftVersion)
        assertEquals(3, stub.supportedLoaders.size)
        assertTrue(stub.supportedLoaders.contains("fabric"))
        assertTrue(stub.supportedLoaders.contains("forge"))
        assertTrue(stub.supportedLoaders.contains("neoforge"))
        assertEquals(5, stub.requiredContracts.size)
        assertEquals(3, stub.getMappingTables().size)
    }

    @Test
    fun `CompatModule mapping tables contain expected namespaces`() {
        val stub = object : CompatModule {
            override val moduleId = "test"
            override val moduleVersion = "0.1.0"
            override val minecraftVersion = "1.20.1"
            override val supportedLoaders = setOf("fabric")
            override val requiredContracts = setOf("metadata")

            override fun initialize() {}

            override fun getMappingTables(): Map<String, Map<String, String>> {
                return mapOf(
                    "obf-to-mojmap" to emptyMap(),
                    "srg-to-mojmap" to emptyMap(),
                    "intermediary-to-mojmap" to emptyMap()
                )
            }
        }

        val tables = stub.getMappingTables()
        assertTrue(tables.containsKey("obf-to-mojmap"))
        assertTrue(tables.containsKey("srg-to-mojmap"))
        assertTrue(tables.containsKey("intermediary-to-mojmap"))
    }
}
