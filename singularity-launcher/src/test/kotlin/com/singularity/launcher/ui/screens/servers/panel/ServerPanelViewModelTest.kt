package com.singularity.launcher.ui.screens.servers.panel

import com.singularity.launcher.service.ServerConfig
import com.singularity.launcher.service.ServerManager
import com.singularity.launcher.service.ServerStatus
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
class ServerPanelViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class FakeServerManager(
        private val server: ServerManager.Server?
    ) : ServerManager {
        override suspend fun list(): List<ServerManager.Server> = listOfNotNull(server)
        override suspend fun getById(id: String): ServerManager.Server? = server?.takeIf { it.id == id }
        override suspend fun create(config: ServerConfig): ServerManager.Server = error("not used")
        override suspend fun delete(id: String) {}
        override suspend fun start(id: String) {}
        override suspend fun stop(id: String) {}
        override suspend fun forceStop(id: String) {}
        override suspend fun restart(id: String) {}
    }

    private fun makeServer(id: String = "test-srv") = ServerManager.Server(
        id = id,
        rootDir = Path.of("/tmp/$id"),
        config = ServerConfig(
            name = "Test Server",
            minecraftVersion = "1.20.1",
            parentInstanceId = null
        ),
        status = ServerStatus.OFFLINE,
        statusChangedAt = 0L
    )

    private fun makeVm(mgr: ServerManager, id: String) = ServerPanelViewModel(
        serverManager = mgr,
        serverId = id,
        dispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
    )

    @Test
    fun `initial state loads server by id`() = runTest {
        val mgr = FakeServerManager(makeServer("abc"))
        val vm = makeVm(mgr, "abc")
        testDispatcher.scheduler.runCurrent()

        val state = vm.state.first()
        assertNotNull(state.server)
        assertEquals("Test Server", state.server!!.config.name)
        vm.onCleared()
    }

    @Test
    fun `not found sets error`() = runTest {
        val mgr = FakeServerManager(null)
        val vm = makeVm(mgr, "nonexistent")
        testDispatcher.scheduler.runCurrent()

        assertNotNull(vm.state.first().error)
        vm.onCleared()
    }

    @Test
    fun `appendConsoleLine adds to console`() = runTest {
        val mgr = FakeServerManager(makeServer())
        val vm = makeVm(mgr, "test-srv")
        testDispatcher.scheduler.runCurrent()

        vm.appendConsoleLine("[INFO] Server starting...")
        vm.appendConsoleLine("[INFO] Done (5.0s)!")

        val state = vm.state.first()
        assertEquals(2, state.consoleLines.size)
        assertTrue(state.consoleLines.last().contains("Done"))
        vm.onCleared()
    }

    @Test
    fun `console line overflow drops oldest`() = runTest {
        val mgr = FakeServerManager(makeServer())
        val vm = makeVm(mgr, "test-srv")
        testDispatcher.scheduler.runCurrent()

        repeat(15000) { vm.appendConsoleLine("line $it") }
        val state = vm.state.first()
        assertTrue(state.consoleLines.size <= 10_000, "Max 10k lines (was ${state.consoleLines.size})")
        assertFalse(state.consoleLines.contains("line 0"), "Oldest dropped")
        vm.onCleared()
    }

    @Test
    fun `setForceStopConfirmOpen shows dialog`() = runTest {
        val mgr = FakeServerManager(makeServer())
        val vm = makeVm(mgr, "test-srv")
        testDispatcher.scheduler.runCurrent()

        vm.setForceStopConfirmOpen(true)
        assertTrue(vm.state.first().isForceStopConfirmOpen)
        vm.setForceStopConfirmOpen(false)
        assertFalse(vm.state.first().isForceStopConfirmOpen)
        vm.onCleared()
    }

    @Test
    fun `setSelectedTab updates tab`() = runTest {
        val mgr = FakeServerManager(makeServer())
        val vm = makeVm(mgr, "test-srv")
        testDispatcher.scheduler.runCurrent()

        vm.setSelectedTab(ServerTab.MODS)
        assertEquals(ServerTab.MODS, vm.state.first().selectedTab)
        vm.onCleared()
    }

    @Test
    fun `initial console has no lines`() = runTest {
        val mgr = FakeServerManager(makeServer())
        val vm = makeVm(mgr, "test-srv")
        testDispatcher.scheduler.runCurrent()

        assertEquals(0, vm.state.first().consoleLines.size)
        vm.onCleared()
    }

    @Test
    fun `initial force stop dialog closed`() = runTest {
        val mgr = FakeServerManager(makeServer())
        val vm = makeVm(mgr, "test-srv")
        testDispatcher.scheduler.runCurrent()

        assertFalse(vm.state.first().isForceStopConfirmOpen)
        vm.onCleared()
    }

    @Test
    fun `onCleared does not throw`() = runTest {
        val mgr = FakeServerManager(makeServer())
        val vm = makeVm(mgr, "test-srv")
        testDispatcher.scheduler.runCurrent()

        vm.onCleared()
        assertTrue(true)
    }
}
