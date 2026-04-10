package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class DuplicateDetectorTest {

    private fun mod(id: String, version: String, name: String = id, author: String = "Author") = ModInfo(
        modId = id, version = version, name = name, loaderType = LoaderType.FABRIC,
        dependencies = emptyList(), entryPoints = emptyList(), mixinConfigs = emptyList(),
        authors = listOf(author), description = "", side = ModSide.BOTH,
        jarPath = Paths.get("/mods/$id-$version.jar")
    )

    @Test
    fun `two versions of same mod detected as KeepNewer`() {
        val mods = listOf(
            mod("jei", "15.2.0", "JEI", "mezz"),
            mod("jei", "15.3.0", "JEI", "mezz")
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is DuplicateDetector.DuplicateAction.KeepNewer)
        val action = actions[0] as DuplicateDetector.DuplicateAction.KeepNewer
        assertEquals("15.3.0", action.keep.version)
        assertEquals("15.2.0", action.remove.version)
    }

    @Test
    fun `two different mods with same ID detected as ConflictingIds`() {
        val mods = listOf(
            mod("coolmod", "1.0", "Cool Mod", "Alice"),
            mod("coolmod", "2.0", "Another Cool Mod", "Bob")
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is DuplicateDetector.DuplicateAction.ConflictingIds)
    }

    @Test
    fun `no duplicates returns empty list`() {
        val mods = listOf(mod("mod-a", "1.0"), mod("mod-b", "2.0"), mod("mod-c", "3.0"))
        val actions = DuplicateDetector.detect(mods)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `three versions of same mod — keep newest, remove two`() {
        val mods = listOf(
            mod("sodium", "0.5.6", "Sodium", "JellySquid"),
            mod("sodium", "0.5.8", "Sodium", "JellySquid"),
            mod("sodium", "0.5.7", "Sodium", "JellySquid")
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(2, actions.size) // 2 do usunięcia
        assertTrue(actions.all { it is DuplicateDetector.DuplicateAction.KeepNewer })
        val kept = (actions[0] as DuplicateDetector.DuplicateAction.KeepNewer).keep
        assertEquals("0.5.8", kept.version)
    }

    @Test
    fun `empty list returns empty`() {
        val actions = DuplicateDetector.detect(emptyList())
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `semver sort — 0_5_10 is newer than 0_5_9 (lexicographic bug)`() {
        // THE classic bug: string sort says "0.5.10" < "0.5.9" bo '1' < '9'.
        // Z poprawionym ModVersionComparator powinno wybrać 0.5.10 jako newest.
        val mods = listOf(
            mod("sodium", "0.5.9", "Sodium", "JellySquid"),
            mod("sodium", "0.5.10", "Sodium", "JellySquid")
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        val keepNewer = actions[0] as DuplicateDetector.DuplicateAction.KeepNewer
        assertEquals("0.5.10", keepNewer.keep.version) // newest = 0.5.10
        assertEquals("0.5.9", keepNewer.remove.version)
    }

    @Test
    fun `semver sort — MC-style version with build metadata`() {
        // 0.5.8+mc1.20.1 vs 0.5.7+mc1.20.1 — build metadata ignored, 0.5.8 newer
        val mods = listOf(
            mod("sodium", "0.5.7+mc1.20.1", "Sodium", "JellySquid"),
            mod("sodium", "0.5.8+mc1.20.1", "Sodium", "JellySquid")
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        val keepNewer = actions[0] as DuplicateDetector.DuplicateAction.KeepNewer
        assertEquals("0.5.8+mc1.20.1", keepNewer.keep.version)
    }

    @Test
    fun `empty authors list does not crash ConflictingIds detection`() {
        val modA = ModInfo(
            modId = "shared", version = "1.0", name = "Shared Mod",
            loaderType = com.singularity.common.model.LoaderType.FABRIC,
            dependencies = emptyList(), entryPoints = emptyList(), mixinConfigs = emptyList(),
            authors = emptyList(), description = "",
            side = com.singularity.common.model.ModSide.BOTH,
            jarPath = java.nio.file.Paths.get("/mods/a.jar")
        )
        val modB = modA.copy(
            name = "Different Shared Mod",
            jarPath = java.nio.file.Paths.get("/mods/b.jar")
        )

        // Should not crash even with empty authors
        val actions = DuplicateDetector.detect(listOf(modA, modB))
        // Different names → ConflictingIds
        assertEquals(1, actions.size)
        assertTrue(actions[0] is DuplicateDetector.DuplicateAction.ConflictingIds)
    }

    // --- CrossLoaderSameId (#26 opcja C, Task 15) ---

    private fun modWithLoader(id: String, version: String, name: String, author: String, loader: LoaderType) = ModInfo(
        modId = id, version = version, name = name, loaderType = loader,
        dependencies = emptyList(), entryPoints = emptyList(), mixinConfigs = emptyList(),
        authors = listOf(author), description = "", side = ModSide.BOTH,
        jarPath = Paths.get("/mods/$id-$loader-$version.jar")
    )

    @Test
    fun `Fabric core + Forge core = CrossLoaderSameId (not ConflictingIds)`() {
        val mods = listOf(
            modWithLoader("core", "1.0", "Core Fabric", "Alice", LoaderType.FABRIC),
            modWithLoader("core", "1.0", "Core Forge", "Bob", LoaderType.FORGE)
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is DuplicateDetector.DuplicateAction.CrossLoaderSameId)
        val crossLoader = actions[0] as DuplicateDetector.DuplicateAction.CrossLoaderSameId
        assertEquals(2, crossLoader.mods.size)
    }

    @Test
    fun `Forge core + Forge core = ConflictingIds (same ecosystem)`() {
        val mods = listOf(
            modWithLoader("core", "1.0", "Core A", "Alice", LoaderType.FORGE),
            modWithLoader("core", "1.0", "Core B", "Bob", LoaderType.FORGE)
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is DuplicateDetector.DuplicateAction.ConflictingIds)
    }

    @Test
    fun `MULTI core + Fabric core = ConflictingIds (MULTI visible in both)`() {
        val mods = listOf(
            modWithLoader("core", "1.0", "Core Multi", "Alice", LoaderType.MULTI),
            modWithLoader("core", "1.0", "Core Fabric", "Bob", LoaderType.FABRIC)
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is DuplicateDetector.DuplicateAction.ConflictingIds)
    }

    @Test
    fun `Fabric core + NeoForge core = CrossLoaderSameId`() {
        val mods = listOf(
            modWithLoader("core", "1.0", "Core Fabric", "Alice", LoaderType.FABRIC),
            modWithLoader("core", "1.0", "Core NeoForge", "Charlie", LoaderType.NEOFORGE)
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is DuplicateDetector.DuplicateAction.CrossLoaderSameId)
    }

    @Test
    fun `Forge + NeoForge same modId = ConflictingIds (same ecosystem)`() {
        val mods = listOf(
            modWithLoader("mylib", "1.0", "MyLib Forge", "Dev", LoaderType.FORGE),
            modWithLoader("mylib", "1.0", "MyLib NeoForge", "Dev2", LoaderType.NEOFORGE)
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is DuplicateDetector.DuplicateAction.ConflictingIds)
    }

    @Test
    fun `NeoForge + NeoForge same modId = ConflictingIds (same ecosystem)`() {
        val mods = listOf(
            modWithLoader("mylib", "1.0", "MyLib A", "Dev", LoaderType.NEOFORGE),
            modWithLoader("mylib", "1.0", "MyLib B", "Dev2", LoaderType.NEOFORGE)
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is DuplicateDetector.DuplicateAction.ConflictingIds)
    }

    @Test
    fun `Fabric + Forge + NeoForge same modId = CrossLoaderSameId (3-loader group)`() {
        val mods = listOf(
            modWithLoader("jei", "1.0", "JEI Fabric", "mezz-fabric", LoaderType.FABRIC),
            modWithLoader("jei", "1.0", "JEI Forge", "mezz-forge", LoaderType.FORGE),
            modWithLoader("jei", "1.0", "JEI NeoForge", "mezz-neo", LoaderType.NEOFORGE)
        )

        val actions = DuplicateDetector.detect(mods)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is DuplicateDetector.DuplicateAction.CrossLoaderSameId)
        val crossLoader = actions[0] as DuplicateDetector.DuplicateAction.CrossLoaderSameId
        assertEquals(3, crossLoader.mods.size)
    }
}
