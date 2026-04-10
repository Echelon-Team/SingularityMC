package com.singularity.agent.threading.region

enum class RegionPriority(val order: Int) {
    HIGH(0),
    MEDIUM(1),
    LOW(2),
    SKIP(3);
}

data class PlayerPosition(val blockX: Int, val blockZ: Int)
