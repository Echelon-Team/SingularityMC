package com.singularity.agent

import com.singularity.agent.module.ContractValidator
import com.singularity.agent.module.ModuleLoader
import org.slf4j.LoggerFactory
import java.lang.instrument.Instrumentation
import java.nio.file.Paths

/**
 * Entry point agenta SingularityMC.
 *
 * Ladowany przez JVM z flaga -javaagent:singularity-agent.jar=<instanceDir>.
 * Metoda premain() wywolywana PRZED main() Minecrafta.
 *
 * Argument agenta: sciezka do katalogu instancji (np. "C:/Users/baran/.singularity/instances/MyInstance").
 * Jesli brak argumentu — tryb standalone (logowanie tylko, bez compat module — do testow).
 *
 * Sub 2a implementation:
 * 1. Parsuj agentArgs jako instanceDir
 * 2. Znajdz module compat w <instanceDir>/.singularity/modules/
 * 3. Zaladuj module, sparsuj deskryptor
 * 4. Zwaliduj kontrakty (agent ⊇ modul)
 * 5. Jesli contract mismatch → throw IllegalStateException (fail-fast, JVM pada)
 *
 * Sub 2b extensions (TODO):
 * - Zaladowanie mapping tables z module JAR do MappingTable instances
 * - Budowa InheritanceTree ze skanu MC JAR
 * - Rejestracja ClassFileTransformer z SingularityTransformer pipeline
 * - Otwarcie IPC socket do launcher
 *
 * Sub 6 TODO:
 * - Hardcoded mcVersion "1.20.1" → czytac z instance.json
 * - logback root DEBUG → INFO dla production
 *
 * Referencja: implementation design sekcja 4.1.
 */
object AgentMain {

    private val logger = LoggerFactory.getLogger(AgentMain::class.java)

    /**
     * Kontrakty oferowane przez agenta. Modul musi wymagac PODZBIORU tych kontraktow
     * (agent ⊇ modul). Dodawanie nowych kontraktow w pozniejszych wersjach agenta jest
     * OK — stare moduly nadal dzialaja bo wymagaja podzbioru.
     */
    val OFFERED_CONTRACTS = setOf(
        "metadata",
        "remapping",
        "loader_emulation",
        "bridges",
        "hooks"
    )

    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        logger.info("SingularityMC Agent loaded")
        logger.info("Agent args: {}", agentArgs ?: "none")
        logger.info("Can redefine classes: {}", inst.isRedefineClassesSupported)
        logger.info("Can retransform classes: {}", inst.isRetransformClassesSupported)

        if (agentArgs.isNullOrBlank()) {
            logger.info("No instance path provided — running in standalone mode (skeleton)")
            return
        }

        val instanceDir = Paths.get(agentArgs)
        val modulesDir = instanceDir.resolve(".singularity/modules")

        // TODO Sub 6: wersja MC powinna byc czytana z instance.json
        val mcVersion = "1.20.1"

        val modulePath = ModuleLoader.findModule(modulesDir, mcVersion)
        if (modulePath == null) {
            logger.warn("No compat module found for MC {} — running without compat layer", mcVersion)
            return
        }

        // use{} zamyka LoadedModule (JarFile) niezależnie od throw w bloku.
        // edge-case-hunter flag #3: plan bez .use{} leakował JarFile, na Windows
        // to blokuje delete/update module JAR po ponownym uruchomieniu gry.
        //
        // UWAGA Sub 2b: gdy mapping tables beda ladowane z module JAR do RemappingEngine,
        // module musi byc open przez wystarczajaco dlugo zeby wczytac wszystkie pliki .tiny.
        // Potem close jest OK (mappingi w pamieci). Na razie (Sub 2a skeleton) close od razu.
        ModuleLoader.loadModule(modulePath).use { module ->
            val validation = ContractValidator.validate(OFFERED_CONTRACTS, module.descriptor)
            if (!validation.isValid) {
                logger.error("Contract validation FAILED: {}", validation.errorMessage)
                throw IllegalStateException(validation.errorMessage)
            }

            // TODO Sub 2b: Zaladuj mapping tables, zbuduj drzewo dziedziczenia
            // TODO Sub 2b: Zarejestruj ClassFileTransformer
            // TODO Sub 6: Otworz IPC socket

            logger.info("Agent initialization complete (Sub 2a — module validated, no transforms yet)")
        }
    }
}
