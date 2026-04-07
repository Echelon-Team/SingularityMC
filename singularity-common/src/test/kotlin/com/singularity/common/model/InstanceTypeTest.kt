package com.singularity.common.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class InstanceTypeTest {

    @Test
    fun `ENHANCED and VANILLA values exist`() {
        val values = InstanceType.entries
        assertEquals(2, values.size)
        assertTrue(values.contains(InstanceType.ENHANCED))
        assertTrue(values.contains(InstanceType.VANILLA))
    }

    @Test
    fun `serialization produces uppercase string`() {
        val json = Json.encodeToString(InstanceType.ENHANCED)
        assertEquals("\"ENHANCED\"", json)
    }

    @Test
    fun `deserialization round-trip preserves value`() {
        val original = InstanceType.ENHANCED
        val json = Json.encodeToString(original)
        val decoded = Json.decodeFromString<InstanceType>(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `VANILLA serialization round-trip`() {
        val original = InstanceType.VANILLA
        val json = Json.encodeToString(original)
        val decoded = Json.decodeFromString<InstanceType>(json)
        assertEquals(original, decoded)
    }
}
