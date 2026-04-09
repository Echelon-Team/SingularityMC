package com.singularity.agent.pipeline

import com.singularity.agent.remapping.InheritanceTree
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ParallelTreeTransformerTest {

    @Test
    fun `transforms all classes in parent-before-child order`() {
        val tree = InheritanceTree()
        tree.register("Entity", "java/lang/Object", emptyList())
        tree.register("LivingEntity", "Entity", emptyList())
        tree.register("Player", "LivingEntity", emptyList())
        tree.register("Zombie", "LivingEntity", emptyList())
        tree.register("Block", "java/lang/Object", emptyList())

        val transformOrder = ConcurrentHashMap<String, Int>()
        val counter = AtomicInteger(0)

        ParallelTreeTransformer.transformAll(
            inheritanceTree = tree,
            rootClasses = listOf("Entity", "Block"),
            transform = { className ->
                transformOrder[className] = counter.getAndIncrement()
            }
        )

        // Wszystkie 5 klas transformed
        assertEquals(5, transformOrder.size)
        assertTrue(transformOrder.containsKey("Entity"))
        assertTrue(transformOrder.containsKey("LivingEntity"))
        assertTrue(transformOrder.containsKey("Player"))
        assertTrue(transformOrder.containsKey("Zombie"))
        assertTrue(transformOrder.containsKey("Block"))

        // Parent przed child w ramach drzewa
        assertTrue(transformOrder["Entity"]!! < transformOrder["LivingEntity"]!!)
        assertTrue(transformOrder["LivingEntity"]!! < transformOrder["Player"]!!)
        assertTrue(transformOrder["LivingEntity"]!! < transformOrder["Zombie"]!!)
    }

    @Test
    fun `handles empty tree without throwing`() {
        val tree = InheritanceTree()
        ParallelTreeTransformer.transformAll(
            inheritanceTree = tree,
            rootClasses = emptyList(),
            transform = { fail("Should not be called on empty tree") }
        )
    }

    @Test
    fun `handles single class`() {
        val tree = InheritanceTree()
        tree.register("Lonely", "java/lang/Object", emptyList())

        var transformed = false
        ParallelTreeTransformer.transformAll(
            inheritanceTree = tree,
            rootClasses = listOf("Lonely"),
            transform = { transformed = true }
        )
        assertTrue(transformed)
    }

    @Test
    fun `deep inheritance chain is transformed in correct order`() {
        val tree = InheritanceTree()
        tree.register("A", "java/lang/Object", emptyList())
        tree.register("B", "A", emptyList())
        tree.register("C", "B", emptyList())
        tree.register("D", "C", emptyList())
        tree.register("E", "D", emptyList())

        val order = ConcurrentHashMap<String, Int>()
        val counter = AtomicInteger(0)

        ParallelTreeTransformer.transformAll(
            inheritanceTree = tree,
            rootClasses = listOf("A"),
            transform = { order[it] = counter.getAndIncrement() }
        )

        assertEquals(5, order.size)
        for ((parent, child) in listOf("A" to "B", "B" to "C", "C" to "D", "D" to "E")) {
            assertTrue(order[parent]!! < order[child]!!, "$parent must be before $child")
        }
    }

    @Test
    fun `exception in transform propagates (no silent swallow)`() {
        val tree = InheritanceTree()
        tree.register("FailClass", "java/lang/Object", emptyList())

        assertThrows(RuntimeException::class.java) {
            ParallelTreeTransformer.transformAll(
                inheritanceTree = tree,
                rootClasses = listOf("FailClass"),
                transform = { throw RuntimeException("intentional") }
            )
        }
    }
}
