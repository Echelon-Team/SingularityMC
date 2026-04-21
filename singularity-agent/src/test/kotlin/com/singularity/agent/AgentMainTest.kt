// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.lang.instrument.Instrumentation
import java.lang.reflect.Modifier

class AgentMainTest {

    @Test
    fun `premain method exists with correct signature`() {
        val method = AgentMain::class.java.getDeclaredMethod(
            "premain",
            String::class.java,
            Instrumentation::class.java
        )
        assertNotNull(method)
        assertTrue(Modifier.isStatic(method.modifiers), "premain must be static")
        assertTrue(Modifier.isPublic(method.modifiers), "premain must be public")
        assertEquals(Void.TYPE, method.returnType, "premain must return void")
    }

    // Usuniety duplikat testu `premain method has JvmStatic annotation` —
    // @JvmStatic ma @Retention(BINARY), nie jest widoczny w runtime reflection.
    // Sam `Modifier.isStatic` z poprzedniego testu juz to pokrywa.

    @Test
    fun `AgentMain is a Kotlin object (singleton)`() {
        // Kotlin object kompiluje się do klasy z polem INSTANCE
        val instanceField = AgentMain::class.java.getDeclaredField("INSTANCE")
        assertNotNull(instanceField)
        assertTrue(Modifier.isStatic(instanceField.modifiers))
    }
}
