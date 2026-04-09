package com.singularity.agent.registry

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class VisibilityRulesTest {

    private fun mod(id: String, loader: LoaderType) = SingularityModRegistry.RegisteredMod(
        modId = id, version = "1.0", name = id, loaderType = loader, side = ModSide.BOTH
    )

    private val allMods = listOf(
        mod("sodium", LoaderType.FABRIC),
        mod("lithium", LoaderType.FABRIC),
        mod("create", LoaderType.FORGE),
        mod("jei", LoaderType.FORGE),
        mod("ftb", LoaderType.NEOFORGE),
        mod("architectury", LoaderType.MULTI)
    )

    @Test
    fun `filterForFabric returns ONLY Fabric and Multi mods`() {
        val visible = VisibilityRules.filterForFabric(allMods)
        assertEquals(3, visible.size) // sodium, lithium, architectury
        assertTrue(visible.all { it.loaderType == LoaderType.FABRIC || it.loaderType == LoaderType.MULTI })
    }

    @Test
    fun `filterForForge returns ONLY Forge, NeoForge, and Multi mods`() {
        val visible = VisibilityRules.filterForForge(allMods)
        assertEquals(4, visible.size) // create, jei, ftb, architectury
        assertTrue(
            visible.all {
                it.loaderType == LoaderType.FORGE ||
                    it.loaderType == LoaderType.NEOFORGE ||
                    it.loaderType == LoaderType.MULTI
            }
        )
    }

    @Test
    fun `filterForNeoForge returns same as filterForForge on 1_20_1`() {
        val forgeVisible = VisibilityRules.filterForForge(allMods)
        val neoforgeVisible = VisibilityRules.filterForNeoForge(allMods)
        assertEquals(forgeVisible, neoforgeVisible)
    }

    @Test
    fun `Fabric filter does NOT contain Forge mods`() {
        val visible = VisibilityRules.filterForFabric(allMods)
        assertFalse(visible.any { it.modId == "create" })
        assertFalse(visible.any { it.modId == "jei" })
    }

    @Test
    fun `Forge filter does NOT contain Fabric-only mods`() {
        val visible = VisibilityRules.filterForForge(allMods)
        assertFalse(visible.any { it.modId == "sodium" })
        assertFalse(visible.any { it.modId == "lithium" })
    }

    @Test
    fun `Multi-loader mod visible in BOTH filters`() {
        val fabricVisible = VisibilityRules.filterForFabric(allMods)
        val forgeVisible = VisibilityRules.filterForForge(allMods)
        assertTrue(fabricVisible.any { it.modId == "architectury" })
        assertTrue(forgeVisible.any { it.modId == "architectury" })
    }

    @Test
    fun `empty list returns empty for all filters`() {
        assertTrue(VisibilityRules.filterForFabric(emptyList()).isEmpty())
        assertTrue(VisibilityRules.filterForForge(emptyList()).isEmpty())
        assertTrue(VisibilityRules.filterForNeoForge(emptyList()).isEmpty())
    }

    @Test
    fun `Library and Unknown mods not visible in any filter`() {
        val modsWithLibrary = allMods + listOf(
            mod("gson", LoaderType.LIBRARY),
            mod("mystery", LoaderType.UNKNOWN)
        )
        val fabricVisible = VisibilityRules.filterForFabric(modsWithLibrary)
        val forgeVisible = VisibilityRules.filterForForge(modsWithLibrary)

        assertFalse(fabricVisible.any { it.modId == "gson" })
        assertFalse(fabricVisible.any { it.modId == "mystery" })
        assertFalse(forgeVisible.any { it.modId == "gson" })
        assertFalse(forgeVisible.any { it.modId == "mystery" })
    }
}
