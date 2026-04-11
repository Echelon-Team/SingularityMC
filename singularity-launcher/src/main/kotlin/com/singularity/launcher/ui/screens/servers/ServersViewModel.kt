package com.singularity.launcher.ui.screens.servers

import com.singularity.launcher.service.ServerManager
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

enum class ServerViewMode { GRID, LIST }

data class ServersScreenState(
    val servers: List<ServerManager.Server> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val viewMode: ServerViewMode = ServerViewMode.GRID,
    val isWizardOpen: Boolean = false
)

/**
 * ViewModel dla ServersScreen. Ładuje servery przez `ServerManager.list()` w init.
 * Uruchamia periodic poll co 5s (#9 edge-case) — refresh state + timeout check.
 *
 * **Polling lifecycle:** `pollJob` cancelled w `onCleared` (BaseViewModel scope).
 */
class ServersViewModel(
    private val serverManager: ServerManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<ServersScreenState>(
    ServersScreenState(),
    dispatcher
) {

    companion object {
        const val POLL_INTERVAL_MS = 5000L
    }

    private var pollJob: Job? = null

    init {
        loadServers()
        startPolling()
    }

    private fun loadServers() {
        updateState { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val list = serverManager.list()
                updateState { it.copy(servers = list, isLoading = false) }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun startPolling() {
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                try {
                    val list = serverManager.list()
                    // Apply timeout detection
                    val now = System.currentTimeMillis()
                    val updated = list.map { server ->
                        val newStatus = ServerStatusLogic.detectStartingTimeout(
                            status = server.status,
                            statusChangedAt = server.statusChangedAt,
                            now = now
                        )
                        if (newStatus != server.status) server.copy(status = newStatus) else server
                    }
                    updateState { it.copy(servers = updated) }
                } catch (e: Exception) {
                    // Swallow — periodic failures nie powinny trigger UI error
                }
            }
        }
    }

    /** Explicit timeout check (wywołane przez test). */
    fun checkTimeouts() {
        val now = System.currentTimeMillis()
        val current = state.value.servers
        val updated = current.map { server ->
            val newStatus = ServerStatusLogic.detectStartingTimeout(
                status = server.status,
                statusChangedAt = server.statusChangedAt,
                now = now
            )
            if (newStatus != server.status) server.copy(status = newStatus) else server
        }
        updateState { it.copy(servers = updated) }
    }

    fun refresh() = loadServers()

    fun setViewMode(mode: ServerViewMode) = updateState { it.copy(viewMode = mode) }

    fun openWizard() = updateState { it.copy(isWizardOpen = true) }
    fun closeWizard() = updateState { it.copy(isWizardOpen = false) }

    fun startServer(id: String) {
        viewModelScope.launch {
            try {
                serverManager.start(id)
                loadServers()
            } catch (e: Exception) {
                updateState { it.copy(error = "Start failed: ${e.message}") }
            }
        }
    }

    fun stopServer(id: String) {
        viewModelScope.launch {
            try {
                serverManager.stop(id)
                loadServers()
            } catch (e: Exception) {
                updateState { it.copy(error = "Stop failed: ${e.message}") }
            }
        }
    }

    fun forceStopServer(id: String) {
        viewModelScope.launch {
            try {
                serverManager.forceStop(id)
                loadServers()
            } catch (e: Exception) {
                updateState { it.copy(error = "Force stop failed: ${e.message}") }
            }
        }
    }

    fun restartServer(id: String) {
        viewModelScope.launch {
            try {
                serverManager.restart(id)
                loadServers()
            } catch (e: Exception) {
                updateState { it.copy(error = "Restart failed: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
