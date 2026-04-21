// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.proxy

import org.objectweb.asm.*
import org.slf4j.LoggerFactory

/**
 * Bytecode transformer for net.minecraft.world.level.Level.
 *
 * ADDENDUM v2 FULL REWRITE — method replacement pattern:
 * 1. Rename original methods to $original (e.g., getBlockState → getBlockState$original)
 * 2. Create new dispatch methods that check region → call $original (in-region) or snapshot (cross-region)
 *
 * This pattern preserves Mixin modifications (they operate on the renamed $original body)
 * and allows dispatch to intercept return values (unlike the old void beforeAccess hook).
 *
 * Transform ONLY Level — ServerLevel inherits these methods and doesn't override them.
 * ClientLevel (rendering) is NOT transformed — rendering is single-threaded.
 */
object WorldAccessProxyTransformer {

    private val logger = LoggerFactory.getLogger(WorldAccessProxyTransformer::class.java)

    private const val LEVEL_INTERNAL_NAME = "net/minecraft/world/level/Level"
    private const val DISPATCHER_INTERNAL_NAME =
        "com/singularity/agent/threading/proxy/WorldAccessProxyDispatcher"

    /**
     * Methods to intercept: name → JVM descriptor.
     * Each gets renamed to name$original, with a new dispatch method taking its place.
     */
    val INTERCEPTED_METHODS = mapOf(
        "getBlockState" to "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        "setBlockState" to "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        "getBlockEntity" to "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"
    )

    fun isLevelClass(internalName: String): Boolean = internalName == LEVEL_INTERNAL_NAME

    fun transformLevel(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        if (!isLevelClass(reader.className)) return classBytes

        logger.info("Transforming {} — method replacement for WorldAccessProxy", reader.className)

        // Phase 1: Rename intercepted methods to $original
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        val renamer = MethodRenamerVisitor(writer)
        reader.accept(renamer, 0)

        // Phase 2: Add dispatch methods
        for ((name, desc) in INTERCEPTED_METHODS) {
            addDispatchMethod(writer, name, desc)
        }

        return writer.toByteArray()
    }

    /**
     * ASM ClassVisitor that renames intercepted methods to name$original.
     */
    private class MethodRenamerVisitor(cv: ClassVisitor) : ClassVisitor(Opcodes.ASM9, cv) {
        override fun visitMethod(
            access: Int, name: String, descriptor: String,
            signature: String?, exceptions: Array<out String>?
        ): MethodVisitor? {
            if (name in INTERCEPTED_METHODS && INTERCEPTED_METHODS[name] == descriptor) {
                // Rename: getBlockState → getBlockState$original
                return super.visitMethod(access, "${name}\$original", descriptor, signature, exceptions)
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions)
        }
    }

    /**
     * Adds a dispatch method that:
     * 1. Calls WorldAccessProxyDispatcher.dispatch(this, methodName, pos)
     * 2. If non-null → return snapshot value (cross-region read)
     * 3. If null → call this.name$original(args) (in-region, fast-path)
     */
    private fun addDispatchMethod(cw: ClassWriter, name: String, desc: String) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null)
        mv.visitCode()

        // Object result = WorldAccessProxyDispatcher.dispatch(this, name, pos)
        mv.visitVarInsn(Opcodes.ALOAD, 0) // this (Level)
        mv.visitLdcInsn(name)              // method name string
        mv.visitVarInsn(Opcodes.ALOAD, 1)  // first arg (BlockPos)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, DISPATCHER_INTERNAL_NAME,
            "dispatch",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
            false
        )

        // if (result != null) → return cast result
        mv.visitInsn(Opcodes.DUP)
        val labelOriginal = Label()
        mv.visitJumpInsn(Opcodes.IFNULL, labelOriginal)

        // Return snapshot value — cast to return type
        val returnType = desc.substringAfterLast(')')
        if (returnType.startsWith("L")) {
            val internalType = returnType.substring(1, returnType.length - 1)
            mv.visitTypeInsn(Opcodes.CHECKCAST, internalType)
        }
        mv.visitInsn(Opcodes.ARETURN)

        // Original path: pop null, call $original
        mv.visitLabel(labelOriginal)
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, arrayOf("java/lang/Object"))
        mv.visitInsn(Opcodes.POP)
        mv.visitVarInsn(Opcodes.ALOAD, 0) // this
        mv.visitVarInsn(Opcodes.ALOAD, 1) // pos (BlockPos)

        // For setBlockState: also load state (arg2) and flags (arg3)
        if (name == "setBlockState") {
            mv.visitVarInsn(Opcodes.ALOAD, 2) // BlockState
            mv.visitVarInsn(Opcodes.ILOAD, 3) // flags (int)
        }

        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, LEVEL_INTERNAL_NAME,
            "${name}\$original", desc, false
        )

        // Return based on return type
        if (desc.endsWith("Z")) {
            mv.visitInsn(Opcodes.IRETURN) // boolean
        } else {
            mv.visitInsn(Opcodes.ARETURN) // object
        }

        mv.visitMaxs(5, 5)
        mv.visitEnd()
    }
}
