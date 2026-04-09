package com.singularity.common.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModMetadataTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `serialization round-trip preserves all fields`() {
        val meta = ModMetadata(
            modId = "sodium",
            version = "0.5.8+mc1.20.1",
            name = "Sodium",
            loader = LoaderType.FABRIC,
            side = ModSide.CLIENT,
            authors = listOf("JellySquid"),
            description = "Rendering engine replacement"
        )
        val encoded = json.encodeToString(meta)
        val decoded = json.decodeFromString<ModMetadata>(encoded)
        assertEquals(meta, decoded)
    }

    @Test
    fun `defaults for optional fields`() {
        val meta = ModMetadata(
            modId = "test",
            version = "1.0",
            name = "Test",
            loader = LoaderType.FORGE,
            side = ModSide.BOTH
        )
        assertEquals(emptyList<String>(), meta.authors)
        assertEquals("", meta.description)
    }
}
