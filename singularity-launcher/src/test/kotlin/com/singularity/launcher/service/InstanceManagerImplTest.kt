// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service

import com.singularity.common.model.InstanceConfig
import com.singularity.common.model.InstanceType
import com.singularity.common.model.LoaderType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstanceManagerImplTest {

    @TempDir
    lateinit var tempRoot: Path

    private fun manager() = InstanceManagerImpl(tempRoot)

    private fun config(name: String = "Test") = InstanceConfig(
        name = name,
        minecraftVersion = "1.20.1",
        type = InstanceType.ENHANCED,
        loader = LoaderType.NONE
    )

    @Test
    fun `empty root returns empty list`() = runTest {
        assertEquals(0, manager().getAll().size)
    }

    @Test
    fun `create adds instance with unique UUID`() = runTest {
        val mgr = manager()
        val instance = mgr.create(config("Alpha"))

        assertNotNull(instance.id)
        assertEquals("Alpha", instance.config.name)
        assertTrue(Files.exists(instance.rootDir.resolve("instance.json")))
        assertTrue(Files.exists(instance.rootDir.resolve("minecraft")))
        assertTrue(Files.exists(instance.rootDir.resolve(".singularity").resolve("modules")))
        assertTrue(Files.exists(instance.rootDir.resolve(".singularity").resolve("cache")))
    }

    @Test
    fun `getById returns created instance`() = runTest {
        val mgr = manager()
        val created = mgr.create(config("Beta"))
        val found = mgr.getById(created.id)
        assertNotNull(found)
        assertEquals("Beta", found!!.config.name)
    }

    @Test
    fun `getById for non-existent returns null`() = runTest {
        val mgr = manager()
        assertNull(mgr.getById("nonexistent-id"))
    }

    @Test
    fun `getAll returns multiple instances`() = runTest {
        val mgr = manager()
        mgr.create(config("A"))
        mgr.create(config("B"))
        mgr.create(config("C"))

        val all = mgr.getAll()
        assertEquals(3, all.size)
    }

    @Test
    fun `getLastPlayed returns null when no instances`() = runTest {
        val mgr = manager()
        assertNull(mgr.getLastPlayed())
    }

    @Test
    fun `getLastPlayed returns most recent by lastPlayedAt`() = runTest {
        val mgr = manager()
        val a = mgr.create(config("A"))
        val b = mgr.create(config("B"))
        mgr.create(config("C"))

        // Update lastPlayedAt
        mgr.update(b.copy(lastPlayedAt = 999_999L))
        mgr.update(a.copy(lastPlayedAt = 1_000L))

        val last = mgr.getLastPlayed()
        assertNotNull(last)
        assertEquals("B", last!!.config.name)
    }

    @Test
    fun `update persists changes`() = runTest {
        val mgr = manager()
        val instance = mgr.create(config("Original"))

        val updated = instance.copy(
            config = instance.config.copy(name = "Renamed", ramMb = 8192)
        )
        mgr.update(updated)

        val refetched = mgr.getById(instance.id)
        assertEquals("Renamed", refetched!!.config.name)
        assertEquals(8192, refetched.config.ramMb)
    }

    @Test
    fun `update non-existent throws IllegalStateException`() {
        val mgr = manager()
        val fake = InstanceManager.Instance(
            id = "fake-id",
            rootDir = tempRoot.resolve("fake-id"),
            config = config("Fake"),
            lastPlayedAt = null,
            modCount = 0
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { mgr.update(fake) }
        }
    }

    @Test
    fun `delete removes instance directory`() = runTest {
        val mgr = manager()
        val instance = mgr.create(config("ToDelete"))
        assertTrue(Files.exists(instance.rootDir))

        mgr.delete(instance.id)
        assertFalse(Files.exists(instance.rootDir))
        assertNull(mgr.getById(instance.id))
    }

    @Test
    fun `delete non-existent is no-op`() = runTest {
        val mgr = manager()
        // Should not throw
        mgr.delete("nonexistent")
        assertTrue(true)
    }

    @Test
    fun `getAll skips corrupted instance json gracefully`() = runTest {
        val mgr = manager()
        mgr.create(config("Valid"))

        // Manually create corrupted instance directory
        val corruptedDir = tempRoot.resolve("corrupted-id")
        Files.createDirectories(corruptedDir)
        Files.writeString(corruptedDir.resolve("instance.json"), "{not valid json")

        val all = mgr.getAll()
        assertEquals(1, all.size, "Corrupted instance skipped")
        assertEquals("Valid", all[0].config.name)
    }
}
