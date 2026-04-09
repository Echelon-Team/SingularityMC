package com.singularity.agent.registry

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class SingularityModRegistryTest {

    private lateinit var registry: SingularityModRegistry

    @BeforeEach
    fun setup() {
        registry = SingularityModRegistry()
    }

    private fun entry(id: String, loader: LoaderType) = SingularityModRegistry.RegisteredMod(
        modId = id, version = "1.0", name = id, loaderType = loader, side = ModSide.BOTH
    )

    @Test
    fun `register and retrieve mod by id`() {
        registry.register(entry("sodium", LoaderType.FABRIC))
        val mod = registry.getById("sodium")
        assertNotNull(mod)
        assertEquals("sodium", mod!!.modId)
        assertEquals(LoaderType.FABRIC, mod.loaderType)
    }

    @Test
    fun `getById returns null for unregistered mod`() {
        assertNull(registry.getById("nonexistent"))
    }

    @Test
    fun `isLoaded returns true for registered, false for unregistered`() {
        registry.register(entry("create", LoaderType.FORGE))
        assertTrue(registry.isLoaded("create"))
        assertFalse(registry.isLoaded("sodium"))
    }

    @Test
    fun `getAll returns all registered mods`() {
        registry.register(entry("sodium", LoaderType.FABRIC))
        registry.register(entry("create", LoaderType.FORGE))
        registry.register(entry("ftb-lib", LoaderType.NEOFORGE))

        val all = registry.getAll()
        assertEquals(3, all.size)
    }

    @Test
    fun `getByLoader filters correctly`() {
        registry.register(entry("sodium", LoaderType.FABRIC))
        registry.register(entry("lithium", LoaderType.FABRIC))
        registry.register(entry("create", LoaderType.FORGE))
        registry.register(entry("jei", LoaderType.FORGE))
        registry.register(entry("ftb", LoaderType.NEOFORGE))

        assertEquals(2, registry.getByLoader(LoaderType.FABRIC).size)
        assertEquals(2, registry.getByLoader(LoaderType.FORGE).size)
        assertEquals(1, registry.getByLoader(LoaderType.NEOFORGE).size)
        assertEquals(0, registry.getByLoader(LoaderType.LIBRARY).size)
    }

    @Test
    fun `concurrent registration is thread-safe`() {
        val threads = (0 until 100).map { i ->
            Thread {
                registry.register(entry("mod-$i", LoaderType.FABRIC))
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(100, registry.getAll().size)
    }

    @Test
    fun `duplicate registration overwrites silently`() {
        registry.register(
            SingularityModRegistry.RegisteredMod(
                modId = "test", version = "1.0", name = "Test Old",
                loaderType = LoaderType.FABRIC, side = ModSide.BOTH
            )
        )
        registry.register(
            SingularityModRegistry.RegisteredMod(
                modId = "test", version = "2.0", name = "Test New",
                loaderType = LoaderType.FABRIC, side = ModSide.BOTH
            )
        )

        val mod = registry.getById("test")!!
        assertEquals("2.0", mod.version)
        assertEquals("Test New", mod.name)
        assertEquals(1, registry.getAll().size) // Nie duplikuje
    }

    @Test
    fun `size returns count of registered mods`() {
        assertEquals(0, registry.size)
        registry.register(entry("a", LoaderType.FABRIC))
        assertEquals(1, registry.size)
        registry.register(entry("b", LoaderType.FORGE))
        assertEquals(2, registry.size)
    }

    @Test
    fun `concurrent same-key registration does not corrupt state`() {
        // Real race: 50 wątków rejestruje TEN SAM modId z różnymi wersjami.
        // ConcurrentHashMap last-write-wins, ale state powinien pozostać consistent:
        // size == 1, wartość to jedna z rejestracji (nie corrupted).
        val threads = (0 until 50).map { i ->
            Thread {
                registry.register(
                    SingularityModRegistry.RegisteredMod(
                        modId = "shared-mod",
                        version = "$i.0",
                        name = "Shared Mod v$i",
                        loaderType = LoaderType.FABRIC,
                        side = ModSide.BOTH
                    )
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Invariants:
        // 1. Size exactly 1 (not 50, not 0)
        assertEquals(1, registry.size)
        // 2. Final value is ONE of the 50 inputs (version "X.0" for some X in 0..49)
        val mod = registry.getById("shared-mod")
        assertNotNull(mod)
        val version = mod!!.version
        assertTrue(version.endsWith(".0"))
        val idx = version.removeSuffix(".0").toIntOrNull()
        assertNotNull(idx)
        assertTrue(idx in 0..49, "Final version should be from one of the inputs, got $version")
    }

    @Test
    fun `concurrent getAll during register does not throw ConcurrentModificationException`() {
        // Reader + writer race — CHM values() jest weakly consistent, nie rzuca CME.
        val writerDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = Thread {
            for (i in 0 until 1000) {
                registry.register(entry("mod-$i", LoaderType.FABRIC))
            }
            writerDone.set(true)
        }
        val readers = (0 until 5).map {
            Thread {
                while (!writerDone.get()) {
                    // Nie powinno rzucić CME — ConcurrentHashMap jest safe
                    val snapshot = registry.getAll()
                    assertNotNull(snapshot)
                }
            }
        }

        writer.start()
        readers.forEach { it.start() }
        writer.join()
        readers.forEach { it.join() }

        assertEquals(1000, registry.size)
    }
}
