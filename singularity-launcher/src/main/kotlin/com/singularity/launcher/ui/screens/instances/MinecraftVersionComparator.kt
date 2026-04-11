package com.singularity.launcher.ui.screens.instances

/**
 * Comparator dla wersji Minecraft z semver-aware semantyką.
 *
 * **Problem rozwiązywany:** Natywny `String.compareTo` daje błędne wyniki dla wersji MC,
 * bo "1.20.1" < "1.9" lexicographically (bo "2" < "9"). W rzeczywistości 1.20.1 > 1.9 bo
 * minor 20 > 9. Ten comparator parse'uje version string na numeric parts i porównuje
 * element-by-element.
 *
 * **Input format supported:**
 * - Release: "1.20.1", "1.16.5", "1.8"
 * - Snapshot / pre-release: "1.20.2-pre1", "1.21-rc1" → stripped na base version
 * - Missing trailing zero: "1.20" == "1.20.0" semantycznie
 * - Empty string: traktowany jako "0.0" (rank najniżej)
 * - Invalid / malformed: fallback na `String.compareTo` (nie rzuca)
 *
 * **NIE handluje (OK dla Sub 4):**
 * - Snapshot typ "20w10a" (non-dotted) — traktowany jako invalid → string compare
 * - Compound suffixes typ "1.20.1-forge-47.2" — strip na "1.20.1"
 *
 * Regression test dla S8 v2 (ui-reviewer) — `InstanceSortMode.MC_VERSION` wymaga tego
 * comparator zamiast natywnego string sort.
 */
class MinecraftVersionComparator : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        if (a == b) return 0
        val partsA = parseVersion(a) ?: return a.compareTo(b)  // fallback
        val partsB = parseVersion(b) ?: return a.compareTo(b)  // fallback

        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    /**
     * Parse "1.20.1" → [1, 20, 1]. "1.20.2-pre1" → [1, 20, 2] (strip suffix).
     * Zwraca null jeśli version nie matcha pattern (fallback na string compare w `compare`).
     */
    private fun parseVersion(version: String): List<Int>? {
        if (version.isEmpty()) return listOf(0, 0)
        val base = version.substringBefore('-')  // strip pre/rc/snapshot suffix
        val parts = base.split('.')
        return parts.map { it.toIntOrNull() ?: return null }
    }
}
