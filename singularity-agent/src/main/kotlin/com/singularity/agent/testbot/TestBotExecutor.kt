package com.singularity.agent.testbot

import org.slf4j.LoggerFactory

/**
 * Wykonuje scenariusz test bota.
 *
 * actionHandler — callback per akcja. W runtime: hookowany do MC entity/world API
 * przez compat module. Tutaj: interface pozwalający testować bez MC.
 */
class TestBotExecutor(
    private val actionHandler: (TestBotAction) -> Unit
) {
    private val logger = LoggerFactory.getLogger(TestBotExecutor::class.java)

    data class ExecutionResult(
        val scenarioName: String,
        val success: Boolean,
        val failures: List<String>,
        val durationMs: Long,
        val actionsExecuted: Int,
        val actionsTotal: Int
    )

    fun execute(scenario: TestBotScenario): ExecutionResult {
        logger.info("Starting scenario: {} (timeout: {}s, {} actions)",
            scenario.name, scenario.timeoutSeconds, scenario.actions.size)
        val startTime = System.currentTimeMillis()
        val failures = mutableListOf<String>()
        var actionsExecuted = 0

        for ((index, action) in scenario.actions.withIndex()) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            if (elapsed > scenario.timeoutSeconds) {
                failures.add("Timeout exceeded at action ${index + 1}/${scenario.actions.size} after ${elapsed}s")
                break
            }

            try {
                logger.info("Executing action {}/{}: {}",
                    index + 1, scenario.actions.size, action::class.simpleName)
                actionHandler(action)
                actionsExecuted++

                if (action is TestBotAction.Wait) {
                    Thread.sleep(action.seconds * 1000L)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                failures.add("Action ${index + 1} interrupted")
                break
            } catch (e: Exception) {
                logger.error("Action {} failed: {}", index + 1, e.message, e)
                failures.add("Action ${index + 1} (${action::class.simpleName}) failed: ${e.message}")
            }
        }

        val durationMs = System.currentTimeMillis() - startTime
        logger.info("Scenario '{}' completed in {}ms — {}/{} actions, {} failures",
            scenario.name, durationMs, actionsExecuted, scenario.actions.size, failures.size)

        return ExecutionResult(
            scenarioName = scenario.name,
            success = failures.isEmpty(),
            failures = failures,
            durationMs = durationMs,
            actionsExecuted = actionsExecuted,
            actionsTotal = scenario.actions.size
        )
    }
}
