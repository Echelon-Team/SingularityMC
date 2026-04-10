package com.singularity.agent.threading.proxy

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.slf4j.LoggerFactory

/**
 * Bytecode transformer dla net.minecraft.world.level.Level.
 *
 * Design spec 4.5 warstwa 2B (World Access Proxy):
 * - Transparentna warstwa miedzy kodem gry/modow a danymi swiata
 * - Mody wolaja vanilla API (getBlockState, setBlockState) — proxy przechwytuje
 * - Routing: pos w biezacym regionie → pass-through, sasiad odczyt → snapshot,
 *   sasiad zapis → message queue
 *
 * Implementacja: ASM MethodVisitor wstawia INVOKESTATIC do WorldAccessProxyDispatcher
 * na poczatku przechwytywanych metod.
 */
object WorldAccessProxyTransformer {

    private val logger = LoggerFactory.getLogger(WorldAccessProxyTransformer::class.java)

    private const val LEVEL_INTERNAL_NAME = "net/minecraft/world/level/Level"
    private const val DISPATCHER_INTERNAL_NAME = "com/singularity/agent/threading/proxy/WorldAccessProxyDispatcher"

    private val INTERCEPTED_METHODS = setOf(
        "getBlockState",
        "setBlockState",
        "getBlockEntity",
        "getCollisions"
    )

    fun isLevelClass(internalName: String): Boolean = internalName == LEVEL_INTERNAL_NAME

    fun transformLevel(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        if (!isLevelClass(reader.className)) {
            return classBytes
        }

        logger.info("Transforming {} — injecting WorldAccessProxy hooks", reader.className)

        val writer = ClassWriter(reader, 0)
        val visitor = LevelClassVisitor(writer)
        reader.accept(visitor, 0)
        return writer.toByteArray()
    }

    private class LevelClassVisitor(cv: ClassVisitor) : ClassVisitor(Opcodes.ASM9, cv) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            if (name in INTERCEPTED_METHODS) {
                return InterceptedMethodVisitor(mv, name)
            }
            return mv
        }
    }

    private class InterceptedMethodVisitor(
        mv: MethodVisitor,
        private val methodName: String
    ) : MethodVisitor(Opcodes.ASM9, mv) {
        override fun visitCode() {
            super.visitCode()
            // WorldAccessProxyDispatcher.beforeAccess(this, methodName)
            mv.visitVarInsn(Opcodes.ALOAD, 0) // this
            mv.visitLdcInsn(methodName)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                DISPATCHER_INTERNAL_NAME,
                "beforeAccess",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false
            )
        }
    }
}
