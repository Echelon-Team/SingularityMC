package com.singularity.launcher.ui.screens.servers

import com.singularity.launcher.service.ServerMetrics
import com.singularity.launcher.service.ServerStatus
import java.util.Locale

/**
 * Pure logic helpers dla ServersScreen — testowalne bez Compose.
 *
 * **STARTING timeout detection (#8 edge-case):** server w STARTING state > 60s automatycznie
 * oznaczany jako CRASHED. Wołany z ticker coroutine w ViewModel.
 *
 * **Metrics display:** formatowanie metric values dla card display — stopped server
 * pokazuje "—" we wszystkich polach (text-disabled style).
 */
object ServerStatusLogic {

    const val STARTING_TIMEOUT_MS = 60_000L  // 60s

    fun detectStartingTimeout(
        status: ServerStatus,
        statusChangedAt: Long,
        now: Long = System.currentTimeMillis()
    ): ServerStatus {
        if (status != ServerStatus.STARTING) return status
        return if (now - statusChangedAt > STARTING_TIMEOUT_MS) ServerStatus.CRASHED else ServerStatus.STARTING
    }

    fun formatTps(tps: Double): String = String.format(Locale.ROOT, "%.1f", tps)

    fun formatPlayers(metrics: ServerMetrics): String = "${metrics.playerCount}/${metrics.maxPlayers}"

    fun formatRamGb(metrics: ServerMetrics): String {
        val usedGb = metrics.ramUsedMb / 1024f
        val totalGb = metrics.ramTotalMb / 1024f
        return String.format(Locale.ROOT, "%.1f / %.1f GB", usedGb, totalGb)
    }

    data class MetricsDisplay(
        val tps: String,
        val players: String,
        val ram: String
    )

    fun stoppedMetricsPlaceholder() = MetricsDisplay(tps = "—", players = "—", ram = "—")

    fun runningMetricsDisplay(metrics: ServerMetrics) = MetricsDisplay(
        tps = formatTps(metrics.tps),
        players = formatPlayers(metrics),
        ram = formatRamGb(metrics)
    )
}
