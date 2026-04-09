package com.singularity.agent.mod

import org.slf4j.LoggerFactory

/**
 * Fazowa inicjalizacja modów.
 *
 * Kolejność (design spec sekcja 5A.5):
 *   Faza 1a: Forge/NeoForge RegistryEvents (rejestracja WŁASNYCH bloków/itemów)
 *   Faza 1b: Fabric onInitialize() (rejestracja + setup, topologicznie per mod)
 *   → Po 1a+1b: zunifikowany rejestr KOMPLETNY
 *   Faza 2: Forge FMLCommonSetupEvent / FMLClientSetupEvent
 *   Faza 3: Forge InterModEnqueueEvent / InterModProcessEvent
 *   Faza 4: Load Complete (wszyscy powiadomieni)
 *
 * Callbacki to punkty hookowania — pełna implementacja z prawdziwym event dispatch
 * w Subsystemie 2d/2e (EventBridge + Shims). Tu testujemy kolejność i error handling.
 *
 * @param onPhase1a callback wywoływany per Forge/NeoForge mod w fazie 1a
 * @param onPhase1b callback wywoływany per Fabric mod w fazie 1b
 * @param onPhase2 callback wywoływany per Forge/NeoForge mod w fazie 2
 * @param onPhase3 callback wywoływany per Forge/NeoForge mod w fazie 3
 * @param onPhase4 callback wywoływany raz po zakończeniu wszystkich faz
 */
class ModInitializer(
    private val onPhase1a: (ModInfo) -> Unit,
    private val onPhase1b: (ModInfo) -> Unit,
    private val onPhase2: (ModInfo) -> Unit,
    private val onPhase3: (ModInfo) -> Unit,
    private val onPhase4: () -> Unit
) {
    private val logger = LoggerFactory.getLogger(ModInitializer::class.java)

    /**
     * Inicjalizuje mody w fazowej kolejności.
     *
     * @param forgeMods mody Forge/NeoForge (już topologicznie posortowane)
     * @param fabricMods mody Fabric (już topologicznie posortowane)
     */
    fun initialize(forgeMods: List<ModInfo>, fabricMods: List<ModInfo>) {
        logger.info(
            "Starting phased initialization: {} Forge + {} Fabric mods",
            forgeMods.size, fabricMods.size
        )

        // Faza 1a: Forge RegistryEvents
        logger.info("Phase 1a: Forge RegistryEvents ({} mods)", forgeMods.size)
        for (mod in forgeMods) {
            safeExecute("Phase 1a", mod) { onPhase1a(mod) }
        }

        // Faza 1b: Fabric onInitialize
        logger.info("Phase 1b: Fabric onInitialize ({} mods)", fabricMods.size)
        for (mod in fabricMods) {
            safeExecute("Phase 1b", mod) { onPhase1b(mod) }
        }

        logger.info("Unified registry COMPLETE after phases 1a+1b")

        // Faza 2: Forge FMLCommonSetupEvent
        logger.info("Phase 2: Forge FMLCommonSetupEvent ({} mods)", forgeMods.size)
        for (mod in forgeMods) {
            safeExecute("Phase 2", mod) { onPhase2(mod) }
        }

        // Faza 3: Forge InterModEnqueueEvent / InterModProcessEvent
        logger.info("Phase 3: Forge InterMod ({} mods)", forgeMods.size)
        for (mod in forgeMods) {
            safeExecute("Phase 3", mod) { onPhase3(mod) }
        }

        // Faza 4: Load Complete — symetryczne error handling z phases 1a-3 (catch Throwable,
        // rethrow VMError, restore interrupt flag). Sub 2d wire FMLLoadCompleteEvent tutaj.
        logger.info("Phase 4: Load Complete")
        try {
            onPhase4()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            logger.error("Phase 4 callback threw {}: {}", e.javaClass.simpleName, e.message, e)
        }
        logger.info("Phased initialization complete")
    }

    /**
     * Wykonuje callback z ochroną przed wyjątkami — jeden buggy mod nie blokuje reszty.
     *
     * Łapie `Throwable` bo Forge mody często rzucają `NoClassDefFoundError` / `LinkageError`
     * (classpath issues, missing optional deps) — to są `Error`, nie `Exception`. Bez catch'u
     * Throwable cały phase stage pada przy pierwszym buggy modzie.
     *
     * WYJĄTKI od tej reguły:
     * - `VirtualMachineError` (OOM, StackOverflow, UnknownError) są RE-THROWN — recovery
     *   jest niemożliwe, JVM jest w broken state.
     * - `InterruptedException` — przywracamy flag via `Thread.currentThread().interrupt()`
     *   przed log-and-continue. Obecnie init jest single-threaded (premain), ale Sub 2d/2e
     *   może wywoływać to z worker thread — nie chcemy łamać interrupt protocol.
     */
    private fun safeExecute(phase: String, mod: ModInfo, action: () -> Unit) {
        try {
            action()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            logger.error(
                "{}: {} in mod '{}' — {}",
                phase, e.javaClass.simpleName, mod.modId, e.message, e
            )
        }
    }
}
