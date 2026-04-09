package com.singularity.agent.remapping

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class RemappingEngineTest {

    private lateinit var engine: RemappingEngine
    private lateinit var tree: InheritanceTree

    @BeforeEach
    fun setup() {
        tree = InheritanceTree()

        // Symulujemy hierarchie MC: Entity → LivingEntity → Player
        tree.register("net/minecraft/world/entity/Entity", "java/lang/Object", emptyList())
        tree.register("net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Entity", emptyList())
        tree.register("net/minecraft/world/entity/player/Player", "net/minecraft/world/entity/LivingEntity", emptyList())

        val obfTable = MappingTable(
            namespace = "obf-to-mojmap",
            classes = mapOf("abc" to "net/minecraft/world/entity/Entity"),
            methods = mapOf("net/minecraft/world/entity/Entity/m_5803_()V" to "tick"),
            fields = mapOf("net/minecraft/world/entity/Entity/f_19794_" to "level")
        )

        val srgTable = MappingTable(
            namespace = "srg-to-mojmap",
            classes = emptyMap(),
            methods = mapOf("net/minecraft/world/entity/Entity/m_5803_()V" to "tick"),
            fields = mapOf("net/minecraft/world/entity/Entity/f_19794_" to "level")
        )

        val intermediaryTable = MappingTable(
            namespace = "intermediary-to-mojmap",
            classes = emptyMap(),
            methods = mapOf("net/minecraft/world/entity/Entity/method_5773()V" to "tick"),
            fields = mapOf("net/minecraft/world/entity/Entity/field_6002" to "level")
        )

        engine = RemappingEngine(
            obfToMojmap = obfTable,
            srgToMojmap = srgTable,
            intermediaryToMojmap = intermediaryTable,
            inheritanceTree = tree
        )
    }

    @Test
    fun `resolveClass maps obfuscated class name`() {
        assertEquals("net/minecraft/world/entity/Entity", engine.resolveClass("abc"))
    }

    @Test
    fun `resolveClass returns original for non-mapped class`() {
        assertEquals("com/mymod/MyClass", engine.resolveClass("com/mymod/MyClass"))
    }

    @Test
    fun `resolveMethod with direct match`() {
        assertEquals(
            "tick",
            engine.resolveMethod("net/minecraft/world/entity/Entity", "m_5803_", "()V")
        )
    }

    @Test
    fun `resolveMethod with inheritance fallback — method defined in parent`() {
        // Player nie ma mappingu dla m_5803_, ale Entity (przodek) ma
        assertEquals(
            "tick",
            engine.resolveMethod("net/minecraft/world/entity/player/Player", "m_5803_", "()V")
        )
    }

    @Test
    fun `resolveMethod returns original for unknown method`() {
        assertEquals(
            "unknownMethod",
            engine.resolveMethod("net/minecraft/world/entity/Entity", "unknownMethod", "()V")
        )
    }

    @Test
    fun `resolveField with direct match`() {
        assertEquals(
            "level",
            engine.resolveField("net/minecraft/world/entity/Entity", "f_19794_")
        )
    }

    @Test
    fun `resolveField with inheritance fallback`() {
        assertEquals(
            "level",
            engine.resolveField("net/minecraft/world/entity/player/Player", "f_19794_")
        )
    }

    @Test
    fun `resolveMethod tries all mapping tables`() {
        // Intermediary format: method_5773 — powinno znalezc w intermediaryToMojmap
        assertEquals(
            "tick",
            engine.resolveMethod("net/minecraft/world/entity/Entity", "method_5773", "()V")
        )
    }

    // -------------------------------------------------------------------------
    // Sub 2b Task 0.2: reverseResolveClass — dla SingularityClassLoader reverse lookup
    // -------------------------------------------------------------------------

    @Test
    fun `reverseResolveClass finds original name in obf table`() {
        // Engine setup() ma "abc" → "net/minecraft/world/entity/Entity" w obfTable
        assertEquals("abc", engine.reverseResolveClass("net/minecraft/world/entity/Entity"))
    }

    @Test
    fun `reverseResolveClass searches all tables`() {
        // Specific test: klasa tylko w srgTable
        val localTree = InheritanceTree()
        val localEngine = RemappingEngine(
            MappingTable("obf", emptyMap(), emptyMap(), emptyMap()),
            MappingTable("srg",
                classes = mapOf("net/minecraft/class_1297" to "net/minecraft/world/entity/Entity"),
                methods = emptyMap(),
                fields = emptyMap()
            ),
            MappingTable("intermediary", emptyMap(), emptyMap(), emptyMap()),
            localTree
        )
        assertEquals("net/minecraft/class_1297", localEngine.reverseResolveClass("net/minecraft/world/entity/Entity"))
    }

    @Test
    fun `reverseResolveClass returns null for unmapped mojmap name`() {
        assertNull(engine.reverseResolveClass("com/unknown/Nothing"))
    }
}
