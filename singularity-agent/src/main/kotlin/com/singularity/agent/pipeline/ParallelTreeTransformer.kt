// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.pipeline

import com.singularity.agent.remapping.InheritanceTree
import org.slf4j.LoggerFactory
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RecursiveAction
import java.util.concurrent.TimeUnit

/**
 * Rownolegla transformacja klas po drzewach dziedziczenia z dedicated ForkJoinPool.
 *
 * Dlaczego NIE `ForkJoinPool.commonPool()`:
 * commonPool jest wspoldzielony z calym JVM. Jesli MC (lub mod) uzywa parallelStream,
 * to blokuje common pool → nasz transform czeka → ich transform czeka → deadlock.
 * Transform robi I/O (JarFile read, cache write) — dokladnie ten antipattern.
 * (edge-case-hunter web-confirmed, sources: DZone + Sonatype docs)
 *
 * Algorytm:
 * 1. Dla kazdego root class w rootClasses, tworzymy TreeTransformAction
 * 2. Action transformuje siebie (parent), potem fork dla wszystkich children
 * 3. Work-stealing: skonczony watek kradnie subtree z innego
 * 4. Parent ZAWSZE transformowany PRZED children (inheritance-aware remapping wymaga tego)
 *
 * Exception handling: exceptions z transform lambda sa propagowane przez ForkJoinTask.join()
 * do wywolujacego (nie silent swallow).
 *
 * Referencja: design spec sekcja 5.3 (Faza B), AD5 w plan v2.3.
 */
object ParallelTreeTransformer {

    private val logger = LoggerFactory.getLogger(ParallelTreeTransformer::class.java)

    /**
     * Transformuje wszystkie klasy z podanych korzeni drzew, zachowujac parent→child order.
     *
     * @param inheritanceTree pelne drzewo dziedziczenia z zarejestrowanymi parent-child relacjami
     * @param rootClasses korzenie drzew do transformacji (np. Entity, Block, Item dla MC)
     * @param transform callback dla kazdej klasy (wywolywany z thread z dedicated pool)
     */
    fun transformAll(
        inheritanceTree: InheritanceTree,
        rootClasses: List<String>,
        transform: (String) -> Unit
    ) {
        if (rootClasses.isEmpty()) return

        // Dedicated pool — izolowany od commonPool, parallelism = CPU count
        val pool = ForkJoinPool(Runtime.getRuntime().availableProcessors())
        try {
            val tasks = rootClasses.map { root ->
                TreeTransformAction(root, inheritanceTree, transform)
            }
            // Submit wszystkie roots
            tasks.forEach { pool.execute(it) }
            // Join wszystkie — propagation exception'ow
            tasks.forEach { it.join() }

            logger.info("Parallel tree transform complete: {} root trees", rootClasses.size)
        } finally {
            pool.shutdown()
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("ParallelTreeTransformer pool did not terminate cleanly")
                pool.shutdownNow()
            }
        }
    }

    private class TreeTransformAction(
        private val className: String,
        private val tree: InheritanceTree,
        private val transform: (String) -> Unit
    ) : RecursiveAction() {

        override fun compute() {
            // 1. Transformuj self (parent first)
            transform(className)

            // 2. Znajdz children przez reverse index w InheritanceTree (Task 0.3)
            val children = tree.getChildren(className)
            if (children.isEmpty()) return

            if (children.size == 1) {
                // Jeden child — sekwencyjnie w tym samym watku (unikamy overhead forkowania)
                TreeTransformAction(children[0], tree, transform).compute()
            } else {
                // Multiple children — fork (work-stealing przez JVM FJP)
                val subTasks = children.map { TreeTransformAction(it, tree, transform) }
                invokeAll(subTasks)
            }
        }
    }
}
