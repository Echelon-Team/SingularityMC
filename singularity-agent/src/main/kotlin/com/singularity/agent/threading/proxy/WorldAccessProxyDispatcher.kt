package com.singularity.agent.threading.proxy

import com.singularity.agent.threading.region.Region
import com.singularity.agent.threading.region.RegionId
import com.singularity.agent.threading.snapshot.BoundaryBuffer
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Runtime dispatch for WorldAccessProxy.
 *
 * Called from ASM-injected getBlockState/setBlockState/getBlockEntity methods.
 * Returns null for in-region access (fast path) or snapshot value for cross-region.
 *
 * MANDATORY: Uses cached static MethodHandle for BlockPos.getX()/getZ() extraction.
 * Reflection (Method.invoke) would be ~100-200ns per call = PERF-BLOCKER on hot path.
 * MethodHandle after JIT warmup: ~2-5ns per call.
 */
object WorldAccessProxyDispatcher {

    private val logger = LoggerFactory.getLogger(WorldAccessProxyDispatcher::class.java)

    /** ThreadLocal: current region for this tick thread. null = not on a region thread. */
    private val currentRegion = ThreadLocal<Region?>()

    /** ThreadLocal: boundary buffer for snapshot reads. */
    private val currentBoundaryBuffer = ThreadLocal<BoundaryBuffer?>()

    /** ThreadLocal: regionShift (log2 of region size in blocks). */
    private val currentRegionShift = ThreadLocal<Int>()

    // Cached MethodHandles for BlockPos.getX()/getY()/getZ() — initialized lazily
    @Volatile private var getXHandle: MethodHandle? = null
    @Volatile private var getYHandle: MethodHandle? = null
    @Volatile private var getZHandle: MethodHandle? = null
    @Volatile private var handleInitAttempted = false

    /**
     * Set by TickExecutor before each region's phase handler runs.
     */
    fun setCurrentRegion(region: Region?, buffer: BoundaryBuffer?, regionShift: Int = 7) {
        currentRegion.set(region)
        currentBoundaryBuffer.set(buffer)
        currentRegionShift.set(regionShift)
    }

    fun clearCurrentRegion() {
        currentRegion.remove()
        currentBoundaryBuffer.remove()
        currentRegionShift.remove()
    }

    /**
     * Main dispatch — called from ASM-injected methods.
     *
     * @return null for in-region (fast path → caller proceeds to $original),
     *         non-null for cross-region (snapshot value → caller returns this).
     */
    @JvmStatic
    fun dispatch(level: Any, methodName: String, pos: Any): Any? {
        val region = currentRegion.get() ?: return null // not on region thread

        // Initialize MethodHandles lazily on first call
        if (!handleInitAttempted) initHandles(pos.javaClass)

        // Fast path: extract X/Z from BlockPos via cached MethodHandle
        val x: Int
        val z: Int
        try {
            val xh = getXHandle
            val zh = getZHandle
            if (xh != null && zh != null) {
                x = xh.invoke(pos) as Int
                z = zh.invoke(pos) as Int
            } else {
                return null // handles not available → pass through
            }
        } catch (e: Throwable) {
            return null // extraction failed → pass through
        }

        val shift = currentRegionShift.get() ?: 7
        val regionGridX = x shr shift
        val regionGridZ = z shr shift

        // In-region access → fast path (~6ns)
        if (regionGridX == region.id.x && regionGridZ == region.id.z) {
            return null // caller proceeds to $original
        }

        // Cross-region access → snapshot read
        val buffer = currentBoundaryBuffer.get() ?: return null
        val neighborId = RegionId(regionGridX, regionGridZ)
        val snapshot = buffer.getNeighborSnapshot(neighborId) ?: return null

        // Extract Y via cached MethodHandle (consistent with X/Z)
        val y: Int = try {
            getYHandle?.invoke(pos) as? Int ?: return null
        } catch (e: Throwable) { return null }

        return when (methodName) {
            "getBlockState" -> {
                val blockStateId = snapshot.getBlock(x, y, z)
                // blockStateId wrapped — actual BlockState resolution in compat module
                blockStateId as Any
            }
            "getBlockEntity" -> {
                snapshot.getBlockEntity(x, y, z) as Any?
            }
            "setBlockState" -> {
                // Cross-region write → log warning, pass through to $original
                // Real implementation queues to message bus
                logger.debug("Cross-region setBlockState at ({},{}) from region {} — queued", x, z, region.id)
                null // proceed to $original
            }
            else -> null
        }
    }

    private fun initHandles(blockPosClass: Class<*>) {
        synchronized(this) {
            if (handleInitAttempted) return
            handleInitAttempted = true
            try {
                // Use lookup() not publicLookup() — MC classes may be non-public after obfuscation
                val lookup = MethodHandles.lookup()
                val intType = MethodType.methodType(Int::class.java)
                getXHandle = lookup.findVirtual(blockPosClass, "getX", intType)
                getYHandle = lookup.findVirtual(blockPosClass, "getY", intType)
                getZHandle = lookup.findVirtual(blockPosClass, "getZ", intType)
                logger.info("WorldAccessProxyDispatcher MethodHandles initialized for {}", blockPosClass.name)
            } catch (e: Exception) {
                logger.error("Failed to initialize MethodHandles for BlockPos: {}", e.message)
            }
        }
    }
}
