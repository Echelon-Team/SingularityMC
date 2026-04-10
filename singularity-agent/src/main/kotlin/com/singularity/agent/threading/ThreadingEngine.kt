package com.singularity.agent.threading

import com.singularity.agent.threading.chunk.ChunkGenerationPipeline
import com.singularity.agent.threading.chunk.ChunkLoadingPool
import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.detection.OptimizationModDetector
import com.singularity.agent.threading.memory.HeapMonitor
import com.singularity.agent.threading.pool.DimensionPool
import com.singularity.agent.threading.pool.DimensionPoolManager
import com.singularity.agent.threading.region.RegionGrid
import com.singularity.agent.threading.region.RegionGroupingHint
import com.singularity.agent.threading.region.RegionScheduler
import com.singularity.agent.threading.tick.TickExecutor
import com.singularity.agent.threading.transfer.CrossDimensionTransferQueue
import com.singularity.agent.threading.watchdog.RegionWatchdog
import com.singularity.agent.registry.SingularityModRegistry
import org.slf4j.LoggerFactory

/**
 * Central orchestrator for the threading engine.
 *
 * ADDENDUM v2: weighted thread allocation (Overworld 50%), uses existing modRegistry.
 */
class ThreadingEngine(
    private val config: ThreadingConfig,
    private val modRegistry: SingularityModRegistry,
    private val regionGroupingHint: RegionGroupingHint = RegionGroupingHint.NONE
) {
    private val logger = LoggerFactory.getLogger(ThreadingEngine::class.java)

    private val poolManager = DimensionPoolManager()
    private val grids = mutableMapOf<String, RegionGrid>()
    private val schedulers = mutableMapOf<String, RegionScheduler>()
    private val tickExecutors = mutableMapOf<String, TickExecutor>()
    private val watchdogs = mutableMapOf<String, RegionWatchdog>()
    private val crossDimensionTransfers = CrossDimensionTransferQueue()
    private var heapMonitor: HeapMonitor? = null
    private var chunkPipeline: ChunkGenerationPipeline<Any>? = null
    private var chunkLoadingPool: ChunkLoadingPool? = null
    private var detectedOptimizations: Set<String> = emptySet()

    @Volatile private var initialized = false

    fun initialize(dimensionIds: List<String>) {
        check(!initialized) { "ThreadingEngine already initialized" }

        // Detect optimization mods
        val detector = OptimizationModDetector(modRegistry)
        val detection = detector.detect()
        detectedOptimizations = buildSet {
            if (detection.sodiumDetected) add("sodium")
            if (detection.c2meDetected) add("c2me")
            if (detection.lithiumDetected) add("lithium")
            if (detection.starlightDetected) add("starlight")
        }
        if (detectedOptimizations.isNotEmpty()) {
            logger.info("Detected optimization mods: {}", detectedOptimizations)
        }
        if (detection.shouldBlockStartup) {
            throw IllegalStateException("Incompatible mod detected: ${detection.blockReason}")
        }

        // Weighted thread allocation — Overworld gets 50%
        val weights = computeWeights(dimensionIds)
        for (dimId in dimensionIds) {
            val threads = (config.totalThreads * weights[dimId]!!).toInt().coerceAtLeast(1)
            val pool = poolManager.createPool(dimId, threads)
            val grid = RegionGrid(dimId, config)
            val scheduler = RegionScheduler(grid, config)
            val executor = TickExecutor(pool, scheduler, config, regionGroupingHint)
            val watchdog = RegionWatchdog(config)

            grids[dimId] = grid
            schedulers[dimId] = scheduler
            tickExecutors[dimId] = executor
            watchdogs[dimId] = watchdog

            logger.info("Dimension '{}' initialized: {} threads (weight {:.0f}%)",
                dimId, threads, weights[dimId]!! * 100)
        }

        // Chunk pipeline
        val chunkThreads = 2.coerceAtMost(config.totalThreads / 4).coerceAtLeast(1)
        chunkPipeline = ChunkGenerationPipeline(chunkThreads)
        chunkLoadingPool = ChunkLoadingPool(1)
        heapMonitor = HeapMonitor(config)

        initialized = true
        logger.info("ThreadingEngine initialized: {} dimensions, {} total threads, {} chunk gen threads",
            dimensionIds.size, config.totalThreads, chunkThreads)
    }

    fun getGrid(dimensionId: String): RegionGrid? = grids[dimensionId]
    fun getTickExecutor(dimensionId: String): TickExecutor? = tickExecutors[dimensionId]
    fun getCrossDimensionTransfers(): CrossDimensionTransferQueue = crossDimensionTransfers
    fun isInitialized(): Boolean = initialized

    fun shutdown() {
        logger.info("Shutting down ThreadingEngine")
        poolManager.shutdownAll()
        chunkPipeline?.shutdown()
        chunkLoadingPool?.shutdown()
        initialized = false
    }

    private fun computeWeights(dimensionIds: List<String>): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        val overworldWeight = 0.5
        val otherWeight = if (dimensionIds.size > 1) 0.5 / (dimensionIds.size - 1) else 1.0
        for (dim in dimensionIds) {
            result[dim] = if (dim == "overworld" || dim == "minecraft:overworld") overworldWeight else otherWeight
        }
        return result
    }
}
