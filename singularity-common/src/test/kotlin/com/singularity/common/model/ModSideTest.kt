// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.common.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModSideTest {

    @Test
    fun `all expected values exist`() {
        val values = ModSide.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(ModSide.CLIENT))
        assertTrue(values.contains(ModSide.SERVER))
        assertTrue(values.contains(ModSide.BOTH))
    }

    @Test
    fun `serialization round-trip`() {
        for (side in ModSide.entries) {
            val json = Json.encodeToString(side)
            val decoded = Json.decodeFromString<ModSide>(json)
            assertEquals(side, decoded)
        }
    }

    @Test
    fun `fromFabricEnvironment maps correctly`() {
        assertEquals(ModSide.CLIENT, ModSide.fromFabricEnvironment("client"))
        assertEquals(ModSide.SERVER, ModSide.fromFabricEnvironment("server"))
        assertEquals(ModSide.BOTH, ModSide.fromFabricEnvironment("*"))
        assertEquals(ModSide.BOTH, ModSide.fromFabricEnvironment(null))
        assertEquals(ModSide.BOTH, ModSide.fromFabricEnvironment(""))
    }

    @Test
    fun `fromForgeSide maps correctly`() {
        assertEquals(ModSide.CLIENT, ModSide.fromForgeSide("CLIENT"))
        assertEquals(ModSide.SERVER, ModSide.fromForgeSide("SERVER"))
        assertEquals(ModSide.BOTH, ModSide.fromForgeSide("BOTH"))
        assertEquals(ModSide.BOTH, ModSide.fromForgeSide(null))
        assertEquals(ModSide.BOTH, ModSide.fromForgeSide("NONE"))
    }

    @Test
    fun `isClientSide and isServerSide flags`() {
        assertTrue(ModSide.CLIENT.isClientSide)
        assertFalse(ModSide.CLIENT.isServerSide)

        assertFalse(ModSide.SERVER.isClientSide)
        assertTrue(ModSide.SERVER.isServerSide)

        assertTrue(ModSide.BOTH.isClientSide)
        assertTrue(ModSide.BOTH.isServerSide)
    }
}
