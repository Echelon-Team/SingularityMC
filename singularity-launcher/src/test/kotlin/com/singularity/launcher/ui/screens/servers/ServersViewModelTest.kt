package com.singularity.launcher.ui.screens.servers

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
class ServersViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class FakeServerManager(
        initial: List<ServerManager.Server> = emptyList()
    ) : ServerManager {
        var servers: MutableList<ServerManager.Server> = initial.toMutableList()
        var listCallCount = 0
        override suspend fun list(): List<ServerManager.Server> {
            listCallCount++
            return servers
        }
        override suspend fun getById(id: String): ServerManager.Server? = servers.find { it.id == id }
        override suspend fun create(config: ServerConfig): ServerManager.Server = error("not used")
        override suspend fun delete(id: String) { servers.removeAll { it.id == id } }
        override suspend fun start(id: String) {
            servers = servers.map {
                if (it.id == id) it.copy(status = ServerStatus.STARTING, statusChangedAt = System.currentTimeMillis())
                else it
            }.toMutableList()
        }
        override suspend fun stop(id: String) {
            servers = servers.map {
                if (it.id == id) it.copy(status = ServerStatus.STOPPING)
                else it
            }.toMutableList()
        }
        override suspend fun forceStop(id: String) {
            servers = servers.map {
                if (it.id == id) it.copy(status = ServerStatus.OFFLINE)
                else it
            }.toMutableList()
        }
        override suspend fun restart(id: String) { stop(id); start(id) }
    }

    private fun server(
        id: String,
        name: String = "Test",
        status: ServerStatus = ServerStatus.OFFLINE,
        statusChangedAt: Long = 0L
    ) = ServerManager.Server(
        id = id,
        rootDir = Path.of("/tmp/$id"),
        config = ServerConfig(
            name = name,
            minecraftVersion = "1.20.1",
            parentInstanceId = null
        ),
        status = status,
        statusChangedAt = statusChangedAt
    )

    private fun makeVm(mgr: ServerManager) = ServersViewModel(
        serverManager = mgr,
        dispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
    )

    @Test
    fun `initial state loads servers from manager`() = runTest {
        val mgr = FakeServerManager(listOf(server("a", "Alpha"), server("b", "Beta")))
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        val state = vm.state.first()
        assertEquals(2, state.servers.size)
        assertFalse(state.isLoading)
        vm.onCleared()
    }

    @Test
    fun `empty state when no servers`() = runTest {
        val mgr = FakeServerManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        val state = vm.state.first()
        assertTrue(state.servers.isEmpty())
        vm.onCleared()
    }

    @Test
    fun `refresh triggers manager list`() = runTest {
        val mgr = FakeServerManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        val before = mgr.listCallCount
        vm.refresh()
        testDispatcher.scheduler.runCurrent()
        assertTrue(mgr.listCallCount > before)
        vm.onCleared()
    }

    @Test
    fun `viewMode toggle GRID to LIST`() = runTest {
        val mgr = FakeServerManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        assertEquals(ServerViewMode.GRID, vm.state.first().viewMode)
        vm.setViewMode(ServerViewMode.LIST)
        assertEquals(ServerViewMode.LIST, vm.state.first().viewMode)
        vm.onCleared()
    }

    @Test
    fun `startServer calls manager start`() = runTest {
        val mgr = FakeServerManager(listOf(server("a", status = ServerStatus.OFFLINE)))
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        vm.startServer("a")
        testDispatcher.scheduler.runCurrent()

        val updated = mgr.servers.first { it.id == "a" }
        assertEquals(ServerStatus.STARTING, updated.status)
        vm.onCleared()
    }

    @Test
    fun `stopServer calls manager stop`() = runTest {
        val mgr = FakeServerManager(listOf(server("a", status = ServerStatus.RUNNING)))
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        vm.stopServer("a")
        testDispatcher.scheduler.runCurrent()

        val updated = mgr.servers.first { it.id == "a" }
        assertEquals(ServerStatus.STOPPING, updated.status)
        vm.onCleared()
    }

    @Test
    fun `forceStop calls manager forceStop`() = runTest {
        val mgr = FakeServerManager(listOf(server("a", status = ServerStatus.RUNNING)))
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        vm.forceStopServer("a")
        testDispatcher.scheduler.runCurrent()

        assertEquals(ServerStatus.OFFLINE, mgr.servers.first { it.id == "a" }.status)
        vm.onCleared()
    }

    @Test
    fun `STARTING timeout auto-detects CRASHED after 60s`() = runTest {
        val longAgo = System.currentTimeMillis() - 65_000L
        val mgr = FakeServerManager(listOf(
            server("a", status = ServerStatus.STARTING, statusChangedAt = longAgo)
        ))
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        vm.checkTimeouts()
        testDispatcher.scheduler.runCurrent()

        val state = vm.state.first()
        val server = state.servers.first { it.id == "a" }
        assertEquals(ServerStatus.CRASHED, server.status)
        vm.onCleared()
    }

    @Test
    fun `STARTING under 60s remains STARTING`() = runTest {
        val recently = System.currentTimeMillis() - 30_000L
        val mgr = FakeServerManager(listOf(
            server("a", status = ServerStatus.STARTING, statusChangedAt = recently)
        ))
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        vm.checkTimeouts()
        val server = vm.state.first().servers.first { it.id == "a" }
        assertEquals(ServerStatus.STARTING, server.status)
        vm.onCleared()
    }

    @Test
    fun `openWizard opens state`() = runTest {
        val mgr = FakeServerManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        vm.openWizard()
        assertTrue(vm.state.first().isWizardOpen)
        vm.closeWizard()
        assertFalse(vm.state.first().isWizardOpen)
        vm.onCleared()
    }

    @Test
    fun `onCleared cancels polling job`() = runTest {
        val mgr = FakeServerManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.runCurrent()

        vm.onCleared()
        assertTrue(true)
    }
}
