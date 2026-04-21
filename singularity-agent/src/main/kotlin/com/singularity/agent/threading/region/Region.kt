// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.region

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Reprezentacja pojedynczego regionu w wymiarze.
 *
 * Region ma zawsze dokładnie jednego właściciela wątkowego w danym ticku.
 * Wewnątrz regionu encje tickowane sekwencyjnie (vanilla ordering).
 *
 * ADDENDUM v2 errata:
 * - scheduledTickCount: regiony z aktywnymi redstone clockami tickują nawet bez encji
 * - shouldAbort: cooperative cancellation flag for stuck region recovery
 *
 * Referencja: design spec sekcja 4.3.
 */
class Region(
    val id: RegionId,
    val dimensionId: String
) {
    /** Liczba encji aktualnie w regionie (atomic — updatowane z wielu miejsc). */
    private val entityCount = AtomicInteger(0)

    /**
     * Liczba aktywnych scheduled ticks (redstone repeaters, observers, etc.).
     * Region z scheduledTickCount > 0 tickuje nawet bez encji.
     * ADDENDUM v2 errata: empty region fix.
     */
    private val scheduledTickCount = AtomicInteger(0)

    /** Numer ticka w którym region był ostatnio przetwarzany. */
    private val lastTick = AtomicLong(-1)

    /** Wątek będący aktualnym właścicielem regionu (null gdy nieaktywny). */
    private val currentOwner = AtomicReference<Thread?>(null)

    /** Stan regionu w obecnym ticku. */
    enum class State {
        IDLE,
        QUEUED,
        PROCESSING,
        COMPLETED,
        STUCK
    }

    private val state = AtomicReference(State.IDLE)

    /**
     * Cooperative abort flag — set by watchdog, checked at safe points in tick loop.
     * ADDENDUM v2 errata: two-stage recovery (cooperative → hard interrupt).
     */
    @Volatile
    var shouldAbort: Boolean = false

    // --- Entity count ---
    fun getEntityCount(): Int = entityCount.get()
    fun setEntityCount(count: Int) = entityCount.set(count)
    fun incrementEntityCount(): Int = entityCount.incrementAndGet()
    fun decrementEntityCount(): Int = entityCount.decrementAndGet()

    // --- Scheduled tick count ---
    fun getScheduledTickCount(): Int = scheduledTickCount.get()
    fun addScheduledTick() { scheduledTickCount.incrementAndGet() }
    fun removeScheduledTick() { scheduledTickCount.updateAndGet { maxOf(it - 1, 0) } }

    // --- State ---
    fun getState(): State = state.get()
    fun setState(newState: State) = state.set(newState)
    fun compareAndSetState(expected: State, newState: State): Boolean =
        state.compareAndSet(expected, newState)

    // --- Tick ---
    fun getLastTick(): Long = lastTick.get()
    fun setLastTick(tick: Long) = lastTick.set(tick)

    // --- Ownership ---
    fun getOwner(): Thread? = currentOwner.get()
    fun claimOwnership(thread: Thread): Boolean =
        currentOwner.compareAndSet(null, thread)
    fun releaseOwnership(thread: Thread): Boolean =
        currentOwner.compareAndSet(thread, null)

    /**
     * Czy region kwalifikuje się do ticka?
     * True gdy: ma encje LUB ma aktywne scheduled ticks (redstone clocks).
     */
    fun isActive(): Boolean = entityCount.get() > 0 || scheduledTickCount.get() > 0

    override fun toString(): String =
        "Region($dimensionId@${id.x},${id.z}, entities=${entityCount.get()}, scheduled=${scheduledTickCount.get()}, state=${state.get()})"
}
