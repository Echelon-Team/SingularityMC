// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.servers

import com.singularity.launcher.service.ServerMetrics
import com.singularity.launcher.service.ServerStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ServerStatusLogicTest {

    @Test
    fun `detectStartingTimeout returns CRASHED after 60s`() {
        val now = 100_000L
        val startedAt = now - 61_000L
        val newStatus = ServerStatusLogic.detectStartingTimeout(
            status = ServerStatus.STARTING,
            statusChangedAt = startedAt,
            now = now
        )
        assertEquals(ServerStatus.CRASHED, newStatus)
    }

    @Test
    fun `detectStartingTimeout keeps STARTING if under 60s`() {
        val now = 100_000L
        val startedAt = now - 30_000L
        assertEquals(
            ServerStatus.STARTING,
            ServerStatusLogic.detectStartingTimeout(ServerStatus.STARTING, startedAt, now)
        )
    }

    @Test
    fun `detectStartingTimeout ignores non-STARTING statuses`() {
        val now = 100_000L
        val longAgo = now - 999_000L
        assertEquals(
            ServerStatus.RUNNING,
            ServerStatusLogic.detectStartingTimeout(ServerStatus.RUNNING, longAgo, now)
        )
        assertEquals(
            ServerStatus.OFFLINE,
            ServerStatusLogic.detectStartingTimeout(ServerStatus.OFFLINE, longAgo, now)
        )
    }

    @Test
    fun `formatTps rounds to 1 decimal`() {
        assertEquals("19.5", ServerStatusLogic.formatTps(19.5))
        assertEquals("20.0", ServerStatusLogic.formatTps(20.0))
        assertEquals("15.3", ServerStatusLogic.formatTps(15.333))
    }

    @Test
    fun `formatPlayers returns count over max`() {
        val metrics = ServerMetrics(
            tps = 20.0, playerCount = 5, maxPlayers = 20,
            ramUsedMb = 2048, ramTotalMb = 4096, cpuPercent = 35.0
        )
        assertEquals("5/20", ServerStatusLogic.formatPlayers(metrics))
    }

    @Test
    fun `formatRamGb formats as GB with 1 decimal`() {
        val metrics = ServerMetrics(
            tps = 20.0, playerCount = 0, maxPlayers = 20,
            ramUsedMb = 2048, ramTotalMb = 4096, cpuPercent = 0.0
        )
        assertEquals("2.0 / 4.0 GB", ServerStatusLogic.formatRamGb(metrics))
    }

    @Test
    fun `stoppedMetricsPlaceholder returns em-dash for all fields`() {
        val placeholder = ServerStatusLogic.stoppedMetricsPlaceholder()
        assertEquals("—", placeholder.tps)
        assertEquals("—", placeholder.players)
        assertEquals("—", placeholder.ram)
    }

    @Test
    fun `runningMetricsDisplay maps from real metrics`() {
        val metrics = ServerMetrics(
            tps = 19.8, playerCount = 3, maxPlayers = 20,
            ramUsedMb = 3072, ramTotalMb = 4096, cpuPercent = 45.5
        )
        val display = ServerStatusLogic.runningMetricsDisplay(metrics)
        assertEquals("19.8", display.tps)
        assertEquals("3/20", display.players)
        assertEquals("3.0 / 4.0 GB", display.ram)
    }
}
