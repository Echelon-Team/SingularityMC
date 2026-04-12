package com.singularity.launcher.crash

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CrashPatternMatcherTest {

    private val matcher = CrashPatternMatcher()

    private fun parse(exception: String, msg: String, vararg stack: String) =
        CrashLogParser.ParsedCrash(exception, msg, stack.toList(), stack.firstOrNull(), null, null)

    @Test
    fun `categorizes OOM as OUT_OF_MEMORY`() {
        val parsed = parse("java.lang.OutOfMemoryError", "Java heap space",
            "java.util.Arrays.copyOf(Arrays.java:3213)")
        assertEquals(CrashPatternMatcher.CrashCategory.OUT_OF_MEMORY, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes ClassNotFoundException as MISSING_DEPENDENCY`() {
        val parsed = parse("java.lang.ClassNotFoundException", "com.example.SomeClass",
            "java.lang.ClassLoader.loadClass(ClassLoader.java:1)")
        assertEquals(CrashPatternMatcher.CrashCategory.MISSING_DEPENDENCY, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes NoClassDefFoundError as MISSING_DEPENDENCY`() {
        val parsed = parse("java.lang.NoClassDefFoundError", "com/example/Missing",
            "com.mod.MyMod.init(MyMod.java:1)")
        assertEquals(CrashPatternMatcher.CrashCategory.MISSING_DEPENDENCY, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes ConcurrentModificationException as THREADING`() {
        val parsed = parse("java.util.ConcurrentModificationException", "",
            "java.util.ArrayList.next(ArrayList.java:1)")
        assertEquals(CrashPatternMatcher.CrashCategory.THREADING, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes StackOverflowError as INFINITE_RECURSION`() {
        val parsed = parse("java.lang.StackOverflowError", "",
            "com.mod.Recursive.call(Recursive.java:10)")
        assertEquals(CrashPatternMatcher.CrashCategory.INFINITE_RECURSION, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes ClassCastException as CLASS_CAST_MISMATCH`() {
        val parsed = parse("java.lang.ClassCastException", "cannot cast",
            "com.mod.Thing.init(Thing.java:5)")
        assertEquals(CrashPatternMatcher.CrashCategory.CLASS_CAST_MISMATCH, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes crash in SingularityMC code as SINGULARITY_BUG`() {
        val parsed = parse("java.lang.IllegalStateException", "invalid",
            "com.singularity.agent.threading.TickExecutor.execute(TickExecutor.kt:50)")
        assertEquals(CrashPatternMatcher.CrashCategory.SINGULARITY_BUG, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes vanilla MC crash as VANILLA_BUG`() {
        val parsed = parse("java.lang.NullPointerException", "",
            "net.minecraft.world.level.Level.tick(Level.java:123)",
            "net.minecraft.server.MinecraftServer.run(MinecraftServer.java:100)")
        assertEquals(CrashPatternMatcher.CrashCategory.VANILLA_BUG, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes crash in known mod package as MOD_BUG`() {
        val parsed = parse("java.lang.NullPointerException", "null",
            "me.jellysquid.mods.sodium.SodiumMod.init(SodiumMod.java:42)",
            "net.minecraft.server.MinecraftServer.run(MinecraftServer.java:100)")
        assertEquals(CrashPatternMatcher.CrashCategory.MOD_BUG, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes render-related crash as RENDER_ERROR`() {
        val parsed = parse("java.lang.RuntimeException", "GL error",
            "net.minecraft.client.renderer.RenderSystem.checkError(RenderSystem.java:50)",
            "net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:100)")
        assertEquals(CrashPatternMatcher.CrashCategory.RENDER_ERROR, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes unknown third-party crash as MOD_BUG`() {
        // Any non-vanilla, non-JDK, non-singularity top frame → MOD_BUG
        val parsed = parse("java.lang.RuntimeException", "something",
            "com.unknown.pkg.Foo.bar(Foo.java:1)")
        assertEquals(CrashPatternMatcher.CrashCategory.MOD_BUG, matcher.categorize(parsed))
    }

    @Test
    fun `categorizes org embeddedt crash as MOD_BUG`() {
        val parsed = parse("java.lang.NullPointerException", "null",
            "org.embeddedt.modernfix.ModernFix.init(ModernFix.java:42)")
        assertEquals(CrashPatternMatcher.CrashCategory.MOD_BUG, matcher.categorize(parsed))
    }

    @Test
    fun `describes OOM with helpful message`() {
        val parsed = parse("java.lang.OutOfMemoryError", "Java heap space")
        val desc = matcher.describe(parsed, CrashPatternMatcher.CrashCategory.OUT_OF_MEMORY)
        assertTrue(desc.contains("RAM", ignoreCase = true) || desc.contains("pamięć", ignoreCase = true))
    }

    @Test
    fun `suggests actions for OOM`() {
        val actions = matcher.suggestActions(CrashPatternMatcher.CrashCategory.OUT_OF_MEMORY)
        assertTrue(actions.isNotEmpty())
        assertTrue(actions.any { it.contains("RAM", ignoreCase = true) })
    }

    @Test
    fun `suggests actions for MISSING_DEPENDENCY`() {
        val actions = matcher.suggestActions(CrashPatternMatcher.CrashCategory.MISSING_DEPENDENCY)
        assertTrue(actions.any { it.contains("Modrinth", ignoreCase = true) || it.contains("zależność", ignoreCase = true) })
    }

    @Test
    fun `suggests actions for SINGULARITY_BUG`() {
        val actions = matcher.suggestActions(CrashPatternMatcher.CrashCategory.SINGULARITY_BUG)
        assertTrue(actions.any { it.contains("GitHub", ignoreCase = true) || it.contains("zgłoś", ignoreCase = true) })
    }

    @Test
    fun `all categories have suggestions`() {
        for (category in CrashPatternMatcher.CrashCategory.entries) {
            val actions = matcher.suggestActions(category)
            assertTrue(actions.isNotEmpty(), "Category $category should have suggestions")
        }
    }

    @Test
    fun `categorizes with no stack trace as UNKNOWN`() {
        val parsed = parse("java.lang.RuntimeException", "no stack")
        assertEquals(CrashPatternMatcher.CrashCategory.UNKNOWN, matcher.categorize(parsed))
    }
}
