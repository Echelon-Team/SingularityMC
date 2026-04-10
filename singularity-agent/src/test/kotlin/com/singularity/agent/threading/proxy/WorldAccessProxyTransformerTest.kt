package com.singularity.agent.threading.proxy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

class WorldAccessProxyTransformerTest {

    private fun generateLevelClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            "net/minecraft/world/level/Level",
            null,
            "java/lang/Object",
            null
        )

        val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitCode()
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(1, 1)
        ctor.visitEnd()

        val getBlock = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getBlockState",
            "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            null, null
        )
        getBlock.visitCode()
        getBlock.visitInsn(Opcodes.ACONST_NULL)
        getBlock.visitInsn(Opcodes.ARETURN)
        getBlock.visitMaxs(1, 2)
        getBlock.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `transformer accepts Level class and returns valid bytecode`() {
        val original = generateLevelClass()
        val transformed = WorldAccessProxyTransformer.transformLevel(original)

        assertNotNull(transformed)
        assertTrue(transformed.isNotEmpty())

        val reader = ClassReader(transformed)
        assertEquals("net/minecraft/world/level/Level", reader.className)
    }

    @Test
    fun `transformed Level class still has original methods`() {
        val original = generateLevelClass()
        val transformed = WorldAccessProxyTransformer.transformLevel(original)

        val node = ClassNode()
        ClassReader(transformed).accept(node, 0)

        val getBlockState = node.methods.find { it.name == "getBlockState" }
        assertNotNull(getBlockState)
    }

    @Test
    fun `transformer is no-op for non-Level classes`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/NotLevel", null, "java/lang/Object", null)
        cw.visitEnd()
        val original = cw.toByteArray()

        val transformed = WorldAccessProxyTransformer.transformLevel(original)
        assertArrayEquals(original, transformed)
    }

    @Test
    fun `isLevelClass identifies vanilla Level by internal name`() {
        assertTrue(WorldAccessProxyTransformer.isLevelClass("net/minecraft/world/level/Level"))
        assertFalse(WorldAccessProxyTransformer.isLevelClass("com/example/NotLevel"))
        assertFalse(WorldAccessProxyTransformer.isLevelClass("java/lang/String"))
    }
}
