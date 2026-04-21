// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.pipeline

import com.singularity.agent.cache.TransformCache
import com.singularity.agent.classloader.JarRegistry
import com.singularity.agent.remapping.InheritanceTree
import com.singularity.agent.remapping.MappingTable
import com.singularity.agent.remapping.RemappingEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class SingularityTransformerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var transformer: SingularityTransformer
    private lateinit var cache: TransformCache
    private lateinit var jarRegistry: JarRegistry

    @BeforeEach
    fun setup() {
        cache = TransformCache(tempDir.resolve("cache"))
        jarRegistry = JarRegistry()
        val tree = InheritanceTree()
        val empty = MappingTable("empty", emptyMap(), emptyMap(), emptyMap())
        val engine = RemappingEngine(empty, empty, empty, tree)

        transformer = SingularityTransformer(
            jarRegistry = jarRegistry,
            remappingEngine = engine,
            transformCache = cache,
            agentVersion = "1.0.0-test",
            moduleVersion = "1.0.0-test",
            enableThreading = false,
            enableC2ME = false
        )
    }

    private fun generateClass(internalName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitCode()
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(1, 1)
        ctor.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun createJar(internalName: String, bytes: ByteArray): Path {
        val path = tempDir.resolve("${internalName.replace('/', '_')}-jar.jar")
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            jos.putNextEntry(JarEntry("$internalName.class"))
            jos.write(bytes)
            jos.closeEntry()
        }
        return path
    }

    @Test
    fun `transform returns original bytes for JDK classes (skip)`() {
        val bytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val result = transformer.transform("java/lang/String", bytes)
        assertArrayEquals(bytes, result, "JDK classes must pass-through unchanged")
    }

    @Test
    fun `transform returns original bytes for agent classes`() {
        val bytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val result = transformer.transform("com/singularity/agent/Foo", bytes)
        assertArrayEquals(bytes, result)
    }

    @Test
    fun `transform remaps non-JDK class and writes to cache`() {
        val internalName = "com/example/Target"
        val bytes = generateClass(internalName)
        jarRegistry.addJar(createJar(internalName, bytes))

        val result = transformer.transform(internalName, bytes)
        assertNotNull(result)
        assertTrue(result.isNotEmpty())

        val node = ClassNode()
        ClassReader(result).accept(node, 0)
        assertEquals(internalName, node.name)
    }

    @Test
    fun `transform cache hit returns cached bytes without re-running pipeline`() {
        val internalName = "com/example/CacheTest"
        val bytes = generateClass(internalName)
        jarRegistry.addJar(createJar(internalName, bytes))

        val first = transformer.transform(internalName, bytes)

        // Weryfikacja ze cache file istnieje na dysku
        val cacheFile = tempDir.resolve("cache").toFile().walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith(".class") }
        assertNotNull(cacheFile, "Cache file must exist after first transform")

        val second = transformer.transform(internalName, bytes)
        assertArrayEquals(first, second, "Cached bytes must be identical to first transform")
    }

    @Test
    fun `transform produces bytecode that loads and verifies through ClassLoader`() {
        // Verifier test: transformed bytes musza byc valid classfile ktory JVM verifier accepts.
        // Klasa z IF branch wymaga StackMapTable (lesson z Sub 2a SKIP_FRAMES regression).
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/verify/Test", null, "java/lang/Object", null)

        val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitCode()
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(1, 1)
        ctor.visitEnd()

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "branchy", "(Z)I", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        val elseLabel = Label()
        mv.visitJumpInsn(Opcodes.IFEQ, elseLabel)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(elseLabel)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()
        cw.visitEnd()

        val originalBytes = cw.toByteArray()
        jarRegistry.addJar(createJar("com/verify/Test", originalBytes))

        val transformedBytes = transformer.transform("com/verify/Test", originalBytes)

        // defineClass + invoke — triggers JVM verifier
        val loader = object : ClassLoader() {
            fun definePublic(name: String, bytes: ByteArray): Class<*> =
                defineClass(name, bytes, 0, bytes.size)
        }
        val cls = loader.definePublic("com.verify.Test", transformedBytes)
        val instance = cls.getDeclaredConstructor().newInstance()
        val method = cls.getDeclaredMethod("branchy", Boolean::class.javaPrimitiveType)
        assertEquals(1, method.invoke(instance, true))
        assertEquals(0, method.invoke(instance, false))
    }

    @Test
    fun `transform handles concurrent calls without corruption (cache safety)`() {
        // Flag 10 z test-quality: concurrent put/get test dla TransformCache via transformer
        val internalName = "com/example/Concurrent"
        val bytes = generateClass(internalName)
        jarRegistry.addJar(createJar(internalName, bytes))

        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val results = mutableListOf<ByteArray>()

        try {
            repeat(threadCount) {
                executor.submit {
                    try {
                        val result = transformer.transform(internalName, bytes)
                        synchronized(results) { results.add(result) }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
        } finally {
            // Musimy AWAIT shutdown zanim JUnit @TempDir sprobuje delete cache files.
            // Na Windows bez tego: race → open handle → DirectoryNotEmptyException w test teardown.
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            // Windows NTFS trzyma lock chwilę po close() — awaitTermination nie wystarcza
            // bo FileOutputStream/BufferedOutputStream w writeBytes() są już closed ale
            // Windows delay NTFS lock release. System.gc() wymusza finalizery + krótki sleep
            // daje NTFS czas na zwolnienie lock. Bez tego @TempDir cleanup flaky na Windows
            // z DirectoryNotEmptyException (memory sub2b_learnings #14).
            System.gc()
            Thread.sleep(200)
        }

        assertEquals(threadCount, results.size)
        // Wszystkie wyniki musza byc identyczne (pipeline deterministic)
        val first = results[0]
        for (i in 1 until results.size) {
            assertArrayEquals(first, results[i], "Concurrent transform produced different bytes")
        }
    }

    @Test
    fun `transform throws on invalid bytes instead of silent fallback`() {
        // Edge-case-hunter flag 5: silent null return = NoSuchMethodError deep w runtime.
        // Zamiast: throw RuntimeException z context aby crash byl czytelny.
        jarRegistry.addJar(createJar("com/example/Valid", generateClass("com/example/Valid")))
        val corrupted = byteArrayOf(0x00, 0x00, 0x00, 0x00)  // invalid magic number
        assertThrows(Exception::class.java) {
            transformer.transform("com/example/Corrupted", corrupted)
        }
    }

    @Test
    fun `transform remaps obf class using forward mapping produces bytes with mojmap internal name`() {
        // Bytecode-safety FIX-1: poprzednie testy uzywaly empty mappings → passthrough tylko.
        // Ten test weryfikuje faktyczny forward remap flow: obf class name → mojmap przez pipeline.
        val originalName = "obf/Target"
        val mojmapName = "net/minecraft/Target"
        val obfBytes = generateClass(originalName)
        jarRegistry.addJar(createJar(originalName, obfBytes))

        // Buduj forward mapping engine
        val forwardTree = InheritanceTree()
        forwardTree.register(originalName, "java/lang/Object", emptyList())
        val forwardTable = MappingTable(
            "forward",
            classes = mapOf(originalName to mojmapName),
            methods = emptyMap(),
            fields = emptyMap()
        )
        val forwardEngine = RemappingEngine(
            forwardTable,
            MappingTable("srg", emptyMap(), emptyMap(), emptyMap()),
            MappingTable("intermediary", emptyMap(), emptyMap(), emptyMap()),
            forwardTree
        )

        // Transformer z forward engine (nie shared empty setup)
        val forwardTransformer = SingularityTransformer(
            jarRegistry = jarRegistry,
            remappingEngine = forwardEngine,
            transformCache = TransformCache(tempDir.resolve("forward-cache")),
            agentVersion = "1.0.0-test",
            moduleVersion = "1.0.0-test"
        )

        // Transform wywolany z mojmapName (tak jak SingularityClassLoader.findClass dziala po reverseResolve)
        val result = forwardTransformer.transform(mojmapName, obfBytes)

        // Weryfikacja: wynikowy bytecode MA mojmap internal name (ClassRemapper w step 2-3 zmienil)
        val node = ClassNode()
        ClassReader(result).accept(node, 0)
        assertEquals(
            mojmapName,
            node.name,
            "Transformer MUSI zmienic internal name z obf/Target na net/minecraft/Target " +
            "(ClassRemapper w step 2-3 pipeline) — bez tego defineClass z mojmap nazwa zrzuci ClassFormatError"
        )
    }
}
