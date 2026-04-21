// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.lock

import org.slf4j.LoggerFactory

class LockOrderingViolationException(message: String) : RuntimeException(message)

/**
 * Validator hierarchii locków — wykrywa ABBA deadlocki PRZED ich zaistnieniem.
 *
 * Design spec 4.5A:
 * - Każdy wątek śledzi stack trzymanych locków
 * - Próba wzięcia locka o niższym lub równym poziomie → natychmiastowy crash
 *
 * W produkcji można wyłączyć (setEnabled(false)). Narzut minimalny (ThreadLocal stack).
 *
 * LIMITATIONS:
 * - Thread-local only — tracks what CURRENT thread holds, NOT cross-thread ABBA detection
 * - Does NOT track implicit locks: ConcurrentHashMap.computeIfAbsent bin locks,
 *   synchronized blocks not annotated with LockLevel
 * - Runtime sanity check, not a formal deadlock detector
 * - For cross-thread deadlock detection, use JVM thread dump analysis (jstack)
 */
object LockOrderingValidator {

    private val logger = LoggerFactory.getLogger(LockOrderingValidator::class.java)

    @Volatile
    private var enabled = true

    private val heldLocks = ThreadLocal.withInitial { ArrayDeque<LockLevel>() }

    fun setEnabled(value: Boolean) { enabled = value }
    fun isEnabled(): Boolean = enabled

    fun acquireLock(newLevel: LockLevel) {
        if (!enabled) return

        val stack = heldLocks.get()
        val current = stack.lastOrNull()

        if (current != null && newLevel.level <= current.level) {
            val msg = "Lock ordering violation: trying to acquire $newLevel (level ${newLevel.level}) " +
                    "while holding $current (level ${current.level}). " +
                    "Held stack: ${stack.toList()}. Thread: ${Thread.currentThread().name}"
            logger.error(msg)
            throw LockOrderingViolationException(msg)
        }

        stack.addLast(newLevel)
    }

    fun releaseLock(level: LockLevel) {
        if (!enabled) return

        val stack = heldLocks.get()
        val top = stack.lastOrNull()

        if (top != level) {
            val msg = "Lock release mismatch: releasing $level but top of stack is $top. " +
                    "Stack: ${stack.toList()}. Thread: ${Thread.currentThread().name}"
            logger.error(msg)
            throw LockOrderingViolationException(msg)
        }

        stack.removeLast()
    }

    fun currentDepth(): Int = heldLocks.get().size

    fun clearCurrentThreadStack() {
        heldLocks.get().clear()
    }
}
