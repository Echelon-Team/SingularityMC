// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.diagnostics

import com.singularity.launcher.crash.CrashAnalyzer
import com.singularity.launcher.crash.CrashPatternMatcher
import com.singularity.launcher.crash.CrashReportBuilder
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

data class CrashAnalyzerState(
    val reports: List<CrashLogParser.CrashReport> = emptyList(),
    val selectedReport: CrashLogParser.CrashReport? = null,
    val analysis: CrashAnalyzer.AnalysisResult? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel dla CrashAnalyzerTab — scanuje crash-reports/ folder all instancji, parse'uje,
 * trzyma state ze sortowanymi reports (most recent first).
 *
 * Sub 5: When a report is selected, CrashAnalyzer (crash/ package) provides full analysis:
 * category, human-readable description, suggested actions, Markdown report.
 */
class CrashAnalyzerViewModel(
    private val instanceManager: InstanceManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<CrashAnalyzerState>(CrashAnalyzerState(), dispatcher) {

    private val patternMatcher = CrashPatternMatcher()
    private val reportBuilder = CrashReportBuilder(
        launcherVersion = "1.0.0",
        agentVersion = "1.0.0"
    )

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
        updateState { it.copy(selectedReport = report, analysis = null) }
        if (report != null) {
            analyzeReport(report)
        }
    }

    private fun analyzeReport(report: CrashLogParser.CrashReport) {
        viewModelScope.launch {
            try {
                val parsed = com.singularity.launcher.crash.CrashLogParser.parse(report.rawContent)
                val category = patternMatcher.categorize(parsed)
                val description = patternMatcher.describe(parsed, category)
                val actions = patternMatcher.suggestActions(category)
                val fullReport = reportBuilder.build(
                    parsed = parsed,
                    category = category,
                    description = description,
                    suggestedActions = actions,
                    vanillaCrashLog = report.rawContent,
                    agentLogs = emptyList() // agent logs loaded per-instance when CrashAnalyzer is used directly
                )
                updateState {
                    it.copy(analysis = CrashAnalyzer.AnalysisResult(
                        parsed = parsed,
                        category = category,
                        humanReadableDescription = description,
                        suggestedActions = actions,
                        fullReport = fullReport
                    ))
                }
            } catch (e: Exception) {
                updateState { it.copy(error = "Analysis failed: ${e.message}") }
            }
        }
    }
}
