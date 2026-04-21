// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.remapping

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

class RemappingClassVisitorTest {

    private lateinit var engine: RemappingEngine

    @BeforeEach
    fun setup() {
        val tree = InheritanceTree()
        tree.register("obf/EntityClass", "java/lang/Object", emptyList())

        val obfTable = MappingTable(
            namespace = "obf-to-mojmap",
            classes = mapOf("obf/EntityClass" to "net/minecraft/world/entity/Entity"),
            methods = mapOf("net/minecraft/world/entity/Entity/m_tick()V" to "tick"),
            fields = mapOf("net/minecraft/world/entity/Entity/f_level" to "level")
        )

        engine = RemappingEngine(
            obfToMojmap = obfTable,
            srgToMojmap = MappingTable("srg", emptyMap(), emptyMap(), emptyMap()),
            intermediaryToMojmap = MappingTable("intermediary", emptyMap(), emptyMap(), emptyMap()),
            inheritanceTree = tree
        )
    }

    /**
     * Generuje minimalny bytecode klasy z ASM.
     */
    private fun generateTestClass(
        className: String = "obf/EntityClass",
        superName: String = "java/lang/Object"
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, superName, null)
        cw.visitField(Opcodes.ACC_PUBLIC, "f_level", "Ljava/lang/Object;", null, null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m_tick", "()V", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `remaps class name in bytecode`() {
        val original = generateTestClass()
        val remapped = RemappingClassVisitor.remap(original, engine)

        val reader = ClassReader(remapped)
        assertEquals("net/minecraft/world/entity/Entity", reader.className)
    }

    @Test
    fun `remapped bytecode is valid`() {
        val original = generateTestClass()
        val remapped = RemappingClassVisitor.remap(original, engine)

        // ClassReader nie rzuci wyjatku jesli bytecode jest poprawny
        val reader = ClassReader(remapped)
        assertNotNull(reader.className)
        assertTrue(remapped.isNotEmpty())
    }

    @Test
    fun `non-mapped class passes through unchanged`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/mymod/MyClass", null, "java/lang/Object", null)
        cw.visitEnd()
        val original = cw.toByteArray()

