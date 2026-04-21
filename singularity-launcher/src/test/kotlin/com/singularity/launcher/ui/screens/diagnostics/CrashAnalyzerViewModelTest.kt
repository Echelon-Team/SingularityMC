// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.diagnostics

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
class CrashAnalyzerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class FakeInstanceManager(val instances: List<InstanceManager.Instance>) : InstanceManager {
        override suspend fun getAll(): List<InstanceManager.Instance> = instances
        override suspend fun getById(id: String): InstanceManager.Instance? = instances.find { it.id == id }
        override suspend fun getLastPlayed(): InstanceManager.Instance? = null
        override suspend fun create(config: InstanceConfig): InstanceManager.Instance = error("not used")
        override suspend fun update(instance: InstanceManager.Instance) {}
        override suspend fun delete(id: String) {}
    }

    private fun instance(id: String) = InstanceManager.Instance(
        id = id,
        rootDir = Path.of("/tmp/$id"),
        config = InstanceConfig("Test", "1.20.1", InstanceType.ENHANCED, LoaderType.NONE),
        lastPlayedAt = 0L, modCount = 0
    )

    private fun makeVm(all: List<InstanceManager.Instance>) = CrashAnalyzerViewModel(
        FakeInstanceManager(all),
        UnconfinedTestDispatcher(testDispatcher.scheduler)
    )

    @Test
    fun `initial state loads empty reports when no instances`() = runTest {
        val vm = makeVm(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.state.first().reports.size)
        assertFalse(vm.state.first().isLoading)
    }

    @Test
    fun `setSelectedReport updates state`() = runTest {
        val vm = makeVm(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        val report = CrashLogParser.CrashReport(
            fileName = "crash.txt",
            rawContent = "test",
            time = "2026-04-11",
            description = "Test"
        )
        vm.setSelectedReport(report)
        assertEquals(report, vm.state.first().selectedReport)
    }

    @Test
    fun `clearSelection nullifies selectedReport`() = runTest {
        val vm = makeVm(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        val report = CrashLogParser.CrashReport("c.txt", "r", "t", "d")
        vm.setSelectedReport(report)
        vm.setSelectedReport(null)
        assertNull(vm.state.first().selectedReport)
    }

    @Test
    fun `refresh triggers reload`() = runTest {
        val vm = makeVm(listOf(instance("a")))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.state.first().isLoading)
    }

    @Test
    fun `onCleared completes without exception`() = runTest {
        val vm = makeVm(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onCleared()
        assertTrue(true)
    }
}
