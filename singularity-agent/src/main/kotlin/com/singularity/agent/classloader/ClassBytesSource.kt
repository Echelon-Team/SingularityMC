package com.singularity.agent.classloader

import java.nio.file.Path

/**
 * Wynik lookup'u w JarRegistry — raw bytes klasy plus metadata o JAR z ktorego pochodzi.
 *
 * Uzywane przez SingularityClassLoader przy findClass: najpierw JarRegistry.findClassBytes(),
 * potem ClassBytesSource.bytes jest przekazywany do pipeline transform, jarHash uzywany
 * jako czesc cache key.
 *
 * @property bytes raw bytecode klasy (pre-transform)
 * @property jarHash SHA-256 first 8 bytes JAR'a (16 hex chars) do cache key
 * @property jarPath path JAR'a (dla diagnostyki + log messages)
 */
data class ClassBytesSource(
    val bytes: ByteArray,
    val jarHash: String,
    val jarPath: Path
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClassBytesSource) return false
        return jarHash == other.jarHash && jarPath == other.jarPath && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + jarHash.hashCode()
        result = 31 * result + jarPath.hashCode()
        return result
    }
}
