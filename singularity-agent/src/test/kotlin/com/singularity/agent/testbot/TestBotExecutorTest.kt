package com.singularity.agent.testbot

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TestBotExecutorTest {

    @Test
    fun `execute runs all actions and reports success`() {
        val executed = mutableListOf<String>()
        val executor = TestBotExecutor { action ->
            executed.add(action::class.simpleName!!)
        }

        val scenario = TestBotScenario(
            name = "test",
            timeoutSeconds = 60,
            actions = listOf(
                TestBotAction.Teleport(0.0, 64.0, 0.0),
                TestBotAction.Summon("zombie", 10, 0, 64, 0)
            ),
            assertions = emptyList()
        )

        val result = executor.execute(scenario)
        assertTrue(result.success)
        assertEquals(2, result.actionsExecuted)
        assertEquals(2, result.actionsTotal)
        assertEquals(0, result.failures.size)
        assertEquals(listOf("Teleport", "Summon"), executed)
    }

    @Test
    fun `execute catches action handler exceptions`() {
        val executor = TestBotExecutor { action ->
            if (action is TestBotAction.Summon) throw RuntimeException("spawn failed")
        }

        val scenario = TestBotScenario(
            name = "fail-test",
            timeoutSeconds = 60,
            actions = listOf(
                TestBotAction.Teleport(0.0, 64.0, 0.0),
                TestBotAction.Summon("zombie", 10, 0, 64, 0),
                TestBotAction.Teleport(100.0, 64.0, 100.0)
            ),
            assertions = emptyList()
        )

        val result = executor.execute(scenario)
        assertFalse(result.success)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures[0].contains("spawn failed"))
        // Third action should still execute after caught exception
        assertEquals(2, result.actionsExecuted) // teleport1 + teleport2 (summon failed but counted)
    }

    @Test
    fun `execute respects timeout`() {
        val executor = TestBotExecutor { }

        val scenario = TestBotScenario(
            name = "timeout-test",
            timeoutSeconds = 1,
            actions = listOf(
                TestBotAction.Wait(2), // 2 seconds > 1 second timeout
                TestBotAction.Teleport(0.0, 64.0, 0.0) // should not be reached
            ),
            assertions = emptyList()
        )

        val result = executor.execute(scenario)
        assertFalse(result.success)
        assertTrue(result.failures.any { it.contains("Timeout") })
    }

    @Test
    fun `execute empty scenario succeeds`() {
        val executor = TestBotExecutor { }

        val scenario = TestBotScenario(
            name = "empty",
            timeoutSeconds = 10,
            actions = emptyList(),
            assertions = emptyList()
        )

        val result = executor.execute(scenario)
        assertTrue(result.success)
        assertEquals(0, result.actionsExecuted)
        assertEquals(0, result.actionsTotal)
        assertTrue(result.durationMs < 1000)
    }

    @Test
    fun `execute reports scenario name and duration`() {
        val executor = TestBotExecutor { }

        val scenario = TestBotScenario(
            name = "named-scenario",
            timeoutSeconds = 10,
            actions = listOf(TestBotAction.Teleport(0.0, 0.0, 0.0)),
            assertions = emptyList()
        )

        val result = executor.execute(scenario)
        assertEquals("named-scenario", result.scenarioName)
        assertTrue(result.durationMs >= 0)
    }
}

class TestBotAssertionTest {

    private fun state(tps: Float = 20f, crashed: Boolean = false, deadlock: Boolean = false,
                      heapMb: Long = 2048, entities: Int = 100) =
        TestBotAssertion.GameState(tps, crashed, deadlock, heapMb, entities)

    @Test
    fun `TpsAbove passes when TPS exceeds minimum`() {
        val result = TestBotAssertion.evaluate(TestBotAssertionSpec.TpsAbove(15), state(tps = 19.5f))
        assertTrue(result.passed)
        assertTrue(result.reason.contains("19.5"))
    }

    @Test
    fun `TpsAbove fails when TPS below minimum`() {
        val result = TestBotAssertion.evaluate(TestBotAssertionSpec.TpsAbove(15), state(tps = 12.0f))
        assertFalse(result.passed)
    }

    @Test
    fun `NoCrash passes when no crash`() {
        val result = TestBotAssertion.evaluate(TestBotAssertionSpec.NoCrash(true), state(crashed = false))
        assertTrue(result.passed)
        assertEquals("No crash", result.reason)
    }

    @Test
    fun `NoCrash fails when crashed`() {
        val result = TestBotAssertion.evaluate(TestBotAssertionSpec.NoCrash(true), state(crashed = true))
        assertFalse(result.passed)
        assertEquals("Game crashed", result.reason)
    }

    @Test
    fun `NoDeadlock passes when no deadlock`() {
        val result = TestBotAssertion.evaluate(TestBotAssertionSpec.NoDeadlock(true), state(deadlock = false))
        assertTrue(result.passed)
    }

    @Test
    fun `NoDeadlock fails when deadlocked`() {
        val result = TestBotAssertion.evaluate(TestBotAssertionSpec.NoDeadlock(true), state(deadlock = true))
        assertFalse(result.passed)
    }

    @Test
    fun `HeapBelow passes when under limit`() {
        val result = TestBotAssertion.evaluate(TestBotAssertionSpec.HeapBelow(4096), state(heapMb = 2048))
        assertTrue(result.passed)
    }

    @Test
    fun `HeapBelow fails when over limit`() {
        val result = TestBotAssertion.evaluate(TestBotAssertionSpec.HeapBelow(2048), state(heapMb = 3000))
        assertFalse(result.passed)
    }

    @Test
    fun `EntityCountAbove passes when enough entities`() {
        val result = TestBotAssertion.evaluate(TestBotAssertionSpec.EntityCountAbove(50), state(entities = 100))
        assertTrue(result.passed)
    }

    @Test
    fun `EntityCountAbove fails when too few entities`() {
        val result = TestBotAssertion.evaluate(TestBotAssertionSpec.EntityCountAbove(200), state(entities = 100))
        assertFalse(result.passed)
    }

    @Test
    fun `evaluateAll returns results for all specs`() {
        val specs = listOf(
            TestBotAssertionSpec.TpsAbove(15),
            TestBotAssertionSpec.NoCrash(true),
            TestBotAssertionSpec.HeapBelow(8192)
        )
        val results = TestBotAssertion.evaluateAll(specs, state(tps = 18f, crashed = false, heapMb = 4000))
        assertEquals(3, results.size)
        assertTrue(results.all { it.passed })
    }

    @Test
    fun `evaluateAll reports mixed pass-fail`() {
        val specs = listOf(
            TestBotAssertionSpec.TpsAbove(15),
            TestBotAssertionSpec.NoCrash(true)
        )
        val results = TestBotAssertion.evaluateAll(specs, state(tps = 18f, crashed = true))
        assertEquals(2, results.size)
        assertTrue(results[0].passed) // TPS OK
        assertFalse(results[1].passed) // crashed
    }
}
