package com.singularity.agent.threading.tick

/**
 * Fazy ticka w threading engine.
 *
 * Design spec 4.4 — 4 fazy z barierami synchronizacji (Phaser) między nimi.
 * ADDENDUM v2: TickBarrier REMOVED — Phaser użyty bezpośrednio w TickExecutor.
 */
enum class TickPhase(val displayName: String) {
    /** Faza 1: Entity ticking (parallel per region). */
    ENTITY_TICKING("Entity Ticking"),

    /** Faza 2: Block updates — random ticks, scheduled ticks, block entity ticks. */
    BLOCK_UPDATES("Block Updates"),

    /** Faza 3: Redstone — sekwencyjnie w połączonych obwodach (RegionGroupingHint). */
    REDSTONE("Redstone"),

    /** Faza 4: World state commit — DEPENDENT sequential, INDEPENDENT parallel. */
    COMMIT("World State Commit");
}
