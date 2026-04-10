package com.singularity.agent.pipeline

/**
 * Raport z pre-scan analizy konfliktow miedzy mixinami.
 *
 * - RED: blokada, RuntimeException przy bootstrap — AgentMain przerywa startup
 * - YELLOW: warning log — aplikujemy ale logujemy ze moze byc unexpected behavior
 * GREEN case = null (no Conflict created) — absence of conflict, not a severity level
 *
 * Referencja: design spec sekcja 5.3 (krok 6).
 */
data class ConflictReport(
    val conflicts: List<Conflict>
) {
    val redCount: Int get() = conflicts.count { it.severity == Severity.RED }
    val yellowCount: Int get() = conflicts.count { it.severity == Severity.YELLOW }
    val hasBlockingConflicts: Boolean get() = redCount > 0

    enum class Severity { RED, YELLOW }

    data class Conflict(
        val severity: Severity,
        val targetClass: String,
        val targetMethod: String?,
        val modA: String,
        val modB: String,
        val description: String
    )
}
