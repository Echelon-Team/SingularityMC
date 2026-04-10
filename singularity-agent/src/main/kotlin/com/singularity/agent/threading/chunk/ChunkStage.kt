package com.singularity.agent.threading.chunk

enum class ChunkStage(val order: Int, val description: String) {
    NOISE(1, "Noise/Density — terrain shape"),
    SURFACE(2, "Surface — biomes, surface rules"),
    CARVERS(3, "Carvers — caves, ravines"),
    FEATURES(4, "Features — trees, ores, structures"),
    LIGHTING(5, "Lighting — light propagation"),
    FULL(6, "Full — vanilla finalize");

    fun next(): ChunkStage? = entries.find { it.order == order + 1 }
    val isFinal: Boolean get() = this == FULL
}
