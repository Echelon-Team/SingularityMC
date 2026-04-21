// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.testbot

/**
 * Scenariusz test bota — lista akcji + asercje.
 * Dev-infrastructure sekcja 1.7.
 */
data class TestBotScenario(
    val name: String,
    val timeoutSeconds: Int,
    val actions: List<TestBotAction>,
    val assertions: List<TestBotAssertionSpec>
)

sealed class TestBotAction {
    data class Wait(val seconds: Int) : TestBotAction()
    data class Teleport(val x: Double, val y: Double, val z: Double) : TestBotAction()
    data class LookAround(val degrees: Int, val durationSeconds: Int) : TestBotAction()
    data class RunDirection(val direction: String, val durationSeconds: Int) : TestBotAction()
    data class PlaceBlocks(val block: String, val area: Pair<Int, Int>, val atX: Int, val atY: Int, val atZ: Int) : TestBotAction()
    data class DestroyBlocks(val area: Pair<Int, Int>, val atX: Int, val atY: Int, val atZ: Int) : TestBotAction()
    data class Summon(val entity: String, val count: Int, val atX: Int, val atY: Int, val atZ: Int) : TestBotAction()
    data class TeleportDimension(val dimension: String) : TestBotAction()
    data class Custom(val name: String, val args: Map<String, Any>) : TestBotAction()
}

sealed class TestBotAssertionSpec {
    data class TpsAbove(val min: Int) : TestBotAssertionSpec()
    data class NoCrash(val required: Boolean) : TestBotAssertionSpec()
    data class NoDeadlock(val required: Boolean) : TestBotAssertionSpec()
    data class HeapBelow(val maxMb: Long) : TestBotAssertionSpec()
    data class EntityCountAbove(val min: Int) : TestBotAssertionSpec()
}
