package com.singularity.agent.threading.proxy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.objectweb.asm.*

class WorldAccessProxyTransformerTest {

    @Test
    fun `isLevelClass identifies vanilla Level`() {
        assertTrue(WorldAccessProxyTransformer.isLevelClass("net/minecraft/world/level/Level"))
        assertFalse(WorldAccessProxyTransformer.isLevelClass("com/example/NotLevel"))
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
    fun `transformed Level has original methods renamed to $original`() {
        val levelBytes = createFakeLevelClass()
        val transformed = WorldAccessProxyTransformer.transformLevel(levelBytes)

        val node = org.objectweb.asm.tree.ClassNode()
        ClassReader(transformed).accept(node, 0)

        val methodNames = node.methods.map { it.name }
        assertTrue(methodNames.contains("getBlockState\$original"),
            "Expected getBlockState\$original, got: $methodNames")
    }

    @Test
    fun `transformed Level has new dispatch getBlockState method`() {
        val levelBytes = createFakeLevelClass()
        val transformed = WorldAccessProxyTransformer.transformLevel(levelBytes)

        val node = org.objectweb.asm.tree.ClassNode()
        ClassReader(transformed).accept(node, 0)

        val methodNames = node.methods.map { it.name }
        assertTrue(methodNames.contains("getBlockState"),
            "Expected dispatch getBlockState, got: $methodNames")
        // Both original (renamed) and dispatch (new) should exist
        assertTrue(methodNames.contains("getBlockState\$original"))
    }

    @Test
    fun `dispatch method calls WorldAccessProxyDispatcher`() {
        val levelBytes = createFakeLevelClass()
        val transformed = WorldAccessProxyTransformer.transformLevel(levelBytes)

        val node = org.objectweb.asm.tree.ClassNode()
        ClassReader(transformed).accept(node, 0)

        val dispatchMethod = node.methods.find { it.name == "getBlockState" && !it.name.contains("\$") }
        assertNotNull(dispatchMethod)

        // Check that it contains INVOKESTATIC to dispatch
        val insns = dispatchMethod!!.instructions
        var foundDispatchCall = false
        for (i in 0 until insns.size()) {
            val insn = insns[i]
            if (insn is org.objectweb.asm.tree.MethodInsnNode &&
                insn.opcode == Opcodes.INVOKESTATIC &&
                insn.name == "dispatch") {
                foundDispatchCall = true
                break
            }
        }
        assertTrue(foundDispatchCall, "Dispatch method should call WorldAccessProxyDispatcher.dispatch()")
    }

    private fun createFakeLevelClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/world/level/Level",
            null, "java/lang/Object", null)

        // Add getBlockState method
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getBlockState",
            "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }
}
