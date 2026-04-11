package com.singularity.common.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LoaderTypeTest {

    @Test
    fun `all expected values exist`() {
        val values = LoaderType.entries
        assertEquals(7, values.size)
        assertTrue(values.contains(LoaderType.NONE))
        assertTrue(values.contains(LoaderType.FABRIC))
        assertTrue(values.contains(LoaderType.FORGE))
        assertTrue(values.contains(LoaderType.NEOFORGE))
        assertTrue(values.contains(LoaderType.MULTI))
        assertTrue(values.contains(LoaderType.LIBRARY))
        assertTrue(values.contains(LoaderType.UNKNOWN))
    }

    @Test
    fun `serialization round-trip`() {
        for (type in LoaderType.entries) {
            val json = Json.encodeToString(type)
            val decoded = Json.decodeFromString<LoaderType>(json)
            assertEquals(type, decoded)
        }
    }

    @Test
    fun `isMod returns true only for actual mod loaders`() {
        assertTrue(LoaderType.FABRIC.isMod)
        assertTrue(LoaderType.FORGE.isMod)
        assertTrue(LoaderType.NEOFORGE.isMod)
        assertTrue(LoaderType.MULTI.isMod)
        assertFalse(LoaderType.NONE.isMod)  // vanilla — brak moda
        assertFalse(LoaderType.LIBRARY.isMod)
        assertFalse(LoaderType.UNKNOWN.isMod)
    }
}
