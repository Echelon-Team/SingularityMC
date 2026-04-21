// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.crash

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CrashLogParserTest {

    @Test
    fun `parse basic crash log with NullPointerException`() {
        val log = """
            ---- Minecraft Crash Report ----
            // Uh... Did I do that?

            Time: 2026-04-07 14:32:15
            Description: Unexpected error

            java.lang.NullPointerException: Cannot invoke "net.minecraft.world.level.Level.getBlockState" because "level" is null
                at com.example.MyMod.tick(MyMod.java:42)
                at net.minecraft.world.level.Level.tick(Level.java:123)
                at net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:897)
        """.trimIndent()

        val parsed = CrashLogParser.parse(log)
        assertEquals("java.lang.NullPointerException", parsed.exceptionType)
        assertTrue(parsed.errorMessage.contains("level"))
        assertEquals(3, parsed.stackTrace.size)
        assertTrue(parsed.stackTrace[0].contains("com.example.MyMod.tick"))
        assertEquals("com.example.MyMod.tick(MyMod.java:42)", parsed.topStackFrame)
        assertEquals("2026-04-07 14:32:15", parsed.time)
        assertEquals("Unexpected error", parsed.description)
    }

    @Test
    fun `parse OutOfMemoryError`() {
        val log = """
            ---- Minecraft Crash Report ----
            Description: Loading error

            java.lang.OutOfMemoryError: Java heap space
                at java.util.Arrays.copyOf(Arrays.java:3213)
        """.trimIndent()

        val parsed = CrashLogParser.parse(log)
        assertEquals("java.lang.OutOfMemoryError", parsed.exceptionType)
        assertTrue(parsed.errorMessage.contains("heap space"))
    }

    @Test
    fun `identifies top stack frame`() {
        val log = """
            Description: Test

            java.lang.RuntimeException: boom
                at com.create.Create.init(Create.java:10)
                at net.minecraft.Main.run(Main.java:20)
        """.trimIndent()

        val parsed = CrashLogParser.parse(log)
        assertEquals("com.create.Create.init(Create.java:10)", parsed.topStackFrame)
    }

    @Test
    fun `extracts time if present`() {
        val log = """
            Time: 2026-04-07 14:32:15
            Description: Test

            java.lang.Exception: oops
                at X.y(X.java:1)
        """.trimIndent()

        val parsed = CrashLogParser.parse(log)
        assertEquals("2026-04-07 14:32:15", parsed.time)
    }

    @Test
    fun `handles empty log gracefully`() {
        val parsed = CrashLogParser.parse("")
        assertEquals("unknown", parsed.exceptionType)
        assertTrue(parsed.stackTrace.isEmpty())
        assertNull(parsed.topStackFrame)
    }

    @Test
    fun `parse StackOverflowError`() {
        val log = """
            Description: Ticking entity

            java.lang.StackOverflowError
                at com.mod.RecursiveThing.call(RecursiveThing.java:15)
                at com.mod.RecursiveThing.call(RecursiveThing.java:15)
                at com.mod.RecursiveThing.call(RecursiveThing.java:15)
        """.trimIndent()

        val parsed = CrashLogParser.parse(log)
        assertEquals("java.lang.StackOverflowError", parsed.exceptionType)
        assertEquals(3, parsed.stackTrace.size)
    }

    @Test
    fun `parse ConcurrentModificationException`() {
        val log = """
            java.util.ConcurrentModificationException
                at java.util.ArrayList${'$'}Itr.checkForComodification(ArrayList.java:1013)
                at java.util.ArrayList${'$'}Itr.next(ArrayList.java:967)
        """.trimIndent()

        val parsed = CrashLogParser.parse(log)
        assertEquals("java.util.ConcurrentModificationException", parsed.exceptionType)
        assertTrue(parsed.errorMessage.isEmpty())
    }

    @Test
    fun `parse exception without message`() {
        val log = """
            java.lang.NullPointerException
                at com.mod.Thing.doIt(Thing.java:5)
        """.trimIndent()

        val parsed = CrashLogParser.parse(log)
        assertEquals("java.lang.NullPointerException", parsed.exceptionType)
        assertTrue(parsed.errorMessage.isEmpty())
    }
}
