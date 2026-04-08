package com.singularity.agent.module

import com.singularity.common.contracts.ModuleDescriptorData
import org.slf4j.LoggerFactory

/**
 * Waliduje ze agent oferuje WSZYSTKIE kontrakty wymagane przez modul.
 *
 * Zasada: agent ⊇ modul — agent musi oferowac wszystko co modul wymaga.
 * Moze oferowac wiecej (nowe kontrakty ktorych stary modul nie uzywa). Nigdy mniej.
 * Niezgodnosc → blad: gra sie nie odpala.
 *
 * Referencja: design spec sekcja 3 (Kompatybilnosc agent↔modul).
 */
object ContractValidator {

    private val logger = LoggerFactory.getLogger(ContractValidator::class.java)

    data class ValidationResult(
        val isValid: Boolean,
        val missingContracts: Set<String>,
        val errorMessage: String?
    )

    fun validate(agentContracts: Set<String>, moduleDescriptor: ModuleDescriptorData): ValidationResult {
        val required = moduleDescriptor.requiredContracts
        val missing = required - agentContracts

        return if (missing.isEmpty()) {
            logger.info("Contract validation PASSED for module '{}': all {} required contracts satisfied",
                moduleDescriptor.moduleId, required.size)
            ValidationResult(isValid = true, missingContracts = emptySet(), errorMessage = null)
        } else {
            val msg = buildString {
                append("Modul '${moduleDescriptor.moduleId}' v${moduleDescriptor.moduleVersion}")
                append(" wymaga kontraktow ktorych ten launcher nie obsluguje: ")
                append(missing.joinToString(", "))
                append(". Zaktualizuj launcher.")
            }
            logger.error("Contract validation FAILED: {}", msg)
            ValidationResult(isValid = false, missingContracts = missing, errorMessage = msg)
        }
    }
}
