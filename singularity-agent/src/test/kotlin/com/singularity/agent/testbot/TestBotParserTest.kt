package com.singularity.agent.testbot

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TestBotParserTest {

    @Test
    fun `parse scenario name and timeout`() {
        val yaml = """
            scenario: stress-basic
            timeout: 300s
            actions:
              - wait: 10s
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        assertEquals("stress-basic", scenario.name)
        assertEquals(300, scenario.timeoutSeconds)
    }

    @Test
    fun `parse wait action`() {
        val yaml = """
            scenario: test
            timeout: 10s
            actions:
              - wait: 5s
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        assertEquals(1, scenario.actions.size)
        val wait = scenario.actions[0] as TestBotAction.Wait
        assertEquals(5, wait.seconds)
    }

    @Test
    fun `parse teleport action`() {
        val yaml = """
            scenario: test
            timeout: 10s
            actions:
              - teleport: [100, 64, 200]
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        val tp = scenario.actions[0] as TestBotAction.Teleport
        assertEquals(100.0, tp.x)
        assertEquals(64.0, tp.y)
        assertEquals(200.0, tp.z)
    }

    @Test
    fun `parse summon action`() {
        val yaml = """
            scenario: test
            timeout: 10s
            actions:
              - summon:
                  entity: zombie
                  count: 200
                  at: [0, 64, 0]
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        val summon = scenario.actions[0] as TestBotAction.Summon
        assertEquals("zombie", summon.entity)
        assertEquals(200, summon.count)
        assertEquals(0, summon.atX)
        assertEquals(64, summon.atY)
        assertEquals(0, summon.atZ)
    }

    @Test
    fun `parse place_blocks action`() {
        val yaml = """
            scenario: test
            timeout: 10s
            actions:
              - place_blocks:
                  block: stone
                  area: [10, 10]
                  at: [0, 64, 0]
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        val place = scenario.actions[0] as TestBotAction.PlaceBlocks
        assertEquals("stone", place.block)
        assertEquals(Pair(10, 10), place.area)
        assertEquals(0, place.atX)
    }

    @Test
    fun `parse destroy_blocks action`() {
        val yaml = """
            scenario: test
            timeout: 10s
            actions:
              - destroy_blocks:
                  area: [5, 5]
                  at: [128, 64, 0]
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        val destroy = scenario.actions[0] as TestBotAction.DestroyBlocks
        assertEquals(Pair(5, 5), destroy.area)
        assertEquals(128, destroy.atX)
    }

    @Test
    fun `parse look_around action`() {
        val yaml = """
            scenario: test
            timeout: 10s
            actions:
              - look_around:
                  degrees: 360
                  duration: 5s
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        val look = scenario.actions[0] as TestBotAction.LookAround
        assertEquals(360, look.degrees)
        assertEquals(5, look.durationSeconds)
    }

    @Test
    fun `parse run_direction action`() {
        val yaml = """
            scenario: test
            timeout: 10s
            actions:
              - run_direction:
                  direction: north
                  duration: 30s
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        val run = scenario.actions[0] as TestBotAction.RunDirection
        assertEquals("north", run.direction)
        assertEquals(30, run.durationSeconds)
    }

    @Test
    fun `parse teleport_dimension action`() {
        val yaml = """
            scenario: test
            timeout: 10s
            actions:
              - teleport_dimension: nether
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        val dim = scenario.actions[0] as TestBotAction.TeleportDimension
        assertEquals("nether", dim.dimension)
    }

    @Test
    fun `parse assertions`() {
        val yaml = """
            scenario: test
            timeout: 10s
            actions: []
            assertions:
              - tps_above: 15
              - no_crash: true
              - no_deadlock: true
              - heap_below: 6GB
              - entity_count_above: 500
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        assertEquals(5, scenario.assertions.size)

        val tps = scenario.assertions[0] as TestBotAssertionSpec.TpsAbove
        assertEquals(15, tps.min)

        val noCrash = scenario.assertions[1] as TestBotAssertionSpec.NoCrash
        assertTrue(noCrash.required)

        val noDeadlock = scenario.assertions[2] as TestBotAssertionSpec.NoDeadlock
        assertTrue(noDeadlock.required)

        val heap = scenario.assertions[3] as TestBotAssertionSpec.HeapBelow
        assertEquals(6 * 1024L, heap.maxMb) // 6GB = 6144 MB

        val entities = scenario.assertions[4] as TestBotAssertionSpec.EntityCountAbove
        assertEquals(500, entities.min)
    }

    @Test
    fun `parse multiple actions in sequence`() {
        val yaml = """
            scenario: multi
            timeout: 60s
            actions:
              - wait: 5s
              - teleport: [0, 64, 0]
              - summon:
                  entity: skeleton
                  count: 100
                  at: [10, 64, 10]
              - wait: 10s
              - teleport_dimension: the_end
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        assertEquals(5, scenario.actions.size)
        assertTrue(scenario.actions[0] is TestBotAction.Wait)
        assertTrue(scenario.actions[1] is TestBotAction.Teleport)
        assertTrue(scenario.actions[2] is TestBotAction.Summon)
        assertTrue(scenario.actions[3] is TestBotAction.Wait)
        assertTrue(scenario.actions[4] is TestBotAction.TeleportDimension)
    }

    @Test
    fun `parse custom action falls through to Custom`() {
        val yaml = """
            scenario: test
            timeout: 10s
            actions:
              - my_custom_action:
                  param1: value1
                  param2: 42
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        val custom = scenario.actions[0] as TestBotAction.Custom
        assertEquals("my_custom_action", custom.name)
        assertEquals("value1", custom.args["param1"])
        assertEquals(42, custom.args["param2"])
    }

    @Test
    fun `parseDuration handles seconds, minutes, milliseconds`() {
        assertEquals(30, TestBotParser.parseDuration("30s"))
        assertEquals(300, TestBotParser.parseDuration("5m"))
        assertEquals(1, TestBotParser.parseDuration("500ms")) // rounded up to 1
        assertEquals(10, TestBotParser.parseDuration("10"))
    }

    @Test
    fun `parseSize handles GB and MB`() {
        assertEquals(8192L, TestBotParser.parseSize("8GB"))
        assertEquals(512L, TestBotParser.parseSize("512MB"))
    }

    @Test
    fun `parse scenario with no actions and no assertions`() {
        val yaml = """
            scenario: empty
            timeout: 10s
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        assertEquals("empty", scenario.name)
        assertTrue(scenario.actions.isEmpty())
        assertTrue(scenario.assertions.isEmpty())
    }

    @Test
    fun `parse defaults to unnamed and 60s timeout`() {
        val yaml = """
            actions:
              - wait: 1s
        """.trimIndent()

        val scenario = TestBotParser.parse(yaml)
        assertEquals("unnamed", scenario.name)
        assertEquals(60, scenario.timeoutSeconds)
    }
}
