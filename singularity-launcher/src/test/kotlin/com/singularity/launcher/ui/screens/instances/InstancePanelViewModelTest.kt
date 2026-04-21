// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances

import com.singularity.common.model.InstanceConfig
import com.singularity.common.model.InstanceType
import com.singularity.common.model.LoaderType
import com.singularity.launcher.config.InstanceRuntimeSettings
import com.singularity.launcher.config.PreGenPreset
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class InstancePanelViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @TempDir lateinit var tempDir: Path

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class FakeInstanceManager(
        private val instance: InstanceManager.Instance?
    ) : InstanceManager {
        override suspend fun getAll(): List<InstanceManager.Instance> = listOfNotNull(instance)
        override suspend fun getById(id: String): InstanceManager.Instance? =
            if (instance?.id == id) instance else null
        override suspend fun getLastPlayed(): InstanceManager.Instance? = instance
        override suspend fun create(config: InstanceConfig): InstanceManager.Instance = error("not used")
        override suspend fun update(instance: InstanceManager.Instance) {}
        override suspend fun delete(id: String) {}
    }

    private fun makeInstance(id: String = "test-id", rootDir: Path = tempDir.resolve(id)) = InstanceManager.Instance(
        id = id,
        rootDir = rootDir,
        config = InstanceConfig(
            name = "Test Instance",
            minecraftVersion = "1.20.1",
            type = InstanceType.ENHANCED,
            loader = LoaderType.NONE
        ),
        lastPlayedAt = 0L,
        modCount = 5
    )

    private fun makeVm(mgr: InstanceManager, id: String) = InstancePanelViewModel(
        instanceManager = mgr,
        instanceId = id,
        dispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
    )

    @Test
    fun `initial state loads instance by id`() = runTest {
        val mgr = FakeInstanceManager(makeInstance("abc"))
        val vm = makeVm(mgr, "abc")
        testDispatcher.scheduler.runCurrent()

        val state = vm.state.first()
        assertNotNull(state.instance)
        assertEquals("Test Instance", state.instance!!.config.name)
        assertNull(state.launchProgress, "Not launching initially")
        assertFalse(state.isLaunching)
        vm.onCleared()
    }

    @Test
    fun `instance not found sets error state`() = runTest {
        val mgr = FakeInstanceManager(null)
        val vm = makeVm(mgr, "nonexistent")
        testDispatcher.scheduler.runCurrent()

        val state = vm.state.first()
        assertNull(state.instance)
        assertNotNull(state.error, "Error message must be set")
        vm.onCleared()
    }

    @Test
    fun `startLaunch sets isLaunching and progress 0`() = runTest {
        val mgr = FakeInstanceManager(makeInstance())
        val vm = makeVm(mgr, "test-id")
        testDispatcher.scheduler.runCurrent()

        vm.startLaunch()
        val state = vm.state.first()
        assertTrue(state.isLaunching)
        assertEquals(0, state.launchProgress)
        vm.onCleared()
    }

    @Test
    fun `updateLaunchProgress updates progress`() = runTest {
        val mgr = FakeInstanceManager(makeInstance())
        val vm = makeVm(mgr, "test-id")
        testDispatcher.scheduler.runCurrent()

        vm.startLaunch()
        vm.updateLaunchProgress(50)
        assertEquals(50, vm.state.first().launchProgress)

        vm.updateLaunchProgress(100)
        assertEquals(100, vm.state.first().launchProgress)
        vm.onCleared()
    }

    @Test
    fun `finishLaunch clears progress and isLaunching`() = runTest {
        val mgr = FakeInstanceManager(makeInstance())
        val vm = makeVm(mgr, "test-id")
        testDispatcher.scheduler.runCurrent()

        vm.startLaunch()
        vm.finishLaunch()

        val state = vm.state.first()
        assertFalse(state.isLaunching)
        assertNull(state.launchProgress)
        vm.onCleared()
    }

    @Test
    fun `failLaunch clears launching and sets error`() = runTest {
        val mgr = FakeInstanceManager(makeInstance())
        val vm = makeVm(mgr, "test-id")
        testDispatcher.scheduler.runCurrent()

        vm.startLaunch()
        vm.failLaunch("JVM not found")

        val state = vm.state.first()
        assertFalse(state.isLaunching)
        assertNull(state.launchProgress)
        assertEquals("JVM not found", state.error)
        vm.onCleared()
    }

    @Test
    fun `loadRuntimeSettings returns default settings when file absent`() = runTest {
        val mgr = FakeInstanceManager(makeInstance())
        val vm = makeVm(mgr, "test-id")
        testDispatcher.scheduler.runCurrent()

        val settings = vm.state.first().runtimeSettings
        assertEquals(InstanceRuntimeSettings(), settings, "Default settings when no file")
        vm.onCleared()
    }

    @Test
    fun `updatePreGenRadius updates runtime settings`() = runTest {
        val instance = makeInstance()
        java.nio.file.Files.createDirectories(instance.rootDir)
        val mgr = FakeInstanceManager(instance)
        val vm = makeVm(mgr, "test-id")
        testDispatcher.scheduler.runCurrent()

        vm.updatePreGenRadius(128)
        testDispatcher.scheduler.runCurrent()
        val state = vm.state.first()
        assertEquals(128, state.runtimeSettings.preGenRadius)
        vm.onCleared()
    }

    @Test
    fun `applyPreGenPreset updates both preset and radius`() = runTest {
        val instance = makeInstance()
        java.nio.file.Files.createDirectories(instance.rootDir)
        val mgr = FakeInstanceManager(instance)
        val vm = makeVm(mgr, "test-id")
        testDispatcher.scheduler.runCurrent()

        vm.applyPreGenPreset(PreGenPreset.HIGH)
        testDispatcher.scheduler.runCurrent()
        val state = vm.state.first()
        assertEquals(PreGenPreset.HIGH, state.runtimeSettings.preGenPreset)
        assertEquals(128, state.runtimeSettings.preGenRadius, "HIGH preset sets radius 128")
        vm.onCleared()
    }

    @Test
    fun `onCleared cancels viewModelScope without exception`() = runTest {
        val mgr = FakeInstanceManager(makeInstance())
        val vm = makeVm(mgr, "test-id")
        testDispatcher.scheduler.runCurrent()

        vm.onCleared()
        assertTrue(true, "onCleared completes cleanly")
    }
}
