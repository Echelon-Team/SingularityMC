package com.singularity.agent.threading.tick

import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.pool.DimensionPool
import com.singularity.agent.threading.region.PlayerPosition
import com.singularity.agent.threading.region.Region
import com.singularity.agent.threading.region.RegionId
import com.singularity.agent.threading.region.RegionGroupingHint
import com.singularity.agent.threading.region.RegionScheduler
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Phaser
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Wykonuje pojedynczy tick wymiaru z 4-fazową synchronizacją (Phaser).
 *
 * ADDENDUM v2: Phaser zamiast CountDownLatch/CyclicBarrier.
 * - excludedRegions: stuck regiony NIE wchodzą do kolejnych faz
 * - arriveAndDeregister: czysto odpina region od phasera
 * - cooperative abort: region.shouldAbort checked at safe points
 *
 * Design spec 4.4: 4 fazy z barierami synchronizacji.
 */
class TickExecutor(
    private val pool: DimensionPool,
    private val scheduler: RegionScheduler,
    private val config: ThreadingConfig,
    private val regionGroupingHint: RegionGroupingHint = RegionGroupingHint.NONE
) {
    private val logger = LoggerFactory.getLogger(TickExecutor::class.java)

    /** Regiony wykluczone z bieżącego ticka (stuck w poprzedniej fazie). */
    private val excludedRegions = ConcurrentHashMap.newKeySet<RegionId>()

    fun executeTick(
        tickNumber: Long,
        playerPositions: List<PlayerPosition>,
        phaseHandler: (TickPhase, Region) -> Unit
    ) {
        val schedule = scheduler.buildSchedule(playerPositions)
        if (schedule.isEmpty()) {
            logger.trace("Tick {}: no active regions, skipping", tickNumber)
            return
        }

        excludedRegions.clear()
        schedule.forEach { it.setState(Region.State.QUEUED) }

        for (phase in TickPhase.entries) {
            val activeRegions = schedule.filter { it.id !in excludedRegions }
            if (activeRegions.isEmpty()) break

            // REDSTONE phase: respect RegionGroupingHint (linked regions tick sequentially)
            if (phase == TickPhase.REDSTONE) {
                executeRedstonePhase(activeRegions, phaseHandler, tickNumber)
            } else {
                executePhase(phase, activeRegions, phaseHandler, tickNumber)
            }
        }

        schedule.filter { it.id !in excludedRegions }.forEach {
            it.setLastTick(tickNumber)
            it.setState(Region.State.COMPLETED)
        }
    }

    private fun executePhase(
        phase: TickPhase,
        regions: List<Region>,
        phaseHandler: (TickPhase, Region) -> Unit,
        tickNumber: Long
    ) {
        val phaser = Phaser(regions.size + 1) // +1 for coordinator
        val regionResults = ConcurrentHashMap<RegionId, Boolean>()

        regions.forEach { region ->
            pool.submit {
                try {
                    region.setState(Region.State.PROCESSING)
                    phaseHandler(phase, region)
                    regionResults[region.id] = true
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: Throwable) {
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    logger.error("Phase {} failed for region {}: {}", phase, region.id, e.message, e)
                    regionResults[region.id] = true // failed but COMPLETED (not stuck)
                } finally {
                    phaser.arriveAndDeregister()
                }
            }
        }

        // Coordinator waits with timeout
        try {
            phaser.awaitAdvanceInterruptibly(phaser.arrive(), config.barrierTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            val stuck = regions.filter { regionResults[it.id] != true }
            for (region in stuck) {
                excludedRegions.add(region.id)
                region.setState(Region.State.STUCK)
                logger.warn("Phase {} timeout: region {} STUCK — excluded from remaining phases",
                    phase, region.id)
            }
        }
    }

    /**
     * REDSTONE phase: linked regions (via RegionGroupingHint) tick sequentially,
     * unlinked regions tick in parallel. Default: all parallel (NONE hint).
     */
    private fun executeRedstonePhase(
        regions: List<Region>,
        phaseHandler: (TickPhase, Region) -> Unit,
        tickNumber: Long
    ) {
        // Group linked regions — they must tick sequentially
        val processed = mutableSetOf<RegionId>()
        val groups = mutableListOf<List<Region>>()

        for (region in regions) {
            if (region.id in processed) continue
            val linked = regionGroupingHint.getLinkedRegions(region.id)
            if (linked.isEmpty()) {
                groups.add(listOf(region))
                processed.add(region.id)
            } else {
                val group = mutableListOf(region)
                for (linkedId in linked) {
                    val linkedRegion = regions.find { it.id == linkedId }
                    if (linkedRegion != null && linkedRegion.id !in processed) {
                        group.add(linkedRegion)
                        processed.add(linkedRegion.id)
                    }
                }
                processed.add(region.id)
                groups.add(group)
            }
        }

        // Execute: single-region groups go into parallel batch,
        // multi-region groups tick sequentially on the submitting thread
        val parallelRegions = mutableListOf<Region>()
        val sequentialGroups = mutableListOf<List<Region>>()

        for (group in groups) {
            if (group.size == 1) {
                parallelRegions.add(group[0])
            } else {
                sequentialGroups.add(group)
            }
        }

        // Sequential groups first (on coordinator thread) — linked redstone circuits
        for (group in sequentialGroups) {
            for (region in group) {
                try {
                    region.setState(Region.State.PROCESSING)
                    phaseHandler(TickPhase.REDSTONE, region)
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: Throwable) {
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    logger.error("REDSTONE phase failed for linked region {}: {}", region.id, e.message, e)
                }
            }
        }

        // Parallel regions via standard executePhase
        if (parallelRegions.isNotEmpty()) {
            executePhase(TickPhase.REDSTONE, parallelRegions, phaseHandler, tickNumber)
        }
    }

    fun getExcludedRegionCount(): Int = excludedRegions.size
}
