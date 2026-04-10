package com.singularity.agent.mod

import org.slf4j.LoggerFactory

/**
 * Wykrywa zduplikowane mody (dwa JARy z tym samym mod ID).
 *
 * Heurystyka (design spec sekcja 5A.3):
 * - Ten sam mod ID + ten sam autor + ta sama nazwa → dwie wersje tego samego moda → KeepNewer
 * - Ten sam mod ID + INNY autor LUB INNA nazwa → dwa różne mody z kolizją ID → ConflictingIds
 */
object DuplicateDetector {

    private val logger = LoggerFactory.getLogger(DuplicateDetector::class.java)

    sealed class DuplicateAction {
        /** Dwie wersje tego samego moda — propozycja zachowania nowszej */
        data class KeepNewer(val keep: ModInfo, val remove: ModInfo) : DuplicateAction()

        /** Dwa RÓŻNE mody z kolizją ID */
        data class ConflictingIds(val modA: ModInfo, val modB: ModInfo) : DuplicateAction()
    }

    fun detect(mods: List<ModInfo>): List<DuplicateAction> {
        val actions = mutableListOf<DuplicateAction>()
        val grouped = mods.groupBy { it.modId }

        for ((modId, group) in grouped) {
            if (group.size < 2) continue

            logger.warn("Duplicate mod ID detected: {} ({} JARs)", modId, group.size)

            // Sprawdź czy to ten sam mod w różnych wersjach.
            // Wymagamy: (1) oba mają authors NIEPUSTE, (2) authors i name się zgadzają.
            // Empty authors + same name → niepewność (różni twórcy mogą nazwać mod
            // "Library" / modId "core"). Fallback do ConflictingIds jest bezpieczniejszy
            // niż ciche KeepNewer z ryzykiem usunięcia legitymatnego różnego moda.
            val hasAuthors = group.all { it.authors.isNotEmpty() }
            val isSameMod = hasAuthors && group.all {
                it.name == group[0].name && it.authors == group[0].authors
            }

            if (isSameMod) {
                // Te same autorzy i nazwa — sortuj po wersji (numerycznie, NIE leksykograficznie).
                // Lexicographic sort ma bug: "0.5.10" < "0.5.9" bo '1' < '9'. Używamy
                // ModVersionComparator który porównuje integer-by-integer.
                val sorted = group.sortedWith(compareByDescending(ModVersionComparator) { it.version })
                val newest = sorted.first()
                for (older in sorted.drop(1)) {
                    actions.add(DuplicateAction.KeepNewer(keep = newest, remove = older))
                }
                logger.info(
                    "Duplicate {}: keeping v{}, removing {} older version(s)",
                    modId, newest.version, sorted.size - 1
                )
            } else {
                // Różne mody z tym samym ID — emit all unique pairs (nie tylko adjacent).
                // Dla 3 konfliktujących modów A, B, C: (A,B), (A,C), (B,C) — nie tylko (A,B),(B,C).
                for (i in group.indices) {
                    for (j in i + 1 until group.size) {
                        actions.add(DuplicateAction.ConflictingIds(modA = group[i], modB = group[j]))
                    }
                }
                logger.error("Conflicting mod IDs: {} — {} different mods use the same ID", modId, group.size)
            }
        }

        return actions
    }
}
