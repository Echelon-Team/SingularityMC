package com.singularity.agent.remapping

import java.util.concurrent.ConcurrentHashMap

/**
 * Drzewo dziedziczenia klas JVM.
 *
 * Budowane w fazie A (skan JARow przy starcie gry):
 * - Bazowy skan MC JAR (z modulu) — czyta TYLKO naglowki klas (extends/implements)
 * - Rozszerzane inkrementalnie przy kazdym class load (klasy modow)
 *
 * Uzywane przez RemappingEngine do inheritance-aware lookup:
 * gdy mapping metody nie znaleziony w klasie dziecka, traversal w gore → parent → grandparent → ...
 *
 * Thread-safe: ConcurrentHashMap, rozszerzane z wielu watkow przy parallel class loading.
 *
 * UWAGA (edge-case-hunter flag, defer Sub 2b): `getAncestors` walk jest non-atomic.
 * Reader moze zobaczyc czesciowy chain jesli parent jest rejestrowany miedzy getParent calls.
 * Dla Sub 2a uzycia (writes startup + class-load, reads podczas remap) akceptowalne.
 * Do rewizyty w Sub 2b z StampedLock lub snapshot.
 *
 * UWAGA #2 (defer Sub 2b): brak cycle detection w getAncestors. JVM class hierarchy
 * to DAG, wiec cykl niemozliwy — ale modul mogl zarejestrowac niesprawny case i
 * getAncestors zapetli. Dla Sub 2a skeleton akceptowalne.
 *
 * Referencja: design spec sekcja 5.3 (Faza A), implementation design sekcja 4.3.
 */
class InheritanceTree {

    private data class ClassInfo(
        val parent: String?,
        val interfaces: List<String>
    )

    private val classes = ConcurrentHashMap<String, ClassInfo>()

    val size: Int get() = classes.size

    /**
     * Rejestruje klase z jej parentem i interfejsami.
     *
     * @param internalName nazwa klasy (internal format: "net/minecraft/world/entity/Entity")
     * @param parentInternalName parent klasy (null dla java/lang/Object)
     * @param interfaces lista implementowanych interfejsow (internal names)
     */
    fun register(internalName: String, parentInternalName: String?, interfaces: List<String>) {
        classes[internalName] = ClassInfo(parentInternalName, interfaces)
    }

    /**
     * Zwraca bezposredni parent klasy lub null jesli klasa nie jest zarejestrowana.
     */
    fun getParent(internalName: String): String? =
        classes[internalName]?.parent

    /**
     * Zwraca liste implementowanych interfejsow lub pusta liste.
     */
    fun getInterfaces(internalName: String): List<String> =
        classes[internalName]?.interfaces ?: emptyList()

    /**
     * Zwraca pelny lancuch przodkow od bezposredniego parenta do java/lang/Object.
     * NIE zawiera samej klasy. Zwraca pusta liste jesli klasa nie jest zarejestrowana.
     */
    fun getAncestors(internalName: String): List<String> {
        val ancestors = mutableListOf<String>()
        var current = getParent(internalName)
        while (current != null) {
            ancestors.add(current)
            current = getParent(current)
        }
        return ancestors
    }

    fun isRegistered(internalName: String): Boolean =
        classes.containsKey(internalName)
}
