package com.singularity.agent.remapping

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class InheritanceTreeTest {

    @Test
    fun `register class with parent and interfaces`() {
        val tree = InheritanceTree()
        tree.register("net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Entity", listOf("net/minecraft/world/Attackable"))

        assertEquals("net/minecraft/world/entity/Entity", tree.getParent("net/minecraft/world/entity/LivingEntity"))
        assertEquals(listOf("net/minecraft/world/Attackable"), tree.getInterfaces("net/minecraft/world/entity/LivingEntity"))
    }

    @Test
    fun `getParent returns null for unregistered class`() {
        val tree = InheritanceTree()
        assertNull(tree.getParent("unknown/Class"))
    }

    @Test
    fun `getParent returns java slash lang slash Object for root class`() {
        val tree = InheritanceTree()
        tree.register("net/minecraft/world/entity/Entity", "java/lang/Object", emptyList())

        assertEquals("java/lang/Object", tree.getParent("net/minecraft/world/entity/Entity"))
    }

    @Test
    fun `getAncestors returns full chain to Object`() {
        val tree = InheritanceTree()
        tree.register("net/minecraft/world/entity/Entity", "java/lang/Object", emptyList())
        tree.register("net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Entity", emptyList())
        tree.register("net/minecraft/world/entity/player/Player", "net/minecraft/world/entity/LivingEntity", emptyList())

        val ancestors = tree.getAncestors("net/minecraft/world/entity/player/Player")
        assertEquals(
            listOf(
                "net/minecraft/world/entity/LivingEntity",
                "net/minecraft/world/entity/Entity",
                "java/lang/Object"
            ),
            ancestors
        )
    }

    @Test
    fun `getAncestors returns empty for unregistered class`() {
        val tree = InheritanceTree()
        assertEquals(emptyList<String>(), tree.getAncestors("unknown/Class"))
    }

    @Test
    fun `getAncestors returns empty for root registered without parent`() {
        val tree = InheritanceTree()
        tree.register("java/lang/Object", null, emptyList())
        assertEquals(emptyList<String>(), tree.getAncestors("java/lang/Object"))
    }

    @Test
    fun `size returns number of registered classes`() {
        val tree = InheritanceTree()
        assertEquals(0, tree.size)

        tree.register("A", "B", emptyList())
        assertEquals(1, tree.size)

        tree.register("B", "java/lang/Object", emptyList())
        assertEquals(2, tree.size)
    }

    @Test
    fun `isRegistered returns true for known class`() {
        val tree = InheritanceTree()
        tree.register("TestClass", "java/lang/Object", emptyList())

        assertTrue(tree.isRegistered("TestClass"))
        assertFalse(tree.isRegistered("Unknown"))
    }

    @Test
    fun `concurrent registration is thread-safe`() {
        val tree = InheritanceTree()
        val threads = (0 until 100).map { i ->
            Thread {
                tree.register("class/$i", "java/lang/Object", emptyList())
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(100, tree.size)
    }
}
