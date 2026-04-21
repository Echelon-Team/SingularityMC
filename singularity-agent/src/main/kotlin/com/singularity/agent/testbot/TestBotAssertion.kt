// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.testbot

import org.slf4j.LoggerFactory

/**
 * Evaluator asercji po wykonaniu scenariusza.
 *
 * GameState dostarczany przez test harness — w produkcji hookowane do
 * ThreadingEngine/MetricsCollector.
 */
object TestBotAssertion {

    private val logger = LoggerFactory.getLogger(TestBotAssertion::class.java)

    data class GameState(
        val currentTps: Float,
        val hasCrashed: Boolean,
        val hasDeadlock: Boolean,
        val heapUsedMb: Long,
        val entityCount: Int
    )

    data class AssertionResult(
        val spec: TestBotAssertionSpec,
        val passed: Boolean,
        val reason: String
    )

    fun evaluateAll(specs: List<TestBotAssertionSpec>, state: GameState): List<AssertionResult> {
        val results = specs.map { evaluate(it, state) }
        val failed = results.filter { !it.passed }
        if (failed.isNotEmpty()) {
            logger.warn("Assertions failed: {}", failed.map { it.reason })
        }
        return results
    }

    fun evaluate(spec: TestBotAssertionSpec, state: GameState): AssertionResult {
        return when (spec) {
            is TestBotAssertionSpec.TpsAbove -> AssertionResult(
                spec = spec,
                passed = state.currentTps >= spec.min,
                reason = "TPS: ${state.currentTps} (required >= ${spec.min})"
            )
            is TestBotAssertionSpec.NoCrash -> AssertionResult(
                spec = spec,
                passed = !state.hasCrashed,
                reason = if (state.hasCrashed) "Game crashed" else "No crash"
            )
            is TestBotAssertionSpec.NoDeadlock -> AssertionResult(
                spec = spec,
                passed = !state.hasDeadlock,
                reason = if (state.hasDeadlock) "Deadlock detected" else "No deadlock"
            )
            is TestBotAssertionSpec.HeapBelow -> AssertionResult(
                spec = spec,
                passed = state.heapUsedMb < spec.maxMb,
                reason = "Heap: ${state.heapUsedMb} MB (max ${spec.maxMb} MB)"
            )
            is TestBotAssertionSpec.EntityCountAbove -> AssertionResult(
                spec = spec,
                passed = state.entityCount >= spec.min,
                reason = "Entities: ${state.entityCount} (required >= ${spec.min})"
            )
        }
    }
}
