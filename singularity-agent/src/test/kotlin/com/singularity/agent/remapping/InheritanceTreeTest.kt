// Copyright (c) 2026 Echelon Team. All rights reserved.

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

    // -------------------------------------------------------------------------
    // Sub 2b Task 0.3: getChildren + getAllRegisteredClasses + cycle detection
    // -------------------------------------------------------------------------

    @Test
    fun `getChildren returns direct children only`() {
        val tree = InheritanceTree()
        tree.register("Entity", "java/lang/Object", emptyList())
        tree.register("LivingEntity", "Entity", emptyList())
        tree.register("Player", "LivingEntity", emptyList())
        tree.register("Zombie", "LivingEntity", emptyList())

        val entityChildren = tree.getChildren("Entity")
        assertEquals(1, entityChildren.size)
        assertTrue("LivingEntity" in entityChildren)

        val livingEntityChildren = tree.getChildren("LivingEntity")
        assertEquals(2, livingEntityChildren.size)
        assertTrue("Player" in livingEntityChildren)
        assertTrue("Zombie" in livingEntityChildren)
    }

    @Test
    fun `getChildren returns empty for class with no children`() {
        val tree = InheritanceTree()
        tree.register("Leaf", "java/lang/Object", emptyList())
        assertTrue(tree.getChildren("Leaf").isEmpty())
    }

    @Test
    fun `getChildren returns empty for unregistered class`() {
        val tree = InheritanceTree()
        assertTrue(tree.getChildren("Unknown").isEmpty())
    }

    @Test
    fun `getAllRegisteredClasses returns all registered class names`() {
        val tree = InheritanceTree()
        tree.register("A", "java/lang/Object", emptyList())
        tree.register("B", "A", emptyList())
        tree.register("C", "B", emptyList())

        val all = tree.getAllRegisteredClasses()
        assertEquals(3, all.size)
        assertTrue("A" in all)
        assertTrue("B" in all)
        assertTrue("C" in all)
    }

    @Test
    fun `getAllRegisteredClasses returns empty set for empty tree`() {
        val tree = InheritanceTree()
        assertTrue(tree.getAllRegisteredClasses().isEmpty())
    }

    @Test
    fun `getAncestors detects cycles and stops walk`() {
        val tree = InheritanceTree()
        // Artificial cycle: A → B → A (nieprawidlowy DAG — ale mozliwy bug w modul)
        tree.register("A", "B", emptyList())
        tree.register("B", "A", emptyList())

        // Musi nie wpasc w nieskonczona petle
        val ancestors = tree.getAncestors("A")
        // Walk: A → getParent=B (add B to visited + ancestors), B → getParent=A (already
        // in visited, break). Result: ["B"]. Sanity: < 10 iteracji.
        assertTrue(ancestors.size < 10, "Walk must terminate on cycle, got ${ancestors.size} ancestors")
    }

    @Test
    fun `concurrent registration of same class with different parents is safe`() {
        // Flag #7 z test-quality review — prawdziwy race test, nie fake distinct-keys test.
        val tree = InheritanceTree()
        val threadCount = 50
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
        val latch = java.util.concurrent.CountDownLatch(threadCount)

        try {
            for (i in 0 until threadCount) {
                executor.submit {
                    try {
                        tree.register("contested/Class", "parent/$i", emptyList())
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            executor.shutdown()
        }

        // Final state: jeden z 50 parentow musi byc zarejestrowany (no corruption)
        val parent = tree.getParent("contested/Class")
        assertNotNull(parent, "Class must be registered")
        assertTrue(parent!!.startsWith("parent/"), "Parent must be one of the 50 registered values")
    }
}
