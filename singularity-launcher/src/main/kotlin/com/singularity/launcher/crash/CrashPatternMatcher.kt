// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.crash

import org.slf4j.LoggerFactory

/**
 * Klasyfikator i opisywacz crashów.
 *
 * Trójpoziomowe podejście:
 * 1. Vanilla MC / nasz kod: predefiniowane opisy dla znanych scenariuszy
 * 2. Mody: pattern matching na kontekście (NPE w klasie z Chunk/BlockState →
 *    "Mod próbował operować na bloku w niezaładowanym chunku")
 * 3. Fallback: identyfikacja moda + typ błędu + generyczne wyjaśnienie
 */
class CrashPatternMatcher {
    private val logger = LoggerFactory.getLogger(CrashPatternMatcher::class.java)

    enum class CrashCategory {
        OUT_OF_MEMORY,
        MISSING_DEPENDENCY,
        CLASS_CAST_MISMATCH,
        THREADING,
        INFINITE_RECURSION,
        RENDER_ERROR,
        MOD_BUG,
        VANILLA_BUG,
        SINGULARITY_BUG,
        UNKNOWN
    }

    fun categorize(parsed: CrashLogParser.ParsedCrash): CrashCategory {
        // 1. Exception type matching
        when {
            parsed.exceptionType.contains("OutOfMemoryError") -> return CrashCategory.OUT_OF_MEMORY
            parsed.exceptionType.contains("ClassNotFoundException") -> return CrashCategory.MISSING_DEPENDENCY
            parsed.exceptionType.contains("NoClassDefFoundError") -> return CrashCategory.MISSING_DEPENDENCY
            parsed.exceptionType.contains("ClassCastException") -> return CrashCategory.CLASS_CAST_MISMATCH
            parsed.exceptionType.contains("ConcurrentModificationException") -> return CrashCategory.THREADING
            parsed.exceptionType.contains("StackOverflowError") -> return CrashCategory.INFINITE_RECURSION
        }

        // 2. Stack trace analysis
        val topFrame = parsed.topStackFrame ?: return CrashCategory.UNKNOWN
        return when {
            topFrame.startsWith("com.singularity.") -> CrashCategory.SINGULARITY_BUG
            isRenderRelated(parsed.stackTrace) -> CrashCategory.RENDER_ERROR
            topFrame.startsWith("net.minecraft.") && parsed.stackTrace.none { isThirdParty(it) } ->
                CrashCategory.VANILLA_BUG
            // Any third-party code (not vanilla, not JDK, not singularity) → MOD_BUG
            isThirdParty(topFrame) -> CrashCategory.MOD_BUG
            else -> CrashCategory.UNKNOWN
        }
    }

    fun describe(parsed: CrashLogParser.ParsedCrash, category: CrashCategory): String {
        return when (category) {
            CrashCategory.OUT_OF_MEMORY ->
                "Gra wyczerpała dostępną pamięć RAM. To oznacza że przypisano za mało RAM dla ilości " +
                "modów i chunków które ładujesz."

            CrashCategory.MISSING_DEPENDENCY -> {
                val className = parsed.errorMessage.trim()
                "Brakuje zależności: $className. Mod wymaga biblioteki która nie jest zainstalowana."
            }

            CrashCategory.CLASS_CAST_MISMATCH ->
                "Konflikt typów klas — prawdopodobnie dwa mody dostarczają różne wersje tej samej biblioteki, " +
                "lub jeden mod oczekuje innego typu niż drugi dostarcza."

            CrashCategory.THREADING ->
                "Problem wielowątkowości — mod modyfikował kolekcję podczas iteracji z innego wątku. " +
                "Rozważ włączenie trybu kompatybilności dla tego moda."

            CrashCategory.INFINITE_RECURSION ->
                "Nieskończona rekurencja — prawdopodobnie mod wywołuje samego siebie bez warunku wyjścia."

            CrashCategory.RENDER_ERROR ->
                "Błąd renderowania — problem z shaderami, resource packiem lub driverem GPU. " +
                "Spróbuj wyłączyć shadery i resource packi."

            CrashCategory.MOD_BUG -> {
                val modId = extractModIdFromFrame(parsed.topStackFrame ?: "")
                "Błąd w modzie ${modId ?: "(nieznany)"}. ${parsed.exceptionType}: ${parsed.errorMessage}"
            }

            CrashCategory.VANILLA_BUG ->
                "Błąd w vanilla Minecraft (${parsed.exceptionType}). Może być spowodowany uszkodzonym " +
                "światem lub edge case w silniku gry."

            CrashCategory.SINGULARITY_BUG ->
                "Błąd w kodzie SingularityMC (${parsed.exceptionType}). To jest nasz bug — prosimy o zgłoszenie."

            CrashCategory.UNKNOWN ->
                "${parsed.exceptionType}: ${parsed.errorMessage}"
        }
    }

