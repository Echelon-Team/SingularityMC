// Copyright (c) 2026 Echelon Team. All rights reserved.

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

    /**
     * Reverse index: parent → zbior bezposrednich children (Sub 2b Task 0.3).
     * Budowany inkrementalnie przy kazdym register() wywolaniu.
     * Uzywany przez ParallelTreeTransformer do parent→child top-down traversal.
     *
     * Thread-safe: ConcurrentHashMap<String, MutableSet<String>> z ConcurrentHashMap.newKeySet()
     * dla inner set (thread-safe dodawanie children z wielu watkow).
     */
    private val reverseChildrenIndex = ConcurrentHashMap<String, MutableSet<String>>()

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
        // Sub 2b Task 0.3: reverse children index — parent → set of direct children.
        // Budujemy inkrementalnie przy kazdej rejestracji dziecka.
        if (parentInternalName != null) {
            reverseChildrenIndex
                .getOrPut(parentInternalName) { ConcurrentHashMap.newKeySet() }
                .add(internalName)
        }
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
     *
     * Sub 2b Task 0.3: cycle detection przez `visited` set. JVM class hierarchy to DAG,
     * wiec cykl jest niemozliwy w normalnym runtime — ale bug w module (manualny edit)
     * lub race condition moze spowodowac cykl. Bez cycle detection getAncestors zawisa
     * w nieskonczonej petli (flag #12).
     */
    fun getAncestors(internalName: String): List<String> {
        val ancestors = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current = getParent(internalName)
        while (current != null) {
            if (!visited.add(current)) {
                // Cycle detected — break walk (zwracamy co do tej pory zebrane)
                break
            }
            ancestors.add(current)
            current = getParent(current)
        }
        return ancestors
    }

    fun isRegistered(internalName: String): Boolean =
        classes.containsKey(internalName)

    /**
     * Zwraca liste bezposrednich dzieci (klasy ktore rozszerzaja podany parent).
     * Nie zawiera grandchildren. Jesli klasa nie ma dzieci lub nie jest zarejestrowana,
     * zwraca pusta liste.
     *
     * Uzywane przez ParallelTreeTransformer (Sub 2b Task 9) do parent→child traversal.
     *
     * @param parentInternalName internal name parent klasy
     * @return snapshot (copy) listy bezposrednich children — safe dla concurrent mod
     */
    fun getChildren(parentInternalName: String): List<String> =
        reverseChildrenIndex[parentInternalName]?.toList() ?: emptyList()

    /**
     * Zwraca zbior WSZYSTKICH zarejestrowanych klas w drzewie (snapshot).
     * Uzywany przez ParallelTreeTransformer do iteracji po wszystkich klasach
     * podczas bootstrap transform.
     */
    fun getAllRegisteredClasses(): Set<String> = classes.keys.toSet()
}
