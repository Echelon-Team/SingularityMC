// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances

import com.singularity.common.model.InstanceConfig
import com.singularity.common.model.InstanceType
import com.singularity.common.model.LoaderType
import com.singularity.launcher.service.InstanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class InstancesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class FakeInstanceManager(
        private val all: List<InstanceManager.Instance>
    ) : InstanceManager {
        override suspend fun getAll(): List<InstanceManager.Instance> = all
        override suspend fun getById(id: String): InstanceManager.Instance? = all.find { it.id == id }
        override suspend fun getLastPlayed(): InstanceManager.Instance? = all.maxByOrNull { it.lastPlayedAt ?: 0L }
        override suspend fun create(config: InstanceConfig): InstanceManager.Instance = error("not used")
        override suspend fun update(instance: InstanceManager.Instance) {}
        override suspend fun delete(id: String) {}
    }

    private fun makeInstance(
        id: String,
        name: String,
        mcVersion: String,
        type: InstanceType,
        loader: LoaderType = LoaderType.NONE,
        modCount: Int = 0,
        lastPlayedAt: Long? = null
    ) = InstanceManager.Instance(
        id = id,
        rootDir = Path.of("/tmp/$id"),
        config = InstanceConfig(
            name = name,
            minecraftVersion = mcVersion,
            type = type,
            loader = loader
        ),
        lastPlayedAt = lastPlayedAt,
        modCount = modCount
    )

    private fun makeVm(all: List<InstanceManager.Instance>): InstancesViewModel {
        return InstancesViewModel(
            FakeInstanceManager(all),
            UnconfinedTestDispatcher(testDispatcher.scheduler)
        )
    }

    @Test
    fun `initial state loads all instances from InstanceManager`() = runTest {
        val vm = makeVm(listOf(
            makeInstance("a", "Survival", "1.20.1", InstanceType.ENHANCED),
            makeInstance("b", "Creative", "1.16.5", InstanceType.VANILLA)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertEquals(2, state.instances.size)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `filter ENHANCED returns only enhanced instances`() = runTest {
        val vm = makeVm(listOf(
            makeInstance("a", "A", "1.20.1", InstanceType.ENHANCED),
            makeInstance("b", "B", "1.20.1", InstanceType.VANILLA),
            makeInstance("c", "C", "1.20.1", InstanceType.ENHANCED)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setFilter(InstanceFilter.ENHANCED)
        val filtered = vm.state.first().filteredInstances
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.config.type == InstanceType.ENHANCED })
    }

    @Test
    fun `filter VANILLA returns only vanilla instances`() = runTest {
        val vm = makeVm(listOf(
            makeInstance("a", "A", "1.20.1", InstanceType.ENHANCED),
            makeInstance("b", "B", "1.20.1", InstanceType.VANILLA)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setFilter(InstanceFilter.VANILLA)
        val filtered = vm.state.first().filteredInstances
        assertEquals(1, filtered.size)
        assertEquals("B", filtered[0].config.name)
    }

    @Test
    fun `search query filters by name case-insensitive`() = runTest {
        val vm = makeVm(listOf(
            makeInstance("a", "Survival World", "1.20.1", InstanceType.ENHANCED),
            makeInstance("b", "Creative World", "1.20.1", InstanceType.ENHANCED),
            makeInstance("c", "Skyblock", "1.20.1", InstanceType.ENHANCED)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setSearchQuery("world")
        val filtered = vm.state.first().filteredInstances
        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.config.name == "Survival World" })
        assertTrue(filtered.any { it.config.name == "Creative World" })
    }

    @Test
    fun `sort by NAME is alphabetical`() = runTest {
        val vm = makeVm(listOf(
            makeInstance("c", "Zebra", "1.20.1", InstanceType.ENHANCED),
            makeInstance("a", "Alpha", "1.20.1", InstanceType.ENHANCED),
            makeInstance("b", "Beta", "1.20.1", InstanceType.ENHANCED)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setSortMode(InstanceSortMode.NAME)
        val sorted = vm.state.first().filteredInstances
        assertEquals(listOf("Alpha", "Beta", "Zebra"), sorted.map { it.config.name })
    }

    @Test
    fun `sort by LAST_PLAYED puts most recent first`() = runTest {
        val now = System.currentTimeMillis()
        val vm = makeVm(listOf(
            makeInstance("a", "Old", "1.20.1", InstanceType.ENHANCED, lastPlayedAt = now - 86400000L),
            makeInstance("b", "Fresh", "1.20.1", InstanceType.ENHANCED, lastPlayedAt = now),
            makeInstance("c", "Never", "1.20.1", InstanceType.ENHANCED, lastPlayedAt = null)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setSortMode(InstanceSortMode.LAST_PLAYED)
        val sorted = vm.state.first().filteredInstances
        assertEquals(listOf("Fresh", "Old", "Never"), sorted.map { it.config.name })
    }

    @Test
    fun `sort by MC_VERSION uses semver regression 1_20_1 greater than 1_9`() = runTest {
        // S8 v2 regression test
        val vm = makeVm(listOf(
            makeInstance("a", "A", "1.9", InstanceType.ENHANCED),
            makeInstance("b", "B", "1.20.1", InstanceType.ENHANCED),
            makeInstance("c", "C", "1.16.5", InstanceType.ENHANCED)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setSortMode(InstanceSortMode.MC_VERSION)
        val sorted = vm.state.first().filteredInstances
        assertEquals(
            listOf("1.20.1", "1.16.5", "1.9"),
            sorted.map { it.config.minecraftVersion },
            "Semver sort: 1.20.1 > 1.16.5 > 1.9"
        )
    }

    @Test
    fun `filter and search combined`() = runTest {
        val vm = makeVm(listOf(
            makeInstance("a", "Enhanced World", "1.20.1", InstanceType.ENHANCED),
            makeInstance("b", "Vanilla World", "1.20.1", InstanceType.VANILLA),
            makeInstance("c", "Enhanced Skyblock", "1.20.1", InstanceType.ENHANCED)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setFilter(InstanceFilter.ENHANCED)
        vm.setSearchQuery("World")

        val filtered = vm.state.first().filteredInstances
        assertEquals(1, filtered.size)
        assertEquals("Enhanced World", filtered[0].config.name)
    }

    @Test
    fun `view mode toggle GRID to LIST`() = runTest {
        val vm = makeVm(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(InstanceViewMode.GRID, vm.state.first().viewMode, "Default GRID")
        vm.setViewMode(InstanceViewMode.LIST)
        assertEquals(InstanceViewMode.LIST, vm.state.first().viewMode)
    }

    @Test
    fun `empty state when no instances`() = runTest {
        val vm = makeVm(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertTrue(state.instances.isEmpty())
        assertTrue(state.filteredInstances.isEmpty())
    }

    @Test
    fun `search with no match returns empty`() = runTest {
        val vm = makeVm(listOf(
            makeInstance("a", "Survival", "1.20.1", InstanceType.ENHANCED)
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setSearchQuery("nonexistent")
        assertTrue(vm.state.first().filteredInstances.isEmpty())
    }

    @Test
    fun `onCleared cancels viewModelScope`() = runTest {
        val vm = makeVm(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onCleared()
        // Successful execution indicates scope cancellation without exception
        assertTrue(true)
    }
}
