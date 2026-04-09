package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class DependencyResolverTest {

    private fun mod(id: String, deps: List<ModDependency> = emptyList()) = ModInfo(
        modId = id, version = "1.0", name = id, loaderType = LoaderType.FABRIC,
        dependencies = deps, entryPoints = emptyList(), mixinConfigs = emptyList(),
        authors = emptyList(), description = "", side = ModSide.BOTH,
        jarPath = Paths.get("/mods/$id.jar")
    )

    private fun dep(modId: String, required: Boolean = true) = ModDependency(modId, null, required)

    @Test
    fun `linear dependency chain sorted correctly`() {
        val mods = listOf(
            mod("A", listOf(dep("B"))),
            mod("B", listOf(dep("C"))),
            mod("C")
        )

        val result = DependencyResolver.resolve(mods)
        assertTrue(result.errors.isEmpty())

        val order = result.sortedMods.map { it.modId }
        assertTrue(order.indexOf("C") < order.indexOf("B"), "C before B")
        assertTrue(order.indexOf("B") < order.indexOf("A"), "B before A")
    }

    @Test
    fun `diamond dependency sorted correctly`() {
        // A depends on B and C, both depend on D
        val mods = listOf(
            mod("A", listOf(dep("B"), dep("C"))),
            mod("B", listOf(dep("D"))),
            mod("C", listOf(dep("D"))),
            mod("D")
        )

        val result = DependencyResolver.resolve(mods)
        assertTrue(result.errors.isEmpty())

        val order = result.sortedMods.map { it.modId }
        assertTrue(order.indexOf("D") < order.indexOf("B"))
        assertTrue(order.indexOf("D") < order.indexOf("C"))
        assertTrue(order.indexOf("B") < order.indexOf("A"))
        assertTrue(order.indexOf("C") < order.indexOf("A"))
    }

    @Test
    fun `missing required dependency produces error`() {
        val mods = listOf(
            mod("A", listOf(dep("nonexistent-mod", required = true)))
        )

        val result = DependencyResolver.resolve(mods)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0] is DependencyResolver.DependencyError.MissingRequired)
        val err = result.errors[0] as DependencyResolver.DependencyError.MissingRequired
        assertEquals("A", err.requiredBy)
        assertEquals("nonexistent-mod", err.missingModId)
    }

    @Test
    fun `missing optional dependency is NOT an error`() {
        val mods = listOf(
            mod("A", listOf(dep("optional-mod", required = false)))
        )

        val result = DependencyResolver.resolve(mods)
        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.sortedMods.size)
    }

    @Test
    fun `cyclic dependency produces error`() {
        val mods = listOf(
            mod("A", listOf(dep("B"))),
            mod("B", listOf(dep("A")))
        )

        val result = DependencyResolver.resolve(mods)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0] is DependencyResolver.DependencyError.CyclicDependency)
    }

    @Test
    fun `empty mod list resolves to empty`() {
        val result = DependencyResolver.resolve(emptyList())
        assertTrue(result.sortedMods.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `no dependencies — all mods in result`() {
        val mods = listOf(mod("A"), mod("B"), mod("C"))

        val result = DependencyResolver.resolve(mods)
        assertEquals(3, result.sortedMods.size)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `system dependencies (minecraft, java, fabricloader, forge) are ignored`() {
        val mods = listOf(
            mod("mymod", listOf(
                dep("minecraft"),
                dep("java"),
                dep("fabricloader"),
                dep("forge")
            ))
        )

        val result = DependencyResolver.resolve(mods)
        assertTrue(result.errors.isEmpty()) // Nie raportuj brakujących system deps
        assertEquals(1, result.sortedMods.size)
    }

    @Test
    fun `three-node cycle detected`() {
        val mods = listOf(
            mod("A", listOf(dep("B"))),
            mod("B", listOf(dep("C"))),
            mod("C", listOf(dep("A")))
        )

        val result = DependencyResolver.resolve(mods)
        assertTrue(result.errors.any { it is DependencyResolver.DependencyError.CyclicDependency })
    }

    @Test
    fun `self-dependency detected as cycle`() {
        val mods = listOf(
            mod("self-ref", listOf(dep("self-ref")))
        )

        val result = DependencyResolver.resolve(mods)
        assertTrue(
            result.errors.any { it is DependencyResolver.DependencyError.CyclicDependency },
            "Self-dependency should be detected as cycle"
        )
    }

    @Test
    fun `cyclic dependency error contains the actual cycle mod IDs`() {
        // Bez tej weryfikacji error jest useless dla usera.
        val mods = listOf(
            mod("A", listOf(dep("B"))),
            mod("B", listOf(dep("C"))),
            mod("C", listOf(dep("A")))
        )

        val result = DependencyResolver.resolve(mods)
        val cycleError = result.errors.filterIsInstance<DependencyResolver.DependencyError.CyclicDependency>()
            .firstOrNull()
        assertNotNull(cycleError, "Should have cycle error")
        assertEquals(setOf("A", "B", "C"), cycleError!!.involvedMods.toSet())
    }

    @Test
    fun `diamond graph with cycle inside detected`() {
        // A -> B, A -> C, B -> D, C -> D (diamond, no cycle)
        // Plus D -> A (introduces cycle through A->B->D->A and A->C->D->A)
        val mods = listOf(
            mod("A", listOf(dep("B"), dep("C"))),
            mod("B", listOf(dep("D"))),
            mod("C", listOf(dep("D"))),
            mod("D", listOf(dep("A")))
        )

        val result = DependencyResolver.resolve(mods)
        val cycleError = result.errors.filterIsInstance<DependencyResolver.DependencyError.CyclicDependency>()
            .firstOrNull()
        assertNotNull(cycleError, "Diamond with back-edge should be detected as cycle")
    }

    @Test
    fun `large graph performs without recursion issues`() {
        // 200 mods, linear chain — verifies no stack overflow
        val mods = (0 until 200).map { i ->
            val deps = if (i > 0) listOf(dep("mod-${i - 1}")) else emptyList()
            mod("mod-$i", deps)
        }

        val result = DependencyResolver.resolve(mods)
        assertTrue(result.errors.isEmpty())
        assertEquals(200, result.sortedMods.size)
        // mod-0 should be first, mod-199 last
        assertEquals("mod-0", result.sortedMods.first().modId)
        assertEquals("mod-199", result.sortedMods.last().modId)
    }
}
