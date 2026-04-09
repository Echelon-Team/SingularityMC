package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Parsuje META-INF/mods.toml (Forge/NeoForge) do ModInfo.
 *
 * Ręczny state-machine parser — format Forge TOML jest wystarczająco prosty.
 * Rozpoznawane sekcje:
 * - [[mods]] — metadane moda (modId, version, displayName, authors, description)
 * - [[dependencies.<modId>]] — zależności (modId, mandatory, versionRange, side)
 * - [[mixins]] — mixin configs (config)
 *
 * Obsługiwane features:
 * - single-line quoted strings: `"value"` lub `'value'`
 * - multi-line strings: `"""..."""` lub `'''...'''` (używane w description realnych Forge mods)
 * - komentarze inline: `# comment` na początku linii
 * - obie konwencje: `key=value` i `key = value`
 *
 * Na MC 1.20.1 format Forge i NeoForge jest identyczny.
 *
 * Referencja: design spec sekcja 5A.2, 5A.7.
 */
object ForgeMetadataParser {

    private val logger = LoggerFactory.getLogger(ForgeMetadataParser::class.java)

    private enum class Section { ROOT, MODS, DEPENDENCIES, MIXINS }

    /**
     * Parsuje surowy TOML mods.toml do ModInfo.
     *
     * @param rawToml surowa zawartość META-INF/mods.toml
     * @param jarPath ścieżka do JAR moda
     * @return ModInfo z zunifikowanymi metadanymi
     * @throws IllegalArgumentException jeśli brak modId w [[mods]]
     */
    fun parse(rawToml: String, jarPath: Path): ModInfo {
        // Strip UTF-8 BOM (defensive — TOML spec disallows but Windows editors may add it)
        val cleanedToml = rawToml.removePrefix("\uFEFF")

        var currentSection = Section.ROOT

        var modId: String? = null
        var version = "0.0.0"
        var displayName: String? = null
        var authors = ""
        var description = ""
        var secondaryModsSeen = false

        val dependencies = mutableListOf<ModDependency>()
        val mixinConfigs = mutableListOf<String>()

        // Tymczasowe dane aktualnie parsowanej zależności
        var depModId: String? = null
        var depMandatory = true
        var depVersionRange: String? = null

        fun flushDependency() {
            if (depModId != null) {
                dependencies.add(
                    ModDependency(
                        modId = depModId!!,
                        versionRange = depVersionRange,
                        required = depMandatory
                    )
                )
                depModId = null
                depMandatory = true
                depVersionRange = null
            }
        }

        // Preprocess: collapse multi-line strings (`"""..."""` / `'''...'''`) into
        // single-line key=value pairs. Real Forge 1.20.1 mods frequently use
        // triple-quoted strings for `description`.
        val collapsedLines = collapseMultilineStrings(cleanedToml)

        for (line in collapsedLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // Detekcja sekcji
            when {
                trimmed == "[[mods]]" -> {
                    flushDependency()
                    // Real Forge 1.20.1 JARy zwykle mają TYLKO jeden [[mods]] per JAR.
                    // Multi-mod JAR (rzadko — np. coreMod + mod) — plan 2c zakłada single mod
                    // per JAR. Jeśli zobaczymy drugi, logujemy warning i bierzemy OSTATNI
                    // (last-wins). Pełna obsługa wielu [[mods]] per JAR to TODO Sub 5.
                    if (modId != null) {
                        secondaryModsSeen = true
                        logger.warn(
                            "Multiple [[mods]] blocks in {} — first modId '{}' will be overwritten. " +
                                "Multi-mod JARs are TODO Sub 5.",
                            jarPath.fileName, modId
                        )
                    }
                    currentSection = Section.MODS
                    continue
                }
                trimmed.startsWith("[[dependencies.") -> {
                    flushDependency()
                    currentSection = Section.DEPENDENCIES
                    continue
                }
                trimmed == "[[mixins]]" -> {
                    flushDependency()
                    currentSection = Section.MIXINS
                    continue
                }
                trimmed.startsWith("[[") -> {
                    flushDependency()
                    currentSection = Section.ROOT
                    continue
                }
                trimmed.startsWith("[") && !trimmed.startsWith("[[") -> {
                    // Single-bracket sections like [general] — also exit per-mod scope
                    flushDependency()
                    currentSection = Section.ROOT
                    continue
                }
            }

            // Parsowanie key=value
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val key = trimmed.substring(0, eqIdx).trim()
            val rawValue = trimmed.substring(eqIdx + 1).trim().stripInlineComment()
            val value = unquote(rawValue)

            when (currentSection) {
                Section.MODS -> when (key) {
                    "modId" -> modId = value
                    "version" -> version = value
                    "displayName" -> displayName = value
                    "authors" -> authors = value
                    "description" -> description = value
                }
                Section.DEPENDENCIES -> when (key) {
                    "modId" -> {
                        flushDependency()
                        depModId = value
                    }
                    "mandatory" -> depMandatory = value.lowercase() == "true"
                    "versionRange" -> depVersionRange = value
                }
                Section.MIXINS -> when (key) {
                    "config" -> mixinConfigs.add(value)
                }
                Section.ROOT -> { /* ignoruj top-level poza sekcjami */ }
            }
        }

        // Flush ostatnia zależność
        flushDependency()

        if (modId == null) {
            throw IllegalArgumentException("mods.toml missing modId in [[mods]] section")
        }

        // Forge allows `authors="Alice, Bob, Carol"` — comma-separated string, not array.
        // Split and trim each author (filter blank — handles trailing commas).
        // Also handle TOML array syntax `["Alice","Bob"]` — strip brackets, split, unquote.
        val authorList = splitAuthors(authors)

        // Niezalecane multi-mod fallback: loguj że użyliśmy tylko jeden (ostatni)
        if (secondaryModsSeen) {
            logger.warn("Forge JAR {} has multiple [[mods]] — using last only (modId={})", jarPath.fileName, modId)
        }

        logger.debug(
            "Parsed Forge mod: {} v{} ({} deps, {} mixins)",
            modId, version, dependencies.size, mixinConfigs.size
        )

        return ModInfo(
            modId = modId,
            version = version,
            name = displayName ?: modId,
            loaderType = LoaderType.FORGE,
            dependencies = dependencies,
            entryPoints = emptyList(), // Forge nie ma entrypointów jak Fabric
            mixinConfigs = mixinConfigs,
            authors = authorList,
            description = description,
            side = ModSide.BOTH, // Forge mod-level side jest zawsze BOTH; side w dependencies to per-dep
            jarPath = jarPath
        )
    }

