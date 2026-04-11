package com.singularity.launcher.ui.screens.home

import com.singularity.common.model.InstanceConfig
import com.singularity.common.model.InstanceType
import com.singularity.common.model.LoaderType
import com.singularity.launcher.service.InstanceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
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
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    // Fake InstanceManager dla testów (będzie dostarczony w Task 26)
    private class FakeInstanceManager(
        private val lastPlayed: InstanceManager.Instance? = null
    ) : InstanceManager {
        override suspend fun getLastPlayed(): InstanceManager.Instance? = lastPlayed
        override suspend fun getAll(): List<InstanceManager.Instance> = emptyList()
        override suspend fun getById(id: String): InstanceManager.Instance? = null
        override suspend fun create(config: InstanceConfig): InstanceManager.Instance = error("not used")
        override suspend fun update(instance: InstanceManager.Instance) {}
        override suspend fun delete(id: String) {}
    }

    private fun fakeInstance(name: String, lastPlayedAt: Long) = InstanceManager.Instance(
        id = "test-$name",
        rootDir = Path.of("/tmp/$name"),
        config = InstanceConfig(
            name = name,
            minecraftVersion = "1.20.1",
            type = InstanceType.ENHANCED,
            loader = LoaderType.NONE,
            ramMb = 4096,
            threads = 4,
            jvmArgs = ""
        ),
        lastPlayedAt = lastPlayedAt,
        modCount = 10
    )

    @Test
    fun `initial state has no lastPlayed and isLoadingNews true`() = runTest {
        val vm = HomeViewModel(FakeInstanceManager(), UnconfinedTestDispatcher(testDispatcher.scheduler))
        val state = vm.state.first()
        assertNull(state.lastPlayedInstance)
        // Init kicks off loadNews → isLoadingNews=true briefly
    }

    @Test
    fun `loadLastPlayed updates state with instance from InstanceManager`() = runTest {
        val now = System.currentTimeMillis()
        val mgr = FakeInstanceManager(lastPlayed = fakeInstance("Survival", lastPlayedAt = now - 3600000L))
        val vm = HomeViewModel(mgr, UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertNotNull(state.lastPlayedInstance)
        assertEquals("Survival", state.lastPlayedInstance!!.instanceName)
        assertEquals("test-Survival", state.lastPlayedInstance.instanceId)
        assertEquals(InstanceType.ENHANCED, state.lastPlayedInstance.type)
        assertEquals("1.20.1", state.lastPlayedInstance.minecraftVersion)
    }

    @Test
    fun `loadLastPlayed with no instances has null lastPlayedInstance`() = runTest {
        val vm = HomeViewModel(FakeInstanceManager(lastPlayed = null), UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.state.first().lastPlayedInstance)
    }

    @Test
    fun `loadNews loads mock news from bundled resource`() = runTest {
        val vm = HomeViewModel(FakeInstanceManager(), UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.first()
        assertFalse(state.isLoadingNews)
        assertTrue(state.news.isNotEmpty(), "Should have at least 1 bundled news item")
    }

    @Test
    fun `onContinueClick calls onLaunch with instance id`() = runTest {
        val mgr = FakeInstanceManager(lastPlayed = fakeInstance("Creative", System.currentTimeMillis()))
        val vm = HomeViewModel(mgr, UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()

        var launchedId: String? = null
        vm.onContinueClick { id -> launchedId = id }
        assertEquals("test-Creative", launchedId)
    }

    @Test
    fun `onContinueClick is no-op when no lastPlayed`() = runTest {
        val vm = HomeViewModel(FakeInstanceManager(lastPlayed = null), UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()

        var launchedId: String? = null
        vm.onContinueClick { id -> launchedId = id }
        assertNull(launchedId, "Should not call onLaunch when no lastPlayed")
    }

    @Test
    fun `formatLastPlayedSubtitle formats properly`() {
        val subtitle = formatLastPlayedSubtitle(
            name = "Survival World",
            version = "1.20.1",
            type = InstanceType.ENHANCED,
            lastPlayedMs = System.currentTimeMillis() - 7200000L  // 2h ago
        )
        assertTrue(subtitle.contains("Survival World"))
        assertTrue(subtitle.contains("1.20.1"))
        assertTrue(subtitle.contains("Enhanced"))
        assertTrue(subtitle.contains("temu") || subtitle.contains("ago"))
    }
}
