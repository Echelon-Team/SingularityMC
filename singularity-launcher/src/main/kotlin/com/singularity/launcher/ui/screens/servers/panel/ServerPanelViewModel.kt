package com.singularity.launcher.ui.screens.servers.panel

import com.singularity.launcher.service.ServerManager
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

data class ServerPanelState(
    val server: ServerManager.Server? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedTab: ServerTab = ServerTab.CONSOLE,
    val consoleLines: List<String> = emptyList(),
    val consoleInput: String = "",
    val isForceStopConfirmOpen: Boolean = false
)

/**
 * ViewModel dla ServerPanel. Ładuje server przez `ServerManager.getById(id)` w init.
 * Zarządza stanem konsoli (10k lines max) i force stop dialog.
 *
 * **Console buffer:** bounded 10k — ring buffer prevents OOM przy long-running serwerze
 * z lot of log output.
 */
class ServerPanelViewModel(
    private val serverManager: ServerManager,
    private val serverId: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<ServerPanelState>(
    ServerPanelState(),
    dispatcher
) {

    companion object {
        const val MAX_CONSOLE_LINES = 10_000
    }

    init {
        loadServer()
    }

    private fun loadServer() {
        updateState { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val s = serverManager.getById(serverId)
                if (s == null) {
                    updateState { it.copy(isLoading = false, error = "Server not found: $serverId") }
                } else {
                    updateState { it.copy(server = s, isLoading = false) }
                }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun setSelectedTab(tab: ServerTab) = updateState { it.copy(selectedTab = tab) }

    fun appendConsoleLine(line: String) {
        updateState {
            val newLines = it.consoleLines + line
            val trimmed = if (newLines.size > MAX_CONSOLE_LINES) {
                newLines.drop(newLines.size - MAX_CONSOLE_LINES)
            } else newLines
            it.copy(consoleLines = trimmed)
        }
    }

    fun setConsoleInput(input: String) = updateState { it.copy(consoleInput = input) }

    fun sendConsoleCommand(onSend: (String) -> Unit = {}) {
        val input = state.value.consoleInput
        if (input.isBlank()) return
        onSend(input)
        appendConsoleLine("> $input")
        updateState { it.copy(consoleInput = "") }
    }

    fun setForceStopConfirmOpen(open: Boolean) = updateState { it.copy(isForceStopConfirmOpen = open) }

    fun confirmForceStop() {
        viewModelScope.launch {
            try {
                serverManager.forceStop(serverId)
                updateState { it.copy(isForceStopConfirmOpen = false) }
                loadServer()
            } catch (e: Exception) {
                updateState { it.copy(error = "Force stop failed: ${e.message}", isForceStopConfirmOpen = false) }
            }
        }
    }
}
