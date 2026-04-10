package com.singularity.agent.threading.lock

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

class LockOrderingValidatorTest {

    @BeforeEach
    fun setup() {
        LockOrderingValidator.clearCurrentThreadStack()
    }

    @AfterEach
    fun cleanup() {
        LockOrderingValidator.clearCurrentThreadStack()
    }

    @Test
    fun `acquireLock with no held locks succeeds`() {
        assertDoesNotThrow {
            LockOrderingValidator.acquireLock(LockLevel.REGION_OWNERSHIP)
        }
    }

    @Test
    fun `acquireLock in increasing order succeeds`() {
        assertDoesNotThrow {
            LockOrderingValidator.acquireLock(LockLevel.REGION_OWNERSHIP)
            LockOrderingValidator.acquireLock(LockLevel.MESSAGE_QUEUE)
            LockOrderingValidator.acquireLock(LockLevel.PER_CHUNK)
        }
    }

    @Test
    fun `acquireLock in decreasing order throws`() {
        LockOrderingValidator.acquireLock(LockLevel.MESSAGE_QUEUE)

        assertThrows(LockOrderingViolationException::class.java) {
            LockOrderingValidator.acquireLock(LockLevel.REGION_OWNERSHIP)
        }
    }

    @Test
    fun `acquireLock at same level throws`() {
        LockOrderingValidator.acquireLock(LockLevel.REGION_OWNERSHIP)

        assertThrows(LockOrderingViolationException::class.java) {
            LockOrderingValidator.acquireLock(LockLevel.REGION_OWNERSHIP)
        }
    }

    @Test
    fun `releaseLock allows re-acquiring lower level`() {
        LockOrderingValidator.acquireLock(LockLevel.PER_CHUNK)
        LockOrderingValidator.releaseLock(LockLevel.PER_CHUNK)

        assertDoesNotThrow {
            LockOrderingValidator.acquireLock(LockLevel.REGION_OWNERSHIP)
        }
    }

    @Test
    fun `currentDepth reflects held lock count`() {
        assertEquals(0, LockOrderingValidator.currentDepth())
        LockOrderingValidator.acquireLock(LockLevel.REGION_OWNERSHIP)
        assertEquals(1, LockOrderingValidator.currentDepth())
        LockOrderingValidator.acquireLock(LockLevel.MESSAGE_QUEUE)
        assertEquals(2, LockOrderingValidator.currentDepth())
        LockOrderingValidator.releaseLock(LockLevel.MESSAGE_QUEUE)
        assertEquals(1, LockOrderingValidator.currentDepth())
    }

    @Test
    fun `releaseLock with mismatched level throws`() {
        LockOrderingValidator.acquireLock(LockLevel.MESSAGE_QUEUE)

        assertThrows(LockOrderingViolationException::class.java) {
            LockOrderingValidator.releaseLock(LockLevel.REGION_OWNERSHIP)
        }
    }

    @Test
    fun `validation can be disabled in production mode`() {
        LockOrderingValidator.setEnabled(false)

        LockOrderingValidator.acquireLock(LockLevel.MESSAGE_QUEUE)
        assertDoesNotThrow {
            LockOrderingValidator.acquireLock(LockLevel.REGION_OWNERSHIP)
        }

        LockOrderingValidator.setEnabled(true)
    }
}
