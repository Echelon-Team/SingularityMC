package com.singularity.agent

import org.slf4j.LoggerFactory
import java.lang.instrument.Instrumentation

/**
 * Entry point agenta SingularityMC.
 *
 * Ładowany przez JVM z flagą -javaagent:singularity-agent.jar.
 * Metoda premain() wywoływana PRZED main() aplikacji docelowej.
 *
 * Szkielet — loguje załadowanie. Pełna implementacja w Subsystemie 2:
 * - Parsowanie args (ścieżka do configu instancji)
 * - Ładowanie compat module JAR
 * - Weryfikacja requiredContracts (agent ⊇ moduł)
 * - Ładowanie mapping tables
 * - Budowa drzewa dziedziczenia
 * - Rejestracja ClassFileTransformer
 * - Otwarcie IPC socket
 *
 * Referencja: implementation design sekcja 4.1.
 */
object AgentMain {

    private val logger = LoggerFactory.getLogger(AgentMain::class.java)

    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        logger.info("SingularityMC Agent loaded")
        logger.info("Agent args: {}", agentArgs ?: "none")
        logger.info("Can redefine classes: {}", inst.isRedefineClassesSupported)
        logger.info("Can retransform classes: {}", inst.isRetransformClassesSupported)
        logger.info("Agent initialization complete (skeleton)")
    }
}