    /**
     * Zbiera wielolinijkowe stringi (`"""..."""` lub `'''...'''`) w pojedyncze linie
     * `key="joined value"`. Wszystkie linie bez multi-line marker'ów pozostają bez zmian.
     *
     * Input przykład:
     * ```
     * description='''A very long
     * multi-line description.'''
     * version="1.0"
     * ```
     * Output:
     * ```
     * description="A very long\nmulti-line description."
     * version="1.0"
     * ```
     */
    private fun collapseMultilineStrings(rawToml: String): List<String> {
        val result = mutableListOf<String>()
        val input = rawToml.lines()
        var i = 0
        while (i < input.size) {
            val line = input[i]
            val trimmed = line.trim()

            // Sprawdz czy linia zawiera key=value z otwarciem multi-line
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val valueStart = trimmed.substring(eqIdx + 1).trimStart()
                val tripleQuote = detectTripleQuoteStart(valueStart)
                if (tripleQuote != null) {
                    // Multi-line otwarta: sprawdź czy zamknięta w tej samej linii
                    val afterOpen = valueStart.substring(3)
                    val closingIdx = afterOpen.indexOf(tripleQuote)
                    if (closingIdx >= 0) {
                        // Zamknięta w tej samej linii — emit jako single-line
                        val content = afterOpen.substring(0, closingIdx)
                        val key = trimmed.substring(0, eqIdx).trim()
                        result.add("$key=\"${escapeForQuoted(content)}\"")
                        i++
                        continue
                    }
                    // Multi-line — akumuluj do zamknięcia
                    val key = trimmed.substring(0, eqIdx).trim()
                    val accumulated = StringBuilder(afterOpen)
                    var j = i + 1
                    var closed = false
                    while (j < input.size) {
                        val nextLine = input[j]
                        val closeIdx = nextLine.indexOf(tripleQuote)
                        if (closeIdx >= 0) {
                            accumulated.append('\n').append(nextLine.substring(0, closeIdx))
                            closed = true
                            j++
                            break
                        }
                        accumulated.append('\n').append(nextLine)
                        j++
                    }
                    if (closed) {
                        result.add("$key=\"${escapeForQuoted(accumulated.toString())}\"")
                    } else {
                        // Niezamknięta — graceful: emit co mamy
                        result.add("$key=\"${escapeForQuoted(accumulated.toString())}\"")
                        logger.warn("Unclosed multi-line string in mods.toml for key '{}'", key)
                    }
                    i = j
                    continue
                }
            }

            result.add(line)
            i++
        }
        return result
    }

    /**
     * Zwraca triple-quote marker (`"""` lub `'''`) jeśli string startuje jednym z nich,
     * w przeciwnym razie null.
     */
    private fun detectTripleQuoteStart(s: String): String? = when {
        s.startsWith("\"\"\"") -> "\"\"\""
        s.startsWith("'''") -> "'''"
        else -> null
    }

    /**
     * Usuwa surrounding cudzysłowy (single lub double) z wartości.
     */
    private fun unquote(s: String): String = when {
        s.length >= 2 && s.startsWith("\"") && s.endsWith("\"") -> s.substring(1, s.length - 1)
        s.length >= 2 && s.startsWith("'") && s.endsWith("'") -> s.substring(1, s.length - 1)
        else -> s
    }

    /**
     * Usuwa inline komentarz po wartości, np. `"foo"  # comment` → `"foo"`.
     * UWAGA: prosty parser — nie obsługuje `#` wewnątrz quoted string (rzadkie w Forge).
     */
    private fun String.stripInlineComment(): String {
        // Nie obcinaj jesli string jest quoted i # jest wewnatrz
        if (this.startsWith("\"") || this.startsWith("'")) {
            val quote = this[0]
            val closingIdx = this.indexOf(quote, startIndex = 1)
            if (closingIdx >= 0) {
                // Po zamknieciu cudzyslowu zbieraj tylko do `#` lub konca
                val afterClose = this.substring(0, closingIdx + 1)
                val tail = this.substring(closingIdx + 1).trimStart()
                return if (tail.startsWith("#")) afterClose else this
            }
        }
        val hashIdx = this.indexOf('#')
        return if (hashIdx >= 0) this.substring(0, hashIdx).trim() else this
    }

    /**
     * Escape'uje newlines i backslashe w string tak żeby wartość mogła być ponownie
     * wrzucona do "..." quoted line. Po unquote wrócimy do oryginalnej formy.
     */
    private fun escapeForQuoted(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Parsuje authors field — Forge pozwala na:
     * - single author string: `"Alice"`
     * - comma-separated string: `"Alice, Bob, Carol"`
     * - TOML array syntax: `["Alice","Bob"]` (rzadko)
     */
    internal fun splitAuthors(authors: String): List<String> {
        if (authors.isBlank()) return emptyList()

        // Strip TOML array brackets if present
        val stripped = authors.trim().let {
            if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1) else it
        }

        return stripped.split(',')
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'").trim() }
            .filter { it.isNotBlank() }
    }
}
