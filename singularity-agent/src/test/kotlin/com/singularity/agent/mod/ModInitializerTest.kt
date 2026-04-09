package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class ModInitializerTest {

    private fun mod(id: String, loader: LoaderType) = ModInfo(
        modId = id, version = "1.0", name = id, loaderType = loader,
        dependencies = emptyList(), entryPoints = emptyList(), mixinConfigs = emptyList(),
        authors = emptyList(), description = "", side = ModSide.BOTH,
        jarPath = Paths.get("/mods/$id.jar")
    )

    @Test
    fun `phases execute in correct order — 1a before 1b before 2 before 3 before 4`() {
        val executionLog = mutableListOf<String>()

        val initializer = ModInitializer(
            onPhase1a = { mod -> executionLog.add("1a:${mod.modId}") },
            onPhase1b = { mod -> executionLog.add("1b:${mod.modId}") },
            onPhase2 = { mod -> executionLog.add("2:${mod.modId}") },
            onPhase3 = { mod -> executionLog.add("3:${mod.modId}") },
            onPhase4 = { executionLog.add("4:complete") }
        )

        val forgeMods = listOf(mod("create", LoaderType.FORGE))
        val fabricMods = listOf(mod("sodium", LoaderType.FABRIC))

        initializer.initialize(forgeMods, fabricMods)

        // Forge 1a PRZED Fabric 1b
        val idx1a = executionLog.indexOfFirst { it.startsWith("1a:") }
        val idx1b = executionLog.indexOfFirst { it.startsWith("1b:") }
        val idx2 = executionLog.indexOfFirst { it.startsWith("2:") }
        val idx3 = executionLog.indexOfFirst { it.startsWith("3:") }
        val idx4 = executionLog.indexOfFirst { it.startsWith("4:") }

        assertTrue(idx1a >= 0, "Phase 1a should execute")
        assertTrue(idx1b >= 0, "Phase 1b should execute")
        assertTrue(idx1a < idx1b, "Phase 1a before 1b")
        assertTrue(idx1b < idx2, "Phase 1b before 2")
        assertTrue(idx2 < idx3, "Phase 2 before 3")
        assertTrue(idx3 < idx4, "Phase 3 before 4")
    }

    @Test
    fun `all Forge mods get 1a, 2, 3`() {
        val executionLog = mutableListOf<String>()

        val initializer = ModInitializer(
            onPhase1a = { executionLog.add("1a:${it.modId}") },
            onPhase1b = { executionLog.add("1b:${it.modId}") },
            onPhase2 = { executionLog.add("2:${it.modId}") },
            onPhase3 = { executionLog.add("3:${it.modId}") },
            onPhase4 = { executionLog.add("4:complete") }
        )

        val forgeMods = listOf(
            mod("create", LoaderType.FORGE),
            mod("jei", LoaderType.FORGE)
        )

        initializer.initialize(forgeMods, emptyList())

        assertEquals(2, executionLog.count { it.startsWith("1a:") })
        assertEquals(2, executionLog.count { it.startsWith("2:") })
        assertEquals(2, executionLog.count { it.startsWith("3:") })
    }

    @Test
    fun `Fabric mods get 1b but NOT 1a, 2, 3`() {
        val executionLog = mutableListOf<String>()

        val initializer = ModInitializer(
            onPhase1a = { executionLog.add("1a:${it.modId}") },
            onPhase1b = { executionLog.add("1b:${it.modId}") },
            onPhase2 = { executionLog.add("2:${it.modId}") },
            onPhase3 = { executionLog.add("3:${it.modId}") },
            onPhase4 = { executionLog.add("4:complete") }
        )

        initializer.initialize(emptyList(), listOf(mod("sodium", LoaderType.FABRIC)))

        assertEquals(0, executionLog.count { it.startsWith("1a:") })
        assertEquals(1, executionLog.count { it.startsWith("1b:") })
        // Fazy 2 i 3 są Forge-specific — Fabric mody ich nie dostaną
        assertEquals(0, executionLog.count { it.startsWith("2:") })
    }

    @Test
    fun `exception in one mod does not stop others`() {
        val executionLog = mutableListOf<String>()

        val initializer = ModInitializer(
            onPhase1a = {
                executionLog.add("1a:${it.modId}")
                if (it.modId == "buggy") throw RuntimeException("Crash in ${it.modId}")
            },
            onPhase1b = { },
            onPhase2 = { executionLog.add("2:${it.modId}") },
            onPhase3 = { executionLog.add("3:${it.modId}") },
            onPhase4 = { executionLog.add("4:complete") }
        )

        val forgeMods = listOf(
            mod("buggy", LoaderType.FORGE),
            mod("stable", LoaderType.FORGE)
        )

        // Nie powinien rzucić wyjątku — łapie i loguje
        assertDoesNotThrow {
            initializer.initialize(forgeMods, emptyList())
        }
        // Oba mody dostały 1a (nawet mimo wyjątku w buggy)
        assertEquals(2, executionLog.count { it.startsWith("1a:") })
        // stable przeszedł przez fazy 2 i 3 (exception w 1a nie blokuje kolejnych faz)
        assertTrue(executionLog.contains("2:stable"))
        assertTrue(executionLog.contains("3:stable"))
        // phase 4 zakończone
        assertTrue(executionLog.contains("4:complete"))
    }

    @Test
    fun `empty mod lists still call phase 4`() {
        var phase4Called = false

        val initializer = ModInitializer(
            onPhase1a = { fail("Should not be called") },
            onPhase1b = { fail("Should not be called") },
            onPhase2 = { fail("Should not be called") },
            onPhase3 = { fail("Should not be called") },
            onPhase4 = { phase4Called = true }
        )

        initializer.initialize(emptyList(), emptyList())
        assertTrue(phase4Called)
    }

    @Test
    fun `NoClassDefFoundError in one mod does not stop others`() {
        // Real case: Forge mod has optional dep on missing class → NoClassDefFoundError
        // This is a Throwable/Error, NOT Exception. Old safeExecute with `catch(e: Exception)`
        // would propagate the Error and kill the whole phase.
        val executionLog = mutableListOf<String>()

        val initializer = ModInitializer(
            onPhase1a = {
                executionLog.add("1a:${it.modId}")
                if (it.modId == "broken") throw NoClassDefFoundError("missing/OptionalDep")
            },
            onPhase1b = { },
            onPhase2 = { executionLog.add("2:${it.modId}") },
            onPhase3 = { executionLog.add("3:${it.modId}") },
            onPhase4 = { executionLog.add("4:complete") }
        )

        val forgeMods = listOf(
            mod("broken", LoaderType.FORGE),
            mod("stable", LoaderType.FORGE)
        )

        assertDoesNotThrow {
            initializer.initialize(forgeMods, emptyList())
        }
        // Both mods reached phase 1a (1a was not short-circuited by broken)
        assertEquals(2, executionLog.count { it.startsWith("1a:") })
        // stable continued through phases 2, 3
        assertTrue(executionLog.contains("2:stable"))
        assertTrue(executionLog.contains("3:stable"))
        assertTrue(executionLog.contains("4:complete"))
    }

    @Test
    fun `VirtualMachineError (OOM) is rethrown not swallowed`() {
        // OOM / StackOverflowError are VirtualMachineError — unrecoverable.
        // safeExecute MUST rethrow, not catch+continue.
        val initializer = ModInitializer(
            onPhase1a = { throw OutOfMemoryError("simulated OOM") },
            onPhase1b = { },
            onPhase2 = { },
            onPhase3 = { },
            onPhase4 = { }
        )

        val forgeMods = listOf(mod("doomed", LoaderType.FORGE))

        assertThrows(OutOfMemoryError::class.java) {
            initializer.initialize(forgeMods, emptyList())
        }
    }

    @Test
    fun `InterruptedException in phase restores interrupt flag`() {
        // Sub 2d może wywoływać fazy z worker thread. Interrupt protocol musi działać —
        // catch Throwable nie może "gubić" interrupt flag.
        val initializer = ModInitializer(
            onPhase1a = { throw InterruptedException("simulated interrupt") },
            onPhase1b = { },
            onPhase2 = { },
            onPhase3 = { },
            onPhase4 = { }
        )

        // Wykonaj init w osobnym wątku, sprawdź flag po powrocie
        val interruptFlagAfter = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            initializer.initialize(listOf(mod("a", LoaderType.FORGE)), emptyList())
            // Po powrocie z initialize, flag interrupt powinien być TRUE (restored)
            interruptFlagAfter.set(Thread.currentThread().isInterrupted)
        }
        thread.start()
        thread.join()

        assertTrue(interruptFlagAfter.get(), "Interrupt flag should be restored after catch")
    }

    @Test
    fun `phase 4 callback Throwable is caught symmetrically with phases 1-3`() {
        // Design-compliance S1: phase 4 was catching only Exception; now catches Throwable
        // with VMError rethrow, matching phases 1a-3.
        val executionLog = mutableListOf<String>()
        val initializer = ModInitializer(
            onPhase1a = { executionLog.add("1a:${it.modId}") },
            onPhase1b = { },
            onPhase2 = { executionLog.add("2:${it.modId}") },
            onPhase3 = { executionLog.add("3:${it.modId}") },
            onPhase4 = { throw NoClassDefFoundError("missing FMLLoadCompleteEvent") }
        )

        // Nie powinno rzucić (Error jest logged, nie propagated)
        assertDoesNotThrow {
            initializer.initialize(listOf(mod("a", LoaderType.FORGE)), emptyList())
        }
        // Phases 1a-3 zostały wykonane
        assertEquals(listOf("1a:a", "2:a", "3:a"), executionLog)
    }

    @Test
    fun `phase 4 VirtualMachineError is rethrown`() {
        val initializer = ModInitializer(
            onPhase1a = { },
            onPhase1b = { },
            onPhase2 = { },
            onPhase3 = { },
            onPhase4 = { throw StackOverflowError("phase 4 stack overflow") }
        )

        assertThrows(StackOverflowError::class.java) {
            initializer.initialize(listOf(mod("a", LoaderType.FORGE)), emptyList())
        }
    }
}
