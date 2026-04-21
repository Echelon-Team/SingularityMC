// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.remapping

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MappingTableTest {

    @Test
    fun `lookup returns mapped name for known class`() {
        val table = MappingTable(
            namespace = "obf-to-mojmap",
            classes = mapOf("a" to "net/minecraft/world/level/Level"),
            methods = mapOf("a/b()V" to "tick"),
            fields = mapOf("a/c" to "isClientSide")
        )

        assertEquals("net/minecraft/world/level/Level", table.mapClass("a"))
    }

    @Test
    fun `lookup returns original name for unknown class`() {
        val table = MappingTable(
            namespace = "obf-to-mojmap",
            classes = mapOf("a" to "net/minecraft/world/level/Level"),
            methods = emptyMap(),
            fields = emptyMap()
        )

        assertEquals("unknown/Class", table.mapClass("unknown/Class"))
    }

    @Test
    fun `method lookup with owner and descriptor`() {
        val table = MappingTable(
            namespace = "srg-to-mojmap",
            classes = emptyMap(),
            methods = mapOf(
                "net/minecraft/world/level/Level/m_46748_(Lnet/minecraft/core/BlockPos;)Z" to "hasChunkAt"
            ),
            fields = emptyMap()
        )

        assertEquals(
            "hasChunkAt",
            table.mapMethod("net/minecraft/world/level/Level", "m_46748_", "(Lnet/minecraft/core/BlockPos;)Z")
        )
    }

    @Test
    fun `method lookup returns original for unknown method`() {
        val table = MappingTable(
            namespace = "srg-to-mojmap",
            classes = emptyMap(),
            methods = emptyMap(),
            fields = emptyMap()
        )

        assertEquals("unknownMethod", table.mapMethod("SomeClass", "unknownMethod", "()V"))
    }

    @Test
    fun `field lookup with owner`() {
        val table = MappingTable(
            namespace = "intermediary-to-mojmap",
            classes = emptyMap(),
            methods = emptyMap(),
            fields = mapOf("net/minecraft/class_1937/field_9236" to "isClientSide")
        )

        assertEquals("isClientSide", table.mapField("net/minecraft/class_1937", "field_9236"))
    }

    @Test
    fun `field lookup returns original for unknown field`() {
        val table = MappingTable(
            namespace = "test",
            classes = emptyMap(),
            methods = emptyMap(),
            fields = emptyMap()
        )

        assertEquals("unknownField", table.mapField("Owner", "unknownField"))
    }

    @Test
    fun `size returns total entries`() {
        val table = MappingTable(
            namespace = "test",
            classes = mapOf("a" to "A", "b" to "B"),
            methods = mapOf("c" to "C"),
            fields = mapOf("d" to "D", "e" to "E", "f" to "F")
        )

        assertEquals(6, table.size)
    }

    @Test
    fun `empty table has zero size`() {
        val table = MappingTable("empty", emptyMap(), emptyMap(), emptyMap())
        assertEquals(0, table.size)
    }

    // -------------------------------------------------------------------------
    // Sub 2b Task 0.2: reverse index tests (lookupMethodByName, lookupFieldByName,
    // lookupOriginalClass) — wymagane przez ReflectionInterceptor i SingularityClassLoader.
    // -------------------------------------------------------------------------

    @Test
    fun `lookupMethodByName returns all full keys matching simple name`() {
        val table = MappingTable(
            namespace = "test",
            classes = emptyMap(),
            methods = mapOf(
                "net/minecraft/Entity/m_5803_()V" to "tick",
                "net/minecraft/LivingEntity/m_5803_()V" to "tick",
                "net/minecraft/Other/m_9999_()I" to "getAge"
            ),
            fields = emptyMap()
        )
        val results = table.lookupMethodByName("m_5803_")
        assertEquals(2, results.size)
        assertTrue(results.any { it == "net/minecraft/Entity/m_5803_()V" })
        assertTrue(results.any { it == "net/minecraft/LivingEntity/m_5803_()V" })
    }

    @Test
    fun `lookupMethodByName returns empty for unknown name`() {
        val table = MappingTable("test", emptyMap(), emptyMap(), emptyMap())
        assertTrue(table.lookupMethodByName("nonexistent").isEmpty())
    }

    @Test
    fun `lookupFieldByName returns all full keys matching simple name`() {
        val table = MappingTable(
            namespace = "test",
            classes = emptyMap(),
            methods = emptyMap(),
            fields = mapOf(
                "net/minecraft/Entity/f_19794_" to "level",
                "net/minecraft/LivingEntity/f_19794_" to "level"
            )
        )
        val results = table.lookupFieldByName("f_19794_")
        assertEquals(2, results.size)
    }

    @Test
    fun `lookupOriginalClass returns original name from mojmap`() {
        val table = MappingTable(
            namespace = "test",
            classes = mapOf(
                "obf/Original" to "net/minecraft/world/entity/Entity",
                "obf/Other" to "net/minecraft/world/level/Level"
            ),
            methods = emptyMap(),
            fields = emptyMap()
        )
        assertEquals("obf/Original", table.lookupOriginalClass("net/minecraft/world/entity/Entity"))
        assertEquals("obf/Other", table.lookupOriginalClass("net/minecraft/world/level/Level"))
        assertNull(table.lookupOriginalClass("unknown/Class"))
    }

    @Test
    fun `reverse indexes are built at construction time`() {
        // Verify ze reverse index jest built raz w init, nie lazy
        val table = MappingTable(
            namespace = "test",
            classes = mapOf("obf/A" to "net/A"),
            methods = mapOf("net/A/m_1_()V" to "tick"),
            fields = mapOf("net/A/f_1_" to "x")
        )
        // Multiple lookups → ten sam wynik (brak side effects)
        assertEquals(1, table.lookupMethodByName("m_1_").size)
        assertEquals(1, table.lookupMethodByName("m_1_").size)
        assertEquals("obf/A", table.lookupOriginalClass("net/A"))
    }
}
