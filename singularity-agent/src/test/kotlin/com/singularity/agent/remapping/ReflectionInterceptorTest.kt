// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.remapping

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ReflectionInterceptorTest {

    private val srgTable = MappingTable(
        namespace = "srg-to-mojmap",
        classes = emptyMap(),
        methods = mapOf("net/minecraft/world/entity/Entity/m_5803_()V" to "tick"),
        fields = mapOf("net/minecraft/world/entity/Entity/f_19794_" to "level")
    )

    private val interceptor = ReflectionInterceptor(
        srgToMojmap = srgTable,
        intermediaryToMojmap = MappingTable("intermediary", emptyMap(), emptyMap(), emptyMap())
    )

    @Test
    fun `intercept SRG method name m_digits_`() {
        // Sub 2b Task 0.2: reverse index wired — searchAllMethods uzywa lookupMethodByName
        assertEquals("tick", interceptor.interceptMethodName("m_5803_"))
    }

    @Test
    fun `intercept SRG field name f_digits_`() {
        // Sub 2b Task 0.2: reverse index wired — searchAllFields uzywa lookupFieldByName
        assertEquals("level", interceptor.interceptFieldName("f_19794_"))
    }

    @Test
    fun `non-SRG name passes through`() {
        assertEquals("myCustomMethod", interceptor.interceptMethodName("myCustomMethod"))
    }

    @Test
    fun `intercept returns original when no mapping exists in empty tables`() {
        // Sub 2b Task 0.2: edge case — SRG pattern match ale brak w reverse index.
        // searchAllMethods zwraca null → interceptMethodName zwraca oryginal.
        val emptyInterceptor = ReflectionInterceptor(
            srgToMojmap = MappingTable("srg", emptyMap(), emptyMap(), emptyMap()),
            intermediaryToMojmap = MappingTable("intermediary", emptyMap(), emptyMap(), emptyMap())
        )
        assertEquals("m_99999_", emptyInterceptor.interceptMethodName("m_99999_"))
    }

    @Test
    fun `isSrgPattern detects old format func_digits_`() {
        assertTrue(ReflectionInterceptor.isSrgPattern("func_12345_"))
    }

    @Test
    fun `isSrgPattern detects new format m_digits_`() {
        assertTrue(ReflectionInterceptor.isSrgPattern("m_5803_"))
    }

    @Test
    fun `isSrgPattern detects field format f_digits_`() {
        assertTrue(ReflectionInterceptor.isSrgPattern("f_19794_"))
    }

    @Test
    fun `isSrgPattern rejects normal names`() {
        assertFalse(ReflectionInterceptor.isSrgPattern("tick"))
        assertFalse(ReflectionInterceptor.isSrgPattern("getLevel"))
        assertFalse(ReflectionInterceptor.isSrgPattern("SOME_CONSTANT"))
    }

    @Test
    fun `isIntermediaryPattern detects method_digits`() {
        assertTrue(ReflectionInterceptor.isIntermediaryPattern("method_5773"))
    }

    @Test
    fun `isIntermediaryPattern detects field_digits`() {
        assertTrue(ReflectionInterceptor.isIntermediaryPattern("field_6002"))
    }

    @Test
    fun `isIntermediaryPattern detects class_digits`() {
        assertTrue(ReflectionInterceptor.isIntermediaryPattern("class_1937"))
    }

    @Test
    fun `isIntermediaryPattern rejects normal names`() {
        assertFalse(ReflectionInterceptor.isIntermediaryPattern("tick"))
        assertFalse(ReflectionInterceptor.isIntermediaryPattern("methodology"))
    }
}
