// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.mod

import org.slf4j.LoggerFactory

/**
 * Buduje graf zależności między modami i topologicznie sortuje.
 *
 * Algorytm: Kahn's BFS topological sort z detekcją cykli.
 *
 * System dependencies (minecraft, java, fabricloader, forge, neoforge) są IGNOROWANE —
 * te nie są modami w naszym systemie, sprawdzanie ich wersji jest osobnym krokiem.
 *
 * Referencja: design spec sekcja 5A.3.
 */
object DependencyResolver {

    private val logger = LoggerFactory.getLogger(DependencyResolver::class.java)

    /** Zależności systemowe — nie są modami, ignorujemy w grafie */
    private val SYSTEM_DEPS = setOf(
        "minecraft", "java", "fabricloader", "fabric-loader",
        "forge", "neoforge", "fabric-api", "fabric"
    )

    data class ResolutionResult(
        val sortedMods: List<ModInfo>,
        val errors: List<DependencyError>,
        val warnings: List<DependencyWarning>
    )

    sealed class DependencyError {
        data class MissingRequired(
            val requiredBy: String,
            val missingModId: String,
            val versionRange: String?
        ) : DependencyError()

        data class CyclicDependency(
            val involvedMods: List<String>
        ) : DependencyError()
    }

    sealed class DependencyWarning {
        data class VersionConflict(
            val modId: String,
            val requestedVersions: Map<String, String>
        ) : DependencyWarning()
    }

    /**
     * Rozwiązuje zależności i topologicznie sortuje mody.
     *
     * @param mods lista ModInfo do posortowania
     * @return ResolutionResult z posortowaną listą, błędami i ostrzeżeniami
     */
    fun resolve(mods: List<ModInfo>): ResolutionResult {
        if (mods.isEmpty()) {
            return ResolutionResult(emptyList(), emptyList(), emptyList())
        }

        val errors = mutableListOf<DependencyError>()
        val warnings = mutableListOf<DependencyWarning>()
        val modMap = mods.associateBy { it.modId }

        // Buduj graf: modId → set of modIds it depends on (filtered to existing mods)
        val inDegree = mutableMapOf<String, Int>()
        val dependents = mutableMapOf<String, MutableList<String>>() // dep → list of mods depending on it

        for (mod in mods) {
            inDegree.putIfAbsent(mod.modId, 0)
        }

        for (mod in mods) {
            for (dep in mod.dependencies) {
                if (dep.modId in SYSTEM_DEPS) continue // Ignoruj system deps

                if (dep.modId !in modMap) {
                    if (dep.required) {
                        errors.add(
                            DependencyError.MissingRequired(
                                requiredBy = mod.modId,
                                missingModId = dep.modId,
                                versionRange = dep.versionRange
                            )
                        )
                    }
                    continue // Brakująca opcjonalna lub wymagana (error dodany) — skip w grafie
                }

                // mod zależy od dep.modId → dep.modId musi być przed mod
                inDegree[mod.modId] = (inDegree[mod.modId] ?: 0) + 1
                dependents.getOrPut(dep.modId) { mutableListOf() }.add(mod.modId)
            }
        }

        // Kahn's algorithm — BFS topological sort
        val queue = ArrayDeque<String>()
        for ((modId, degree) in inDegree) {
            if (degree == 0) queue.add(modId)
        }

        val sorted = mutableListOf<ModInfo>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sorted.add(modMap[current]!!)

            for (dependent in dependents[current] ?: emptyList()) {
                inDegree[dependent] = (inDegree[dependent] ?: 1) - 1
                if (inDegree[dependent] == 0) {
                    queue.add(dependent)
                }
            }
        }

        // Detekcja cyklu: jeśli nie wszystkie mody trafiły do sorted
        if (sorted.size < mods.size) {
            val sortedIds = sorted.map { it.modId }.toSet()
            val cycleMembers = mods.map { it.modId }.filter { it !in sortedIds }
            errors.add(DependencyError.CyclicDependency(cycleMembers))
            logger.error("Cyclic dependency detected among: {}", cycleMembers)

            // Dodaj mody z cyklu na koniec (graceful — pozwól graczowi zobaczyć problem)
            for (mod in mods) {
                if (mod.modId in cycleMembers) sorted.add(mod)
            }
        }

        logger.info(
            "Dependency resolution: {} mods sorted, {} errors, {} warnings",
            sorted.size, errors.size, warnings.size
        )

        return ResolutionResult(sorted, errors, warnings)
    }
}
