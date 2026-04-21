// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.remapping

/**
 * In-memory lookup table dla mappingow: sourceName → targetName.
 *
 * Trzy instancje w runtime (ladowane z plikow .tiny w module JAR):
 * - obf→Mojmap (klasy MC — deobfuskacja)
 * - SRG→Mojmap (klasy modow Forge/NeoForge)
 * - Intermediary→Mojmap (klasy modow Fabric)
 *
 * Klucze w formacie internal JVM (np. "net/minecraft/world/level/Level", nie z kropkami).
 *
 * Referencja: design spec sekcja 15, implementation design sekcja 4.3-4.4.
 */
class MappingTable(
    /** Identyfikator namespace, np. "obf-to-mojmap" */
    val namespace: String,

    /** Mapping klas: internalName → mojmapInternalName */
    private val classes: Map<String, String>,

    /**
     * Mapping metod: "owner/methodName(descriptor)" → mojmapMethodName.
     * Klucz zawiera owner (po remappingu klas), oryginalna nazwe metody i deskryptor.
     */
    private val methods: Map<String, String>,

    /** Mapping pol: "owner/fieldName" → mojmapFieldName */
    private val fields: Map<String, String>
) {
    /** Laczna liczba wpisow we wszystkich trzech mapach. */
    val size: Int get() = classes.size + methods.size + fields.size

    /**
     * Reverse method index: simple method name (np. "m_5803_") → lista pełnych kluczy
     * w formacie "owner/methodNameDescriptor". Uzywane przez ReflectionInterceptor
     * do szukania wszystkich wystapien SRG/Intermediary method name bez znajomosci owner.
     *
     * Buildowany w init block raz — CPU kosztowne raz, zero kosztu lookup'ow.
     */
    private val reverseMethodIndex: Map<String, List<String>>

    /**
     * Reverse field index analogicznie do reverseMethodIndex.
     * Klucz: simple field name, wartosc: lista pelnych kluczy "owner/fieldName".
     */
    private val reverseFieldIndex: Map<String, List<String>>

    /**
     * Reverse class index: mojmap internal name → original internal name.
     * Uzywany przez RemappingEngine.reverseResolveClass dla SingularityClassLoader
     * reverse lookup (mody Forge/Fabric maja klasy w JAR pod SRG/Intermediary nazwami,
     * ale loadClass dostaje mojmap — musimy mapowac back).
     */
    private val reverseClassIndex: Map<String, String>

    init {
        // Build reverseMethodIndex: klucz "owner/name(desc)" → parse simple name
        val methodIdx = mutableMapOf<String, MutableList<String>>()
        for (fullKey in methods.keys) {
            val slashIdx = fullKey.lastIndexOf('/')
            val parenIdx = fullKey.indexOf('(', slashIdx + 1)
            if (slashIdx < 0 || parenIdx < 0) continue  // malformed, skip
            val simpleName = fullKey.substring(slashIdx + 1, parenIdx)
            methodIdx.getOrPut(simpleName) { mutableListOf() }.add(fullKey)
        }
        reverseMethodIndex = methodIdx

        // Build reverseFieldIndex: klucz "owner/name" → parse simple name
        val fieldIdx = mutableMapOf<String, MutableList<String>>()
        for (fullKey in fields.keys) {
            val slashIdx = fullKey.lastIndexOf('/')
            if (slashIdx < 0) continue
            val simpleName = fullKey.substring(slashIdx + 1)
            fieldIdx.getOrPut(simpleName) { mutableListOf() }.add(fullKey)
        }
        reverseFieldIndex = fieldIdx

        // Build reverseClassIndex: mojmap → original (swap classes map)
        reverseClassIndex = classes.entries.associate { (original, mojmap) -> mojmap to original }
    }

    /**
     * Remapuje nazwe klasy. Zwraca oryginalna nazwe jesli nie znaleziona w tabeli.
     * @param internalName nazwa klasy w formacie internal (np. "a" lub "net/minecraft/class_1937")
     */
    fun mapClass(internalName: String): String =
        classes.getOrDefault(internalName, internalName)

    /**
     * Remapuje nazwe metody. Zwraca oryginalna nazwe jesli nie znaleziona.
     * @param ownerInternalName owner klasy w formacie internal
     * @param methodName oryginalna nazwa metody (np. "m_46748_")
     * @param descriptor deskryptor metody (np. "(Lnet/minecraft/core/BlockPos;)Z")
     */
    fun mapMethod(ownerInternalName: String, methodName: String, descriptor: String): String {
        val key = "$ownerInternalName/$methodName$descriptor"
        return methods.getOrDefault(key, methodName)
    }

    /**
     * Remapuje nazwe pola. Zwraca oryginalna nazwe jesli nie znaleziona.
     * @param ownerInternalName owner klasy w formacie internal
     * @param fieldName oryginalna nazwa pola (np. "field_9236")
     */
    fun mapField(ownerInternalName: String, fieldName: String): String {
        val key = "$ownerInternalName/$fieldName"
        return fields.getOrDefault(key, fieldName)
    }

    /**
     * Sprawdza czy tabela zawiera mapping dla danej klasy.
     */
    fun hasClass(internalName: String): Boolean = classes.containsKey(internalName)

    /**
     * Zwraca liste WSZYSTKICH full keys (owner/methodNameDescriptor) ktore maja
     * podana simple method name. Uzywane przez ReflectionInterceptor dla reverse lookup.
     *
     * @param simpleName sama nazwa metody (np. "m_5803_", bez owner i descriptor)
     * @return lista full keys lub pusta lista
     */
    fun lookupMethodByName(simpleName: String): List<String> =
        reverseMethodIndex[simpleName] ?: emptyList()

    /**
     * Zwraca liste full keys (owner/fieldName) ktore maja podana simple field name.
     */
    fun lookupFieldByName(simpleName: String): List<String> =
        reverseFieldIndex[simpleName] ?: emptyList()

    /**
     * Reverse class lookup: mojmap internal name → original (pre-remap) internal name.
     * Uzywany przez SingularityClassLoader do znalezienia klasy w JAR gdy dostaniemy
     * mojmap name (np. "net/minecraft/world/entity/Entity") ale w JAR Fabric moda
     * klasa jest jako "net/minecraft/class_1297".
     *
     * @return original internal name lub null jesli mojmap nie jest w tej tabeli
     */
    fun lookupOriginalClass(mojmapInternalName: String): String? =
        reverseClassIndex[mojmapInternalName]

    override fun toString(): String = "MappingTable($namespace, classes=${classes.size}, methods=${methods.size}, fields=${fields.size})"
}