        val remapped = RemappingClassVisitor.remap(original, engine)
        val reader = ClassReader(remapped)
        assertEquals("com/mymod/MyClass", reader.className)
    }

    /**
     * Regression test: SKIP_FRAMES flag wczesniej byl ustawiony przez pomylke co powodowalo
     * ze writer nie pisal StackMapTable. V17 class bez StackMapTable → VerifyError na load.
     * Unit testy tego nie zlapaly bo tylko ClassReader(remapped) parsowaly — nie ladowaly
     * przez JVM. Ten test defineClass'uje bytes przez custom ClassLoader i wywoluje metoda
     * z branch (ktora requires StackMapTable) — JVM verifier rzuci VerifyError jesli frames
     * sa broken. (bytecode-safety review + protection przeciw regression.)
     */
    @Test
    fun `remapped class with branch loads and verifies through ClassLoader`() {
        // Klasa z method zawierajacym IF branch — requires StackMapTable
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "obf/BranchyClass", null, "java/lang/Object", null)

        // No-arg public constructor — potrzebny dla getDeclaredConstructor().newInstance()
        val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitCode()
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(1, 1)
        ctor.visitEnd()

        // Public method z IF branch: int chooseValue(boolean flag) { return flag ? 1 : 0 }
        // COMPUTE_FRAMES wyliczy StackMapTable automatycznie.
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "chooseValue", "(Z)I", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ILOAD, 1)       // load flag
        val elseLabel = Label()
        mv.visitJumpInsn(Opcodes.IFEQ, elseLabel)  // if (flag == 0) goto elseLabel
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(elseLabel)                  // target label → StackMapTable entry
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()
        cw.visitEnd()
        val original = cw.toByteArray()

        // Engine z mapping obf/BranchyClass → com/test/BranchyClass
        val testTree = InheritanceTree()
        testTree.register("obf/BranchyClass", "java/lang/Object", emptyList())
        val testTable = MappingTable(
            namespace = "test-branch",
            classes = mapOf("obf/BranchyClass" to "com/test/BranchyClass"),
            methods = emptyMap(),
            fields = emptyMap()
        )
        val testEngine = RemappingEngine(
            obfToMojmap = testTable,
            srgToMojmap = MappingTable("srg", emptyMap(), emptyMap(), emptyMap()),
            intermediaryToMojmap = MappingTable("intermediary", emptyMap(), emptyMap(), emptyMap()),
            inheritanceTree = testTree
        )

        val remapped = RemappingClassVisitor.remap(original, testEngine)

        // Verify przez reader ze internal name jest poprawnie zmieniony
        assertEquals("com/test/BranchyClass", ClassReader(remapped).className)

        // defineClass + newInstance + invoke — triggers JVM verifier z StackMapTable check
        val loader = object : ClassLoader() {
            fun definePublic(name: String, bytes: ByteArray): Class<*> =
                defineClass(name, bytes, 0, bytes.size)
        }
        val cls = loader.definePublic("com.test.BranchyClass", remapped)
        val instance = cls.getDeclaredConstructor().newInstance()
        val method = cls.getDeclaredMethod("chooseValue", Boolean::class.javaPrimitiveType)

        // Rzeczywiste invoke — verifier run, jesli frames broken → VerifyError
        assertEquals(1, method.invoke(instance, true))
        assertEquals(0, method.invoke(instance, false))
    }

    // -------------------------------------------------------------------------
    // Sub 2b prerequisites: method/field/descriptor/multi-ref tests
    // (flag #6 od test-quality Sub 2a review)
    //
    // UWAGA: te testy NIE korzystaja z shared `engine` z setup() bo klucze w tabelach
    // setup() zawieraja juz zremapowana nazwe klasy ("net/minecraft/...") zamiast
    // oryginalnej ("obf/..."), przez co shared engine nie remapuje method/field names.
    // Kazdy test ponizej buduje wlasny engine z poprawnymi kluczami w formacie
    // `<oryginalnyOwner>/<nazwa><deskryptor>` zgodnym z ASM Remapper API kontraktem.
    // -------------------------------------------------------------------------

    /**
     * Buduje izolowany RemappingEngine z pojedyncza tabela obf i pustymi srg/intermediary.
     */
    private fun buildEngine(
        classMappings: Map<String, String> = emptyMap(),
        methodMappings: Map<String, String> = emptyMap(),
        fieldMappings: Map<String, String> = emptyMap(),
        registerInTree: List<Triple<String, String, List<String>>> = emptyList()
    ): RemappingEngine {
        val tree = InheritanceTree()
        registerInTree.forEach { (name, parent, ifaces) -> tree.register(name, parent, ifaces) }
        val table = MappingTable(
            namespace = "test",
            classes = classMappings,
            methods = methodMappings,
            fields = fieldMappings
        )
        return RemappingEngine(
            obfToMojmap = table,
            srgToMojmap = MappingTable("srg", emptyMap(), emptyMap(), emptyMap()),
            intermediaryToMojmap = MappingTable("intermediary", emptyMap(), emptyMap(), emptyMap()),
            inheritanceTree = tree
        )
    }

    @Test
    fun `remaps method name in bytecode`() {
        val testEngine = buildEngine(
            classMappings = mapOf("obf/Target" to "net/minecraft/Target"),
            methodMappings = mapOf("obf/Target/m_tick_()V" to "tick"),
            registerInTree = listOf(Triple("obf/Target", "java/lang/Object", emptyList()))
        )

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "obf/Target", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m_tick_", "()V", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 1)
        mv.visitEnd()
        cw.visitEnd()

        val remapped = RemappingClassVisitor.remap(cw.toByteArray(), testEngine)

        val node = ClassNode()
        ClassReader(remapped).accept(node, 0)

        assertEquals("net/minecraft/Target", node.name)
        val userMethods = node.methods.filter { it.name != "<init>" && it.name != "<clinit>" }
        assertEquals(1, userMethods.size, "Expected exactly one user method after remap")
        assertEquals("tick", userMethods[0].name, "Method name should be remapped from m_tick_ to tick")
        assertEquals("()V", userMethods[0].desc)
    }

    @Test
    fun `remaps field name in bytecode`() {
        val testEngine = buildEngine(
            classMappings = mapOf("obf/Target" to "net/minecraft/Target"),
            fieldMappings = mapOf("obf/Target/f_level_" to "level"),
            registerInTree = listOf(Triple("obf/Target", "java/lang/Object", emptyList()))
        )

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "obf/Target", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PUBLIC, "f_level_", "Ljava/lang/Object;", null, null)
        cw.visitEnd()

        val remapped = RemappingClassVisitor.remap(cw.toByteArray(), testEngine)

        val node = ClassNode()
        ClassReader(remapped).accept(node, 0)

        assertEquals("net/minecraft/Target", node.name)
        assertEquals(1, node.fields.size)
        assertEquals("level", node.fields[0].name, "Field name should be remapped from f_level_ to level")
        assertEquals("Ljava/lang/Object;", node.fields[0].desc)
    }

    @Test
    fun `remaps method descriptor types`() {
        // Klasa ma metode ktorej parametr i return type sa tez klasami do remappingu.
        // ASM Remapper w base class override'uje mapDesc/mapMethodDesc/mapType ktore
        // wywoluja map() dla kazdego type reference. Remapping descriptor'a powinien
        // dzialac automatycznie bez dodatkowej logiki w EngineBackedRemapper.
        val testEngine = buildEngine(
            classMappings = mapOf(
                "obf/Target" to "net/minecraft/Target",
                "obf/Param" to "net/minecraft/Param",
                "obf/Result" to "net/minecraft/Result"
            ),
            registerInTree = listOf(
                Triple("obf/Target", "java/lang/Object", emptyList()),
                Triple("obf/Param", "java/lang/Object", emptyList()),
                Triple("obf/Result", "java/lang/Object", emptyList())
            )
        )

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "obf/Target", null, "java/lang/Object", null)

        // public Lobf/Result; transform(Lobf/Param;)
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "transform",
            "(Lobf/Param;)Lobf/Result;",
            null,
            null
        )
        mv.visitCode()
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()
        cw.visitEnd()

        val remapped = RemappingClassVisitor.remap(cw.toByteArray(), testEngine)

        val node = ClassNode()
        ClassReader(remapped).accept(node, 0)

        assertEquals("net/minecraft/Target", node.name)
        val transformMethod = node.methods.single { it.name == "transform" }
        assertEquals(
            "(Lnet/minecraft/Param;)Lnet/minecraft/Result;",
            transformMethod.desc,
            "Method descriptor should have both parameter and return type remapped"
        )
    }

    @Test
    fun `remaps multiple references to same class`() {
        // Sanity check: wielokrotne uzycia tej samej klasy w polach, metodach i lokalnych
        // sa wszystkie remapowane consistent. Chroni przed bug'iem ktory remapuje pierwszy
        // reference ale bail'uje na kolejnych.
        val testEngine = buildEngine(
            classMappings = mapOf(
                "obf/Target" to "net/minecraft/Target",
                "obf/Shared" to "net/minecraft/Shared"
            ),
            registerInTree = listOf(
                Triple("obf/Target", "java/lang/Object", emptyList()),
                Triple("obf/Shared", "java/lang/Object", emptyList())
            )
        )

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "obf/Target", null, "java/lang/Object", null)

        // 3 pola o tym samym typie
        cw.visitField(Opcodes.ACC_PUBLIC, "fieldA", "Lobf/Shared;", null, null)
        cw.visitField(Opcodes.ACC_PUBLIC, "fieldB", "Lobf/Shared;", null, null)
        cw.visitField(Opcodes.ACC_PUBLIC, "fieldC", "Lobf/Shared;", null, null)

        // Metoda biorąca i zwracająca ten sam typ
        val mv1 = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "methodOne",
            "(Lobf/Shared;)Lobf/Shared;",
            null,
            null
        )
        mv1.visitCode()
        mv1.visitVarInsn(Opcodes.ALOAD, 1)
        mv1.visitInsn(Opcodes.ARETURN)
        mv1.visitMaxs(1, 2)
        mv1.visitEnd()

        // Druga metoda — wielokrotne parametry tego samego typu
        val mv2 = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "methodTwo",
            "(Lobf/Shared;Lobf/Shared;)V",
            null,
            null
        )
        mv2.visitCode()
        mv2.visitInsn(Opcodes.RETURN)
        mv2.visitMaxs(0, 3)
        mv2.visitEnd()

        cw.visitEnd()

        val remapped = RemappingClassVisitor.remap(cw.toByteArray(), testEngine)

        val node = ClassNode()
        ClassReader(remapped).accept(node, 0)

        assertEquals("net/minecraft/Target", node.name)

        // Wszystkie 3 pola maja remapowany descriptor
        assertEquals(3, node.fields.size)
        node.fields.forEach { field ->
            assertEquals(
                "Lnet/minecraft/Shared;",
                field.desc,
                "Field ${field.name} should have remapped type"
            )
        }

        // methodOne: param i return remapowane
        val methodOne = node.methods.single { it.name == "methodOne" }
        assertEquals("(Lnet/minecraft/Shared;)Lnet/minecraft/Shared;", methodOne.desc)

        // methodTwo: oba parametry remapowane
        val methodTwo = node.methods.single { it.name == "methodTwo" }
        assertEquals("(Lnet/minecraft/Shared;Lnet/minecraft/Shared;)V", methodTwo.desc)
    }

    // -------------------------------------------------------------------------
    // AD7 guard tests: <init> / <clinit> guard w EngineBackedRemapper.mapMethodName.
    // Jesli mapping table ma wpis dla special method (bug w .tiny generator lub manualny
    // edit), zrenameowany constructor → ClassFormatError, zrenameowany clinit →
    // brak inicjalizacji klasy. Guard MUSI chronic przed takim scenariuszem.
    // -------------------------------------------------------------------------

    @Test
    fun `does not rename constructor even if mapping table has entry`() {
        val testEngine = buildEngine(
            classMappings = mapOf("obf/Target" to "net/minecraft/Target"),
            methodMappings = mapOf("obf/Target/<init>()V" to "spawn"),
            registerInTree = listOf(Triple("obf/Target", "java/lang/Object", emptyList()))
        )

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "obf/Target", null, "java/lang/Object", null)
        val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitCode()
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(1, 1)
        ctor.visitEnd()
        cw.visitEnd()

        val remapped = RemappingClassVisitor.remap(cw.toByteArray(), testEngine)
        val node = ClassNode()
        ClassReader(remapped).accept(node, 0)

        // Constructor MUSI pozostac <init>, mimo mapping entry
        val ctors = node.methods.filter { it.name == "<init>" }
        assertEquals(1, ctors.size, "Constructor must not be renamed")
        val spawnMethods = node.methods.filter { it.name == "spawn" }
        assertTrue(spawnMethods.isEmpty(), "Mapping entry for <init> must be ignored")
    }

    @Test
    fun `does not rename static initializer even if mapping table has entry`() {
        val testEngine = buildEngine(
            classMappings = mapOf("obf/Target" to "net/minecraft/Target"),
            methodMappings = mapOf("obf/Target/<clinit>()V" to "init"),
            registerInTree = listOf(Triple("obf/Target", "java/lang/Object", emptyList()))
        )

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "obf/Target", null, "java/lang/Object", null)
        val clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        clinit.visitInsn(Opcodes.RETURN)
        clinit.visitMaxs(0, 0)
        clinit.visitEnd()
        cw.visitEnd()

        val remapped = RemappingClassVisitor.remap(cw.toByteArray(), testEngine)
        val node = ClassNode()
        ClassReader(remapped).accept(node, 0)

        val clinits = node.methods.filter { it.name == "<clinit>" }
        assertEquals(1, clinits.size, "Static initializer must not be renamed")
        val initMethods = node.methods.filter { it.name == "init" }
        assertTrue(initMethods.isEmpty(), "Mapping entry for <clinit> must be ignored")
    }
}
