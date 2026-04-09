package com.singularity.agent.remapping

import org.slf4j.LoggerFactory

/**
 * Przechwytywanie i zamiana hardcoded SRG/Intermediary nazw w wywolaniach refleksji.
 *
 * Mody uzywaja refleksji z SRG (Forge: "m_5803_", "f_19794_", "func_12345_", "field_12345_")
 * lub Intermediary (Fabric: "method_5773", "field_6002", "class_1937") nazwami.
 * Nasz runtime to Mojmap — te nazwy nie istnieja. Interceptor zamienia je na Mojmap.
 *
 * Dwa tryby uzycia:
 * 1. Bytecode transform — skanujemy string constants w kodzie moda i zamieniamy na Mojmap
 * 2. Runtime interception — przechwytujemy ObfuscationReflectionHelper/MappingResolver
 *
 * UWAGA: `searchAllMethods`/`searchAllFields` zwracaja `null` — TODO do Sub 2b.
 * Pelny lookup wymaga REVERSE INDEX w MappingTable (odwrotna mapa name → mojmap
 * bez znajomosci owner). MappingTable Task 5 przechowuje klucze jako "owner/name" —
 * reverse lookup wymaga dodatkowej struktury. 2 testy z testowania lookup'u sa
 * oznaczone `@Disabled` do momentu implementacji reverse index w 2b.
 *
 * Referencja: design spec sekcja 5.3 (krok 4), sekcja 15.
 */
class ReflectionInterceptor(
    private val srgToMojmap: MappingTable,
    private val intermediaryToMojmap: MappingTable
) {
    private val logger = LoggerFactory.getLogger(ReflectionInterceptor::class.java)

    companion object {
        /** SRG stary format: func_12345_, field_12345_ */
        private val SRG_OLD_PATTERN = Regex("""^(func|field)_\d+_$""")
        /** SRG nowy format: m_12345_, f_12345_ */
        private val SRG_NEW_PATTERN = Regex("""^[mf]_\d+_$""")
        /** Intermediary: method_1234, field_1234, class_1234 (brak trailing _) */
        private val INTERMEDIARY_PATTERN = Regex("""^(method|field|class)_\d+$""")

        fun isSrgPattern(name: String): Boolean =
            SRG_OLD_PATTERN.matches(name) || SRG_NEW_PATTERN.matches(name)

        fun isIntermediaryPattern(name: String): Boolean =
            INTERMEDIARY_PATTERN.matches(name)
    }

    /**
     * Probuje zamienic nazwe metody na Mojmap.
     * Przeszukuje tabele SRG i Intermediary. Zwraca oryginal jesli nie znaleziono.
     *
     * UWAGA: lookup obecnie TODO (Sub 2b reverse index). Zwraca zawsze oryginal.
     */
    fun interceptMethodName(name: String): String {
        if (isSrgPattern(name)) {
            return searchAllMethods(srgToMojmap, name) ?: name
        }
        if (isIntermediaryPattern(name) && name.startsWith("method_")) {
            return searchAllMethods(intermediaryToMojmap, name) ?: name
        }
        return name
    }

    /**
     * Probuje zamienic nazwe pola na Mojmap.
     *
     * UWAGA: lookup obecnie TODO (Sub 2b reverse index). Zwraca zawsze oryginal.
     */
    fun interceptFieldName(name: String): String {
        if (isSrgPattern(name)) {
            return searchAllFields(srgToMojmap, name) ?: name
        }
        if (isIntermediaryPattern(name) && name.startsWith("field_")) {
            return searchAllFields(intermediaryToMojmap, name) ?: name
        }
        return name
    }

    /**
     * Szuka metody w tabeli bez znajomosci owner klasy. Uzywa reverse index
     * zbudowanego w MappingTable.init (Sub 2b Task 0.2).
     *
     * Jesli ta sama simple name mapuje na WIELE mojmap names (np. dwie rozne klasy
     * obie maja m_5803_ z roznymi mojmapami), heurystycznie zwracamy PIERWSZY
     * najczestszy mojmap. Mod ktory uzywa reflection na SRG name bez owner'a
     * juz jest w nieoczywistym terytorium — best effort.
     */
    private fun searchAllMethods(table: MappingTable, methodName: String): String? {
        val fullKeys = table.lookupMethodByName(methodName)
        if (fullKeys.isEmpty()) return null
        val mojmapCounts = mutableMapOf<String, Int>()
        for (fullKey in fullKeys) {
            val slashIdx = fullKey.lastIndexOf('/')
            val parenIdx = fullKey.indexOf('(', slashIdx + 1)
            if (slashIdx < 0 || parenIdx < 0) continue
            val owner = fullKey.substring(0, slashIdx)
            val name = fullKey.substring(slashIdx + 1, parenIdx)
            val desc = fullKey.substring(parenIdx)
            val mojmap = table.mapMethod(owner, name, desc)
            if (mojmap != name) {
                mojmapCounts[mojmap] = (mojmapCounts[mojmap] ?: 0) + 1
            }
        }
        return mojmapCounts.maxByOrNull { it.value }?.key
    }

    /**
     * Szuka pola w tabeli bez znajomosci owner klasy. Analogicznie do searchAllMethods.
     */
    private fun searchAllFields(table: MappingTable, fieldName: String): String? {
        val fullKeys = table.lookupFieldByName(fieldName)
        if (fullKeys.isEmpty()) return null
        val mojmapCounts = mutableMapOf<String, Int>()
        for (fullKey in fullKeys) {
            val slashIdx = fullKey.lastIndexOf('/')
            if (slashIdx < 0) continue
            val owner = fullKey.substring(0, slashIdx)
            val name = fullKey.substring(slashIdx + 1)
            val mojmap = table.mapField(owner, name)
            if (mojmap != name) {
                mojmapCounts[mojmap] = (mojmapCounts[mojmap] ?: 0) + 1
            }
        }
        return mojmapCounts.maxByOrNull { it.value }?.key
    }
}
