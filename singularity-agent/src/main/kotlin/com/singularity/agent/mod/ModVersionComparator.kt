package com.singularity.agent.mod

/**
 * Comparator wersji modów — pragmatic SemVer-like.
 *
 * Obsługiwane formaty:
 * - `MAJOR.MINOR.PATCH` (plain)
 * - `MAJOR.MINOR.PATCH-PRERELEASE` (np. `1.0.0-rc1`, `0.5.0-beta.2`)
 * - `MAJOR.MINOR.PATCH+BUILDMETA` (np. `0.5.8+mc1.20.1`) — build metadata IGNOROWANA przy porównaniu
 * - `MAJOR.MINOR.PATCH-PRERELEASE+BUILDMETA`
 * - Extra main components (np. `0.5.1.f`) — porównywane numerycznie (non-numeric → 0)
 *
 * Reguły porównania (pragmatic SemVer — nie pełna specyfikacja):
 * 1. Build metadata (po `+`) jest ignorowana.
 * 2. Main version (po kropkach) jest porównywana INTEGER-by-INTEGER (nie string).
 *    Brakujące komponenty traktowane jako 0.
 * 3. Stable > pre-release. `1.0.0` > `1.0.0-rc1`.
 * 4. Pre-release identifier: tokenizowany na sekwencje [text, num, text, num...] i porównywany
 *    token-by-token (numeric tokens jako int, text jako string). `rc10 > rc2` poprawnie.
 *
 * Wzorzec adresuje real-world bug: string sort uważa `"0.5.10" < "0.5.9"` bo `'1' < '9'`.
 * Ten comparator: `0.5.10 > 0.5.9` poprawnie.
 *
 * Przykłady:
 * - `compare("0.5.10", "0.5.9")` → `+1` (0.5.10 nowsza)
 * - `compare("1.0.0", "1.0.0-rc1")` → `+1` (stable nowsza niż pre-release)
 * - `compare("0.5.8+mc1.20.1", "0.5.7+mc1.20.1")` → `+1`
 * - `compare("0.5.1", "0.5.1")` → `0`
 *
 * NIE obsługiwane:
 * - Non-ASCII znaki w wersjach
 */
object ModVersionComparator : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        if (a == b) return 0

        val (aMain, aPre) = splitVersion(a)
        val (bMain, bPre) = splitVersion(b)

        // 1. Main version — numeric comparison
        val mainCmp = compareNumericParts(aMain, bMain)
        if (mainCmp != 0) return mainCmp

        // 2. Pre-release handling: absence > presence (stable > pre-release)
        return when {
            aPre == null && bPre == null -> 0
            aPre == null -> 1 // a stable, b pre-release → a > b
            bPre == null -> -1 // a pre-release, b stable → a < b
            else -> comparePreRelease(aPre, bPre)
        }
    }

    /**
     * Porównuje pre-release identifiers token-by-token.
     * Tokenizacja: rozdziela na sekwencje cyfr i nie-cyfr.
     * `"rc10"` → `["rc", "10"]`, `"beta.2"` → `["beta", ".", "2"]`
     *
     * Numeric tokens porównywane jako int: `"10" > "2"`.
     * Text tokens porównywane jako string: `"alpha" < "beta" < "rc"`.
     */
    private fun comparePreRelease(a: String, b: String): Int {
        val aTokens = tokenize(a)
        val bTokens = tokenize(b)
        val maxLen = maxOf(aTokens.size, bTokens.size)
        for (i in 0 until maxLen) {
            val aTok = aTokens.getOrElse(i) { "" }
            val bTok = bTokens.getOrElse(i) { "" }
            val aInt = aTok.toIntOrNull()
            val bInt = bTok.toIntOrNull()
            val cmp = when {
                aInt != null && bInt != null -> aInt.compareTo(bInt)
                else -> aTok.compareTo(bTok)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    /**
     * Tokenizuje string na sekwencje cyfr i nie-cyfr.
     * `"rc10"` → `["rc", "10"]`
     * `"beta.2"` → `["beta", ".", "2"]`
     */
    private fun tokenize(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var lastWasDigit = s[0].isDigit()
        for (ch in s) {
            val isDigit = ch.isDigit()
            if (current.isNotEmpty() && isDigit != lastWasDigit) {
                tokens.add(current.toString())
                current.clear()
            }
            current.append(ch)
            lastWasDigit = isDigit
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    /**
     * Rozdziela wersję na main version string + opcjonalny pre-release string.
     * Build metadata (po `+`) jest usunięta.
     */
    private fun splitVersion(v: String): Pair<String, String?> {
        // Strip build metadata (`+build.N`)
        val noBuild = v.substringBefore('+')
        // Split pre-release (`-rc1`)
        val dashIdx = noBuild.indexOf('-')
        return if (dashIdx < 0) {
            noBuild to null
        } else {
            noBuild.substring(0, dashIdx) to noBuild.substring(dashIdx + 1)
        }
    }

    /**
     * Porównuje dwa main version string integer-by-integer.
     * Format: `X.Y.Z[.W...]`. Brakujący komponent = 0. Non-numeric komponent = 0.
     */
    private fun compareNumericParts(a: String, b: String): Int {
        val aParts = a.split('.')
        val bParts = b.split('.')
        val maxLen = maxOf(aParts.size, bParts.size)
        for (i in 0 until maxLen) {
            val aPart = aParts.getOrNull(i) ?: "0"
            val bPart = bParts.getOrNull(i) ?: "0"
            val aInt = aPart.toIntOrNull() ?: 0
            val bInt = bPart.toIntOrNull() ?: 0
            val cmp = aInt.compareTo(bInt)
            if (cmp != 0) return cmp
        }
        return 0
    }
}
