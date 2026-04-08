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

    override fun toString(): String = "MappingTable($namespace, classes=${classes.size}, methods=${methods.size}, fields=${fields.size})"
}
