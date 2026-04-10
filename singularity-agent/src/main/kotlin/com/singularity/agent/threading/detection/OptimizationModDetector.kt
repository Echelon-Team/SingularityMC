package com.singularity.agent.threading.detection

import com.singularity.agent.registry.SingularityModRegistry
import org.slf4j.LoggerFactory

/**
 * Wykrywa znane mody optymalizacyjne i decyduje o adaptacji threading engine.
 *
 * Design spec 4.1:
 * - Sodium/Embeddium → przejmuje chunk mesh building, frustum culling, translucent sorting
 * - Iris/Oculus → działa NA Sodium, brak osobnej integracji
 * - C2ME → BLOKADA. Nasz system zastępuje.
 * - Lithium/Canary → kompatybilny (ortogonalne optymalizacje)
 * - Starlight/ScalableLux → kompatybilny (lighting)
 */
class OptimizationModDetector(private val registry: SingularityModRegistry) {
    private val logger = LoggerFactory.getLogger(OptimizationModDetector::class.java)

    data class DetectionResult(
        val sodiumDetected: Boolean,
        val c2meDetected: Boolean,
        val lithiumDetected: Boolean,
        val starlightDetected: Boolean,
        val shouldBlockStartup: Boolean,
        val blockReason: String?
    )

    companion object {
        private val SODIUM_IDS = setOf("sodium", "embeddium", "rubidium")
        private val C2ME_IDS = setOf("c2me", "concurrent-chunk-management-engine")
        private val LITHIUM_IDS = setOf("lithium", "canary")
        private val STARLIGHT_IDS = setOf("starlight", "scalablelux")
    }

    fun detect(): DetectionResult {
        val allMods = registry.getAll().map { it.modId.lowercase() }.toSet()

        val sodium = allMods.any { it in SODIUM_IDS }
        val c2me = allMods.any { it in C2ME_IDS }
        val lithium = allMods.any { it in LITHIUM_IDS }
        val starlight = allMods.any { it in STARLIGHT_IDS }

        val blockReason = if (c2me) {
            "C2ME nie jest potrzebny — SingularityMC Enhanced zawiera wielowątkowy chunk loading. Usuń C2ME."
        } else null

        if (sodium) logger.info("Detected Sodium/Embeddium — adapting prep threads")
        if (c2me) logger.error("Detected C2ME — BLOCKING startup: {}", blockReason)
        if (lithium) logger.info("Detected Lithium/Canary — compatible")
        if (starlight) logger.info("Detected Starlight/ScalableLux — compatible")

        return DetectionResult(
            sodiumDetected = sodium,
            c2meDetected = c2me,
            lithiumDetected = lithium,
            starlightDetected = starlight,
            shouldBlockStartup = c2me,
            blockReason = blockReason
        )
    }
}
