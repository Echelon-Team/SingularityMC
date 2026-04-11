package com.singularity.launcher.ui.screens.screenshots

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
class ScreenshotsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class FakeInstanceManager(
        private val instances: List<InstanceManager.Instance>
    ) : InstanceManager {
        override suspend fun getAll(): List<InstanceManager.Instance> = instances
        override suspend fun getById(id: String): InstanceManager.Instance? = instances.find { it.id == id }
        override suspend fun getLastPlayed(): InstanceManager.Instance? = null
        override suspend fun create(config: InstanceConfig): InstanceManager.Instance = error("not used")
        override suspend fun update(instance: InstanceManager.Instance) {}
        override suspend fun delete(id: String) {}
    }

    private fun instance(id: String, name: String = "Test") = InstanceManager.Instance(
        id = id,
        rootDir = Path.of("/tmp/$id-singularity-nonexistent"),
        config = InstanceConfig(
            name = name,
            minecraftVersion = "1.20.1",
            type = InstanceType.ENHANCED,
            loader = LoaderType.NONE
        ),
        lastPlayedAt = null,
        modCount = 0
    )

    private fun makeVm(mgr: InstanceManager) = ScreenshotsViewModel(
        instanceManager = mgr,
        dispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
    )

    @Test
    fun `initial state loads availableInstances`() = runTest {
        val mgr = FakeInstanceManager(listOf(instance("a", "Alpha"), instance("b", "Beta")))
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        val state = vm.state.first()
        assertEquals(2, state.availableInstances.size)
        assertFalse(state.isLoading)
        vm.onCleared()
    }

    @Test
    fun `initial state has no filter`() = runTest {
        val mgr = FakeInstanceManager(emptyList())
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        assertNull(vm.state.first().instanceFilter)
        vm.onCleared()
    }

    @Test
    fun `setInstanceFilter updates filter`() = runTest {
        val mgr = FakeInstanceManager(emptyList())
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        vm.setInstanceFilter("instance-1")
        assertEquals("instance-1", vm.state.first().instanceFilter)

        vm.setInstanceFilter(null)
        assertNull(vm.state.first().instanceFilter)
        vm.onCleared()
    }

    @Test
    fun `filteredEntries returns all when filter null`() = runTest {
        val mgr = FakeInstanceManager(emptyList())
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        val state = vm.state.first()
        assertEquals(state.allEntries.size, state.filteredEntries.size)
        vm.onCleared()
    }

    @Test
    fun `openPreview sets previewEntry`() = runTest {
        val mgr = FakeInstanceManager(listOf(instance("a")))
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        val entry = ScreenshotEntry(
            path = Path.of("/tmp/test.png"),
            instanceId = "a",
            instanceName = "Alpha",
            filename = "test.png",
            lastModified = 1000L,
            sizeBytes = 1024L
        )
        vm.openPreview(entry)
        assertEquals(entry, vm.state.first().previewEntry)
        vm.onCleared()
    }

    @Test
    fun `closePreview clears previewEntry`() = runTest {
        val mgr = FakeInstanceManager(emptyList())
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        val entry = ScreenshotEntry(
            path = Path.of("/tmp/test.png"),
            instanceId = "a",
            instanceName = "Alpha",
            filename = "test.png",
            lastModified = 1000L,
            sizeBytes = 1024L
        )
        vm.openPreview(entry)
        vm.closePreview()
        assertNull(vm.state.first().previewEntry)
        vm.onCleared()
    }

    @Test
    fun `refresh triggers reload`() = runTest {
        val mgr = FakeInstanceManager(listOf(instance("a")))
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        vm.refresh()
        testDispatcher.scheduler.runCurrent()
        assertFalse(vm.state.first().isLoading)
        vm.onCleared()
    }

    @Test
    fun `onCleared cancels scan job and clears cache`() = runTest {
        val mgr = FakeInstanceManager(emptyList())
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        vm.onCleared()
        assertEquals(0, vm.thumbnailCache.size)
    }
}
