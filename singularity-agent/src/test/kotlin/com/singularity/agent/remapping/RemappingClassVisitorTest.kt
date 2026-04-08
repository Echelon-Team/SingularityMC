package com.singularity.agent.remapping

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

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
}
