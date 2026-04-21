// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.bootstrap

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MappingTableLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Tworzy valid .tiny v2 mapping file z dwoma namespaces: source + target.
     * Format:
     * tiny\t2\t0\tsource\ttarget
     * c\tsource/Class\ttarget/Class
     */
    private fun createTinyV2File(
        name: String,
        sourceNs: String,
        targetNs: String,
        classes: List<Pair<String, String>>  // (sourceClass, targetClass)
    ): Path {
        val path = tempDir.resolve(name)
        val sb = StringBuilder()
        sb.appendLine("tiny\t2\t0\t$sourceNs\t$targetNs")
        for ((src, tgt) in classes) {
            sb.appendLine("c\t$src\t$tgt")
        }
        Files.writeString(path, sb.toString())
        return path
    }

    @Test
    fun `loadTinyV2 parses class mappings to MappingTable`() {
        val tinyFile = createTinyV2File(
            "obf.tiny",
            "obf", "mojmap",
            listOf(
                "a" to "net/minecraft/world/entity/Entity",
                "b" to "net/minecraft/world/level/Level"
            )
        )

        val table = MappingTableLoader.loadTinyV2(tinyFile, "obf", "mojmap")
        assertEquals("net/minecraft/world/entity/Entity", table.mapClass("a"))
        assertEquals("net/minecraft/world/level/Level", table.mapClass("b"))
    }

    @Test
    fun `loadTinyV2 throws for missing file`() {
        val missingPath = tempDir.resolve("missing.tiny")
        assertThrows(Exception::class.java) {
            MappingTableLoader.loadTinyV2(missingPath, "obf", "mojmap")
        }
    }

    @Test
    fun `loadTinyV2 handles empty mapping file`() {
        val emptyFile = tempDir.resolve("empty.tiny")
        Files.writeString(emptyFile, "tiny\t2\t0\tobf\tmojmap\n")
        val table = MappingTableLoader.loadTinyV2(emptyFile, "obf", "mojmap")
        assertEquals(0, table.size)
    }
}
