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
}
