package com.singularity.agent.remapping

import org.slf4j.LoggerFactory

/**
 * Silnik remappingu nazw klas/metod/pol.
 *
 * Przyjmuje trzy mapping tables (obf→Mojmap, SRG→Mojmap, Intermediary→Mojmap)
 * i drzewo dziedziczenia. Dla podanej nazwy przeszukuje WSZYSTKIE tabele,
 * z inheritance-aware fallback (lookup w parent jesli nie znaleziono w child).
 *
 * Uzywany przez RemappingClassVisitor (ASM pipeline, Task 8) do transformacji
 * bytecode'u przy ladowaniu klas.
 *
 * UWAGA (edge-case-hunter perf flag, defer Sub 2b): `resolveMethod`/`resolveField`
 * alokuja nowa liste per call (`mutableListOf(owner) + ancestors`). Pod heavy class-load
 * traffic (thousands of calls during MC startup) to powoduje GC pressure.
 * Optymalizacja w Sub 2b: cache resolved lookup per class, albo reuse ThreadLocal list.
 *
 * UWAGA #2 (design clarification defer Sub 2b): table priority jest `obf > srg > intermediary`
 * dla kazdego levelu inheritance. Child class ZAWSZE beats ancestor (inner loop is tables,
 * outer is classes). Dla MC classes (obf) to dziala. Dla Forge/Fabric mod classes ktore
 * dziedza po MC — OK bo owner-level match first. Ale jesli mod class i MC class dziela
 * to samo method name w roznych tabelach, kolejnosc jest istotna. Flag dla review w 2b
 * gdy real mapping data bedzie loaded.
 *
 * Referencja: design spec sekcja 15, implementation design sekcja 4.3.
 */
class RemappingEngine(
    private val obfToMojmap: MappingTable,
    private val srgToMojmap: MappingTable,
    private val intermediaryToMojmap: MappingTable,
    private val inheritanceTree: InheritanceTree
) {
    private val logger = LoggerFactory.getLogger(RemappingEngine::class.java)

    private val allTables = listOf(obfToMojmap, srgToMojmap, intermediaryToMojmap)

    /**
     * Remapuje nazwe klasy. Przeszukuje tabele w kolejnosci obf → srg → intermediary.
     */
    fun resolveClass(internalName: String): String {
        for (table in allTables) {
            if (table.hasClass(internalName)) {
                return table.mapClass(internalName)
            }
        }
        return internalName
    }

    /**
     * Remapuje nazwe metody z inheritance-aware fallback.
     *
     * Kolejnosc: szukaj w klasie owner → parent → grandparent → ... az do java/lang/Object.
     * Na kazdym poziomie: sprawdz WSZYSTKIE tabele mappingow.
     */
    fun resolveMethod(ownerInternalName: String, methodName: String, descriptor: String): String {
        // Szukaj w klasie i jej przodkach
        val classesToCheck = mutableListOf(ownerInternalName)
        classesToCheck.addAll(inheritanceTree.getAncestors(ownerInternalName))

        for (className in classesToCheck) {
            for (table in allTables) {
                val mapped = table.mapMethod(className, methodName, descriptor)
                if (mapped != methodName) {
                    return mapped
                }
            }
        }

        return methodName
    }

    /**
     * Remapuje nazwe pola z inheritance-aware fallback.
     */
    fun resolveField(ownerInternalName: String, fieldName: String): String {
        val classesToCheck = mutableListOf(ownerInternalName)
        classesToCheck.addAll(inheritanceTree.getAncestors(ownerInternalName))

        for (className in classesToCheck) {
            for (table in allTables) {
                val mapped = table.mapField(className, fieldName)
                if (mapped != fieldName) {
                    return mapped
                }
            }
        }

        return fieldName
    }

    override fun toString(): String =
        "RemappingEngine(obf=${obfToMojmap.size}, srg=${srgToMojmap.size}, intermediary=${intermediaryToMojmap.size}, tree=${inheritanceTree.size} classes)"
}
