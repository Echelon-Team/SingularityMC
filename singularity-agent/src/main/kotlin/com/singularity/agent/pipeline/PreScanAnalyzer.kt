package com.singularity.agent.pipeline

import org.slf4j.LoggerFactory

/**
 * Pre-scan analyzer — wykrywa konflikty miedzy mixinami PRZED ich aplikacja.
 *
 * Wywolywany raz przez AgentMain po zebraniu wszystkich mixin declarations przez
 * MixinConfigScanner (po discovery modow, przed MixinBootstrap.init()).
 *
 * Algorithm: grupuje declarations po `targetClass#targetMethod`, dla kazdej pary
 * (z roznych modow) sprawdza kompatybilnosc typow modifications.
 *
 * RED (blokada):
 * - @Overwrite + @Overwrite na tej samej metodzie z dwoch modow
 * - @Redirect + @Redirect na tym samym call site
 *
 * YELLOW (ostrzezenie):
 * - @Overwrite + @Inject (inject moze byc lost)
 * - @ModifyVariable + @ModifyVariable (nondeterministic order)
 * - @WrapOperation + @WrapOperation (nondeterministic order)
 *
 * GREEN: @Inject + @Inject, @Inject + @ModifyArg, etc.
 *
 * Referencja: design spec sekcja 5.3 krok 6.
 */
object PreScanAnalyzer {

    private val logger = LoggerFactory.getLogger(PreScanAnalyzer::class.java)

    fun analyze(declarations: List<MixinDeclaration>): ConflictReport {
        val conflicts = mutableListOf<ConflictReport.Conflict>()

        // Grupuj po target class + method (null method = wildcard group)
        val grouped = declarations.groupBy { "${it.targetClass}#${it.targetMethod ?: "*"}" }

        for ((_, decls) in grouped) {
            if (decls.size < 2) continue
            // Pair-wise check — dla 3+ modow kazda para jest sprawdzana
            for (i in decls.indices) {
                for (j in i + 1 until decls.size) {
                    val a = decls[i]
                    val b = decls[j]
                    if (a.modId == b.modId) continue  // ten sam mod — ignore
                    val conflict = checkPair(a, b)
                    if (conflict != null) conflicts.add(conflict)
                }
            }
        }

        val report = ConflictReport(conflicts)
        if (report.hasBlockingConflicts) {
            logger.error("Pre-scan found {} RED conflicts (blocking)", report.redCount)
        }
        if (report.yellowCount > 0) {
            logger.warn("Pre-scan found {} YELLOW conflicts (warnings)", report.yellowCount)
        }
        return report
    }

    private fun checkPair(a: MixinDeclaration, b: MixinDeclaration): ConflictReport.Conflict? {
        val types = setOf(a.type, b.type)

        // RED cases
        if (a.type == MixinType.OVERWRITE && b.type == MixinType.OVERWRITE) {
            return red(a, b, "@Overwrite + @Overwrite on ${a.targetClass}#${a.targetMethod}")
        }
        if (a.type == MixinType.REDIRECT && b.type == MixinType.REDIRECT) {
            return red(a, b, "@Redirect + @Redirect on ${a.targetClass}#${a.targetMethod}")
        }

        // YELLOW cases
        if (types.contains(MixinType.OVERWRITE) && types.contains(MixinType.INJECT)) {
            return yellow(a, b, "@Overwrite + @Inject on ${a.targetClass}#${a.targetMethod} — inject may be lost")
        }
        if (a.type == MixinType.MODIFY_VARIABLE && b.type == MixinType.MODIFY_VARIABLE) {
            return yellow(a, b, "Double @ModifyVariable on ${a.targetClass}#${a.targetMethod} — nondeterministic order")
        }
        if (a.type == MixinType.WRAP_OPERATION && b.type == MixinType.WRAP_OPERATION) {
            return yellow(a, b, "Double @WrapOperation on ${a.targetClass}#${a.targetMethod} — nondeterministic order")
        }

        return null  // GREEN
    }

    private fun red(a: MixinDeclaration, b: MixinDeclaration, desc: String) = ConflictReport.Conflict(
        ConflictReport.Severity.RED, a.targetClass, a.targetMethod, a.modId, b.modId, desc
    )

    private fun yellow(a: MixinDeclaration, b: MixinDeclaration, desc: String) = ConflictReport.Conflict(
        ConflictReport.Severity.YELLOW, a.targetClass, a.targetMethod, a.modId, b.modId, desc
    )
}
