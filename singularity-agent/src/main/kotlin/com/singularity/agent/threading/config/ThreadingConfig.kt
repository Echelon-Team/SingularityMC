package com.singularity.agent.threading.config

/**
 * Konfiguracja threading engine.
 *
 * Wartości domyślne zgodne z design spec sekcja 4.
 * Gracz może nadpisać w ustawieniach instancji.
 *
 * Architektura: server-ready — nie hardcoduj założeń single-player.
 * Threading engine musi skalować na 32+ wątków (przyszły server engine).
 */
data class ThreadingConfig(
    /** Rozmiar regionu w chunkach (4, 6, 8, 12, 16). Default: 8 (128 bloków). */
    val regionSizeChunks: Int = 8,

    /** Wątki do dimension pools (game tick). Chunk gen/IO adds ~2-3 more on top. Min 4. */
    val totalThreads: Int = 8,

    /** Timeout bariery synchronizacji w ms (design spec 4.5C — default 3000). */
    val barrierTimeoutMs: Long = 3000,

    /** Próg heap usage dla aggressive chunk unloading (design spec 4.8.5 — 80%). */
    val heapAggressiveUnloadThreshold: Double = 0.80,

    /** Próg heap usage dla pauzowania pre-generation (design spec 4.8.5 — 90%). */
    val heapPauseGenerationThreshold: Double = 0.90,

    /** Liczba ticków fallback po stuck region (design spec 4.5C — base 20 = 1s). */
    val stuckFallbackTicks: Int = 20,

    /** Max liczba ticków fallback (exponential backoff — design spec 4.5C — 600 = 30s). */
    val stuckFallbackMaxTicks: Int = 600,

    /** Próg obciążenia dla split regionu (liczba encji). */
    val splitLoadThreshold: Int = 500,

    /** Liczba ticków powyżej progu przed split (histereza). */
    val splitHysteresisTicks: Int = 40,

    /** Próg obciążenia dla merge sub-regionów. */
    val mergeLoadThreshold: Int = 100,

    /** Liczba ticków poniżej progu przed merge (histereza — dłużej niż split). */
    val mergeHysteresisTicks: Int = 200,

    /** Pre-alokowany rozmiar message queue między parą sąsiednich regionów. */
    val messageQueueInitialCapacity: Int = 256,

    /** Częstotliwość heap check w ticks. */
    val heapCheckIntervalTicks: Int = 20,

    /** Rozmiar snapshot pool — ile buforów trzymać dla reuse. 128 for ~50 active regions. */
    val snapshotPoolSize: Int = 128
) {
    /** Rozmiar regionu w blokach (chunki × 16). */
    val regionSizeBlocks: Int get() = regionSizeChunks * 16

    /** Bit shift do konwersji block coords → region coords (log2 regionSizeBlocks). */
    val regionShift: Int get() = Integer.numberOfTrailingZeros(regionSizeBlocks)

    init {
        require(regionSizeChunks in ALLOWED_REGION_SIZES) {
            "regionSizeChunks must be one of $ALLOWED_REGION_SIZES, got $regionSizeChunks"
        }
        require(totalThreads >= 4) {
            "totalThreads must be >= 4 (minimum viable), got $totalThreads"
        }
        require(heapAggressiveUnloadThreshold < heapPauseGenerationThreshold) {
            "heapAggressiveUnloadThreshold ($heapAggressiveUnloadThreshold) must be < heapPauseGenerationThreshold ($heapPauseGenerationThreshold)"
        }
        require(barrierTimeoutMs > 0) {
            "barrierTimeoutMs must be positive"
        }
        require(splitLoadThreshold > mergeLoadThreshold) {
            "splitLoadThreshold must be > mergeLoadThreshold for hysteresis"
        }
    }

    companion object {
        /** Power-of-2 only — enables bit-shift optimization for block→region coord mapping. */
        val ALLOWED_REGION_SIZES = setOf(4, 8, 16)
    }
}
