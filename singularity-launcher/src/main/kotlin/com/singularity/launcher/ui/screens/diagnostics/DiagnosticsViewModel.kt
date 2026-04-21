// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.diagnostics

import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.service.ipc.GameMetrics
import com.singularity.launcher.service.ipc.IpcClient
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

data class DiagnosticsScreenState(
    val selectedTab: DiagnosticsTab = DiagnosticsTab.MONITORING,
    val selectedInstance: String? = null,
    val availableInstances: List<Pair<String, String>> = emptyList(),
    val isConnected: Boolean = false,
    val metrics: DiagnosticsMetrics = DiagnosticsMetrics.EMPTY,
    val currentSnapshot: GameMetrics? = null,
    val error: String? = null
)

/**
 * ViewModel dla DiagnosticsScreen. Collect `IpcClient.metrics` StateFlow + push do
 * `DiagnosticsMetrics` history. Collect `IpcClient.isConnected` StateFlow.
 *
 * **CRITICAL (#26 edge-case):** `onCleared` cancels obie collect jobs + wywołuje
 * `ipcClient.disconnect()`. Bez tego polling job wycieka.
 */
class DiagnosticsViewModel(
    private val ipcClient: IpcClient,
    private val instanceManager: InstanceManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<DiagnosticsScreenState>(DiagnosticsScreenState(), dispatcher) {

    private var metricsCollectJob: Job? = null
    private var connectedCollectJob: Job? = null

    init {
        loadInstances()
        startCollecting()
    }

    private fun loadInstances() {
        viewModelScope.launch {
            try {
                val instances = instanceManager.getAll().map { it.id to it.config.name }
                updateState {
                    it.copy(
                        availableInstances = instances,
                        selectedInstance = instances.firstOrNull()?.first
                    )
                }
                state.value.selectedInstance?.let { ipcClient.connect(it) }
            } catch (e: Exception) {
                updateState { it.copy(error = e.message) }
            }
        }
    }

    private fun startCollecting() {
        metricsCollectJob = viewModelScope.launch {
            ipcClient.metrics.collectLatest { snapshot ->
                if (snapshot != null) {
                    updateState {
                        it.copy(
                            currentSnapshot = snapshot,
                            metrics = DiagnosticsMetricsLogic.pushAll(
                                metrics = it.metrics,
                                fps = snapshot.fps.toFloat(),
                                tps = snapshot.tps.toFloat(),
                                ram = snapshot.ramUsedMb.toFloat(),
                                cpu = snapshot.cpuPercent.toFloat(),
                                gpu = snapshot.gpuPercent.toFloat(),
                                maxSamples = 60
                            )
                        )
                    }
                }
            }
        }
        connectedCollectJob = viewModelScope.launch {
            ipcClient.isConnected.collectLatest { connected ->
                updateState { it.copy(isConnected = connected) }
            }
        }
    }

    fun setTab(tab: DiagnosticsTab) = updateState { it.copy(selectedTab = tab) }

    fun setInstance(instanceId: String) {
        val currentInstance = state.value.selectedInstance
        if (currentInstance != instanceId) {
            ipcClient.disconnect()
            updateState {
                it.copy(
                    selectedInstance = instanceId,
                    metrics = DiagnosticsMetrics.EMPTY,
                    currentSnapshot = null
                )
            }
            ipcClient.connect(instanceId)
        }
    }

    override fun onCleared() {
        metricsCollectJob?.cancel()
        connectedCollectJob?.cancel()
        ipcClient.disconnect()
        super.onCleared()
    }
}
