package com.singularity.agent.testbot

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Parser YAML scenariuszy test bota.
 *
 * Format:
 *   scenario: stress-basic
 *   timeout: 300s
 *   actions:
 *     - wait: 10s
 *     - teleport: [0, 64, 0]
 *     - summon:
 *         entity: zombie
 *         count: 200
 *         at: [0, 64, 0]
 *   assertions:
 *     - tps_above: 15
 *     - no_crash: true
 *     - heap_below: 6GB
 */
object TestBotParser {

    private val logger = LoggerFactory.getLogger(TestBotParser::class.java)

    fun parse(yamlContent: String): TestBotScenario {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        @Suppress("UNCHECKED_CAST")
        val root = yaml.load<Map<String, Any>>(yamlContent)

        val name = root["scenario"] as? String ?: "unnamed"
        val timeout = parseDuration(root["timeout"]?.toString() ?: "60s")
        val actions = parseActions(root["actions"] as? List<*> ?: emptyList<Any>())
        val assertions = parseAssertions(root["assertions"] as? List<*> ?: emptyList<Any>())

        return TestBotScenario(name, timeout, actions, assertions)
    }

    private fun parseActions(list: List<*>): List<TestBotAction> {
        return list.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> parseAction(item)
                else -> null
            }
        }
    }

    private fun parseAction(map: Map<*, *>): TestBotAction? {
        val entry = map.entries.firstOrNull() ?: return null
        val key = entry.key as? String ?: return null
        val value = entry.value

        return when (key) {
            "wait" -> TestBotAction.Wait(parseDuration(value.toString()))
            "teleport" -> {
                val coords = value as? List<*> ?: return null
                if (coords.size < 3) return null
                TestBotAction.Teleport(
                    (coords[0] as? Number)?.toDouble() ?: return null,
                    (coords[1] as? Number)?.toDouble() ?: return null,
                    (coords[2] as? Number)?.toDouble() ?: return null
                )
            }
            "look_around" -> {
                val params = value as? Map<*, *> ?: return null
                TestBotAction.LookAround(
                    (params["degrees"] as? Number)?.toInt() ?: 360,
                    parseDuration(params["duration"]?.toString() ?: "5s")
                )
            }
            "run_direction" -> {
                val params = value as? Map<*, *> ?: return null
                TestBotAction.RunDirection(
                    params["direction"] as? String ?: "north",
                    parseDuration(params["duration"]?.toString() ?: "10s")
                )
            }
            "summon" -> {
                val params = value as? Map<*, *> ?: return null
                val at = params["at"] as? List<*> ?: listOf(0, 64, 0)
                TestBotAction.Summon(
                    entity = params["entity"] as? String ?: "zombie",
                    count = (params["count"] as? Number)?.toInt() ?: 1,
                    atX = (at[0] as Number).toInt(),
                    atY = (at[1] as Number).toInt(),
                    atZ = (at[2] as Number).toInt()
                )
            }
            "teleport_dimension" -> TestBotAction.TeleportDimension(value as String)
            "place_blocks" -> {
                val params = value as? Map<*, *> ?: return null
                val area = params["area"] as? List<*> ?: listOf(1, 1)
                val at = params["at"] as? List<*> ?: listOf(0, 64, 0)
                TestBotAction.PlaceBlocks(
                    block = params["block"] as? String ?: "stone",
                    area = Pair((area[0] as Number).toInt(), (area[1] as Number).toInt()),
                    atX = (at[0] as Number).toInt(),
                    atY = (at[1] as Number).toInt(),
                    atZ = (at[2] as Number).toInt()
                )
            }
            "destroy_blocks" -> {
                val params = value as? Map<*, *> ?: return null
                val area = params["area"] as? List<*> ?: listOf(1, 1)
                val at = params["at"] as? List<*> ?: listOf(0, 64, 0)
                TestBotAction.DestroyBlocks(
                    area = Pair((area[0] as Number).toInt(), (area[1] as Number).toInt()),
                    atX = (at[0] as Number).toInt(),
                    atY = (at[1] as Number).toInt(),
                    atZ = (at[2] as Number).toInt()
                )
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                TestBotAction.Custom(
                    key,
                    (value as? Map<*, *>)?.mapKeys { it.key.toString() }
                        ?.mapValues { it.value ?: Unit } ?: emptyMap()
                )
            }
        }
    }

    private fun parseAssertions(list: List<*>): List<TestBotAssertionSpec> {
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val entry = map.entries.firstOrNull() ?: return@mapNotNull null
            val key = entry.key as? String ?: return@mapNotNull null
            val value = entry.value

            when (key) {
                "tps_above" -> (value as? Number)?.toInt()?.let { TestBotAssertionSpec.TpsAbove(it) }
                "no_crash" -> (value as? Boolean)?.let { TestBotAssertionSpec.NoCrash(it) }
                "no_deadlock" -> (value as? Boolean)?.let { TestBotAssertionSpec.NoDeadlock(it) }
                "heap_below" -> TestBotAssertionSpec.HeapBelow(parseSize(value.toString()))
                "entity_count_above" -> (value as? Number)?.toInt()?.let { TestBotAssertionSpec.EntityCountAbove(it) }
                else -> null
            }
        }
    }

    internal fun parseDuration(str: String): Int {
        val trimmed = str.trim().lowercase()
        return when {
            trimmed.endsWith("ms") -> (trimmed.removeSuffix("ms").toInt() / 1000).coerceAtLeast(1)
            trimmed.endsWith("s") -> trimmed.removeSuffix("s").toInt()
            trimmed.endsWith("m") -> trimmed.removeSuffix("m").toInt() * 60
            else -> trimmed.toIntOrNull() ?: 0
        }
    }

    internal fun parseSize(str: String): Long {
        val trimmed = str.trim().uppercase()
        return when {
            trimmed.endsWith("GB") -> trimmed.removeSuffix("GB").toLong() * 1024
            trimmed.endsWith("MB") -> trimmed.removeSuffix("MB").toLong()
            trimmed.endsWith("KB") -> trimmed.removeSuffix("KB").toLong() / 1024
            else -> trimmed.toLongOrNull() ?: 0
        }
    }
}
