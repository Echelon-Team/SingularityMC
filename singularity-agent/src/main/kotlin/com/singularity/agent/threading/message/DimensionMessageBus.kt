package com.singularity.agent.threading.message

import com.singularity.agent.threading.region.RegionId
import java.util.concurrent.ConcurrentHashMap

/**
 * Message bus per dimension — aggregates cross-region messages.
 *
 * ADDENDUM v2: +migrateOnSplit() for message redistribution after region split.
 */
class DimensionMessageBus {
    private val queues = ConcurrentHashMap<RegionId, RegionMessageQueue>()

    fun send(message: RegionMessage) {
        getOrCreateQueue(message.targetRegion).enqueue(message)
    }

    fun drainQueueFor(target: RegionId): List<RegionMessage> =
        queues[target]?.drainAvailable() ?: emptyList()

    fun pendingMessageCount(): Int = queues.values.sumOf { it.size() }

    fun clear() { queues.clear() }

    fun getOrCreateQueue(regionId: RegionId): RegionMessageQueue =
        queues.computeIfAbsent(regionId) { RegionMessageQueue() }

    /**
     * Migrate messages from oldRegionId to new sub-region IDs after split.
     *
     * THREAD SAFETY CONTRACT: Caller (RegionSplitMerge) MUST ensure no concurrent
     * send() targets oldRegionId during migration. This is enforced by the split
     * happening between ticks (not during parallel phase). If violated, messages
     * sent to oldRegionId during migration are orphaned in a new queue.
     *
     * regionResolver determines which new region each message should go to.
     * If resolver returns a regionId not in newRegionIds, message is routed there
     * anyway (getOrCreateQueue creates it) — caller should validate if needed.
     */
    fun migrateOnSplit(
        oldRegionId: RegionId,
        newRegionIds: Set<RegionId>,
        regionResolver: (RegionMessage) -> RegionId
    ) {
        val oldQueue = queues.remove(oldRegionId) ?: return
        val messages = oldQueue.drainAvailable()
        for (msg in messages) {
            val targetRegion = regionResolver(msg)
            getOrCreateQueue(targetRegion).enqueue(msg)
        }
    }
}
