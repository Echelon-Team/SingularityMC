// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.bootstrap

import com.singularity.agent.remapping.MappingTable
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Loader mapping tables z plikow .tiny v2 przez mapping-io 0.7.1.
 *
 * Zwraca MappingTable z kluczami w formacie wymaganym przez Sub 2a:
 * - classes: sourceInternalName → targetInternalName
 * - methods: "sourceOwner/sourceName+descriptor" → targetName
 * - fields: "sourceOwner/sourceName" → targetName
 *
 * Uzywane przez AgentMain w Step 5 bootstrap: laduje 3 tabele z module JAR
 * (obf→mojmap, srg→mojmap, intermediary→mojmap).
 *
 * UWAGA bug fix (web-researcher review v2): `< 0` check jest WRONG bo:
 * - SRC_NAMESPACE_ID = -1 oznacza "namespace znaleziony jako source" (valid!)
 * - NULL_NAMESPACE_ID = -2 oznacza "namespace nie znaleziony" (error)
 * Plan v1 uzywal `< 0` → false positive dla source namespace → runtime throw.
 * Fix: porownanie z NULL_NAMESPACE_ID explicitnie.
 *
 * Referencja: web-researcher raport sekcja C (mapping-io API).
 */
object MappingTableLoader {

    private val logger = LoggerFactory.getLogger(MappingTableLoader::class.java)

    /**
     * Laduje .tiny v2 file do MappingTable.
     *
     * @param tinyFile sciezka do pliku .tiny v2
     * @param sourceNamespace nazwa source namespace (np. "obf", "srg", "intermediary")
     * @param targetNamespace nazwa target namespace (zwykle "mojmap")
     * @return MappingTable z zaladowanymi mappingami
     */
    fun loadTinyV2(tinyFile: Path, sourceNamespace: String, targetNamespace: String): MappingTable {
        val tree = MemoryMappingTree()
        MappingReader.read(tinyFile, MappingFormat.TINY_2_FILE, tree)

        val classes = mutableMapOf<String, String>()
        val methods = mutableMapOf<String, String>()
        val fields = mutableMapOf<String, String>()

        val srcId = tree.getNamespaceId(sourceNamespace)
        val tgtId = tree.getNamespaceId(targetNamespace)
        if (srcId == MappingTreeView.NULL_NAMESPACE_ID) {
            throw IllegalStateException("Source namespace '$sourceNamespace' not found in $tinyFile")
        }
        if (tgtId == MappingTreeView.NULL_NAMESPACE_ID) {
            throw IllegalStateException("Target namespace '$targetNamespace' not found in $tinyFile")
        }

        for (classEntry in tree.classes) {
            val srcName = classEntry.getName(srcId) ?: continue
            val tgtName = classEntry.getName(tgtId) ?: srcName
            classes[srcName] = tgtName

            for (methodEntry in classEntry.methods) {
                val srcMethodName = methodEntry.getName(srcId) ?: continue
                val tgtMethodName = methodEntry.getName(tgtId) ?: srcMethodName
                val srcDesc = methodEntry.getDesc(srcId) ?: continue
                val key = "$srcName/$srcMethodName$srcDesc"
                methods[key] = tgtMethodName
            }

            for (fieldEntry in classEntry.fields) {
                val srcFieldName = fieldEntry.getName(srcId) ?: continue
                val tgtFieldName = fieldEntry.getName(tgtId) ?: srcFieldName
                val key = "$srcName/$srcFieldName"
                fields[key] = tgtFieldName
            }
        }

        logger.info(
            "Loaded mapping table {} → {} from {}: {} classes, {} methods, {} fields",
            sourceNamespace, targetNamespace, tinyFile.fileName,
            classes.size, methods.size, fields.size
        )

        return MappingTable(
            namespace = "$sourceNamespace-to-$targetNamespace",
            classes = classes,
            methods = methods,
            fields = fields
        )
    }
}