    fun suggestActions(category: CrashCategory): List<String> {
        return when (category) {
            CrashCategory.OUT_OF_MEMORY -> listOf(
                "Zwiększ przypisany RAM w ustawieniach instancji (zalecane: 6-8 GB dla 150+ modów)",
                "Usuń niektóre mody aby zredukować zużycie pamięci",
                "Zmniejsz render distance w ustawieniach gry"
            )
            CrashCategory.MISSING_DEPENDENCY -> listOf(
                "Pobierz brakującą zależność z Modrinth",
                "Sprawdź wymagania moda na jego stronie Modrinth/CurseForge",
                "Usuń mod który nie działa bez brakującej biblioteki"
            )
            CrashCategory.CLASS_CAST_MISMATCH -> listOf(
                "Sprawdź czy masz dwa mody z tym samym mod ID",
                "Spróbuj usuwać mody pojedynczo aby zidentyfikować konflikt",
                "Włącz tryb kompatybilności dla podejrzanych modów"
            )
            CrashCategory.THREADING -> listOf(
                "Włącz tryb kompatybilności (single-threaded) dla moda z błędu",
                "Zgłoś bug autorowi moda z crash logiem",
                "Sprawdź bazę kompatybilności SingularityMC czy mod jest znany jako problematyczny"
            )
            CrashCategory.RENDER_ERROR -> listOf(
                "Wyłącz shader packi",
                "Usuń resource packi i sprawdź czy problem nadal występuje",
                "Zaktualizuj sterowniki GPU",
                "Sprawdź czy GPU wspiera OpenGL 4.5+"
            )
            CrashCategory.MOD_BUG -> listOf(
                "Zaktualizuj mod do najnowszej wersji",
                "Sprawdź issue tracker moda czy problem jest znany",
                "Zgłoś bug autorowi z crash logiem",
                "Usuń mod tymczasowo"
            )
            CrashCategory.SINGULARITY_BUG -> listOf(
                "Zgłoś bug na GitHub SingularityMC z pełnym crash reportem",
                "Dołącz logi agenta z logs/agent/singularity-agent.log",
                "Spróbuj uruchomić bez modyfikacji — czy problem istnieje w czystym vanilla?"
            )
            CrashCategory.VANILLA_BUG -> listOf(
                "Przywróć backup świata",
                "Sprawdź integralność pliku world.dat",
                "Zgłoś bug na Mojang bug tracker"
            )
            CrashCategory.INFINITE_RECURSION -> listOf(
                "Znajdź mod który pojawia się w stack trace",
                "Zaktualizuj lub usuń ten mod",
                "Zgłoś bug autorowi moda"
            )
            CrashCategory.UNKNOWN -> listOf(
                "Dołącz pełny crash report do zgłoszenia",
                "Spróbuj odtworzyć problem aby zidentyfikować przyczynę"
            )
        }
    }

    private fun isModPackage(frame: String): Boolean {
        val modPrefixes = listOf(
            "com.create.", "net.fabricmc.", "net.minecraftforge.",
            "vazkii.", "sereneseasons.", "mcjty.", "slimeknights.",
            "me.jellysquid.", "dev.architectury.", "appeng.",
            "thaumcraft.", "twilightforest.", "buildcraft.",
            "com.simibubi.", "com.tterrag.", "shadows.", "fuzs."
        )
        return modPrefixes.any { frame.startsWith(it) }
    }

    private fun isThirdParty(frame: String): Boolean {
        return !frame.startsWith("net.minecraft.") &&
                !frame.startsWith("java.") &&
                !frame.startsWith("jdk.") &&
                !frame.startsWith("sun.")
    }

    private fun isRenderRelated(stack: List<String>): Boolean {
        return stack.any { frame ->
            frame.contains("Render", ignoreCase = true) ||
                    frame.contains("Shader", ignoreCase = true) ||
                    frame.contains("OpenGL", ignoreCase = true) ||
                    frame.contains("GlStateManager", ignoreCase = true)
        }
    }

    private fun extractModIdFromFrame(frame: String): String? {
        val parts = frame.split(".")
        if (parts.size < 3) return null
        return parts.getOrNull(1)
    }
}
