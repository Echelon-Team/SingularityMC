// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.bootstrap

import com.singularity.agent.remapping.InheritanceTree
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Skanuje MC JAR po naglowkach klas (parent + interfaces), buduje InheritanceTree.
 *
 * Optymalizacja: `ClassReader.accept(visitor, SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES)` —
 * czytamy TYLKO naglowek klasy, nie bytecode metod. 10x szybciej niz full parse.
 * Dla 30k+ klas MC to rozznica 10+ sekund vs 1 sekunda startup time.
 *
 * Uzywane przez AgentMain w Step 7 bootstrap sequence.
 */
object McJarScanner {

    private val logger = LoggerFactory.getLogger(McJarScanner::class.java)

    /**
     * Skanuje JAR i dodaje klasy do InheritanceTree. Zwraca liczbe zarejestrowanych klas.
     */
    fun scanInto(jarPath: Path, tree: InheritanceTree): Int {
        var count = 0
        JarFile(jarPath.toFile()).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (!entry.name.endsWith(".class")) continue
                if (entry.name.startsWith("META-INF/")) continue

                try {
                    val reader = jar.getInputStream(entry).use { input ->
                        ClassReader(input)
                    }
                    val visitor = HeaderVisitor(tree)
                    reader.accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    count++
                } catch (e: Exception) {
                    logger.warn("Failed to scan {}: {}", entry.name, e.message)
                }
            }
        }
        logger.info("Scanned {} classes from {}", count, jarPath.fileName)
        return count
    }

    private class HeaderVisitor(private val tree: InheritanceTree) : ClassVisitor(Opcodes.ASM9) {
        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            tree.register(
                internalName = name,
                parentInternalName = superName,
                interfaces = interfaces?.toList() ?: emptyList()
            )
        }
    }
}
