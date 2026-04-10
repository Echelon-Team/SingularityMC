package com.singularity.agent.threading.pool

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class DimensionPoolManager {
    private val logger = LoggerFactory.getLogger(DimensionPoolManager::class.java)
    private val pools = ConcurrentHashMap<String, DimensionPool>()

    fun createPool(dimensionId: String, threadCount: Int): DimensionPool =
        pools.computeIfAbsent(dimensionId) {
            logger.info("Creating dimension pool: {} with {} threads", dimensionId, threadCount)
            DimensionPool(dimensionId, threadCount)
        }

    fun getPool(dimensionId: String): DimensionPool? = pools[dimensionId]

    fun getAllPools(): List<DimensionPool> = pools.values.toList()

    fun shutdownAll() {
        logger.info("Shutting down {} dimension pools", pools.size)
        pools.values.forEach { it.shutdown() }
        pools.clear()
    }
}
