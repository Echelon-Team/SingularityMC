package com.singularity.launcher.ui.screens.diagnostics

import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

data class CrashAnalyzerState(
    val reports: List<CrashLogParser.CrashReport> = emptyList(),
    val selectedReport: CrashLogParser.CrashReport? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel dla CrashAnalyzerTab — scanuje crash-reports/ folder all instancji, parse'uje,
 * trzyma state ze sortowanymi reports (most recent first).
 *
 * **#32 edge-case fix:** `reports` jest w state (NIE lokalna var) — zapewnia że recompose
 * widzi latest list po refresh.
 */
class CrashAnalyzerViewModel(
    private val instanceManager: InstanceManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<CrashAnalyzerState>(CrashAnalyzerState(), dispatcher) {

    init {
        refresh()
    }

    fun refresh() {
        updateState { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val all = instanceManager.getAll()
                val allReports = mutableListOf<CrashLogParser.CrashReport>()
                for (instance in all) {
                    val crashDir = instance.rootDir.resolve("minecraft").resolve("crash-reports")
                    allReports.addAll(CrashLogParser.scanDirectory(crashDir))
                }
                allReports.sortByDescending { it.lastModifiedMs }
                updateState { it.copy(reports = allReports, isLoading = false) }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun setSelectedReport(report: CrashLogParser.CrashReport?) {
        updateState { it.copy(selectedReport = report) }
    }
}
