package com.singularity.agent.module

import com.singularity.common.contracts.MappingFiles
import com.singularity.common.contracts.ModuleDescriptorData
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class ModuleDescriptorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val validJson = """
    {
        "moduleId": "compat-1.20.1",
        "moduleVersion": "1.0.0",
        "minecraftVersion": "1.20.1",
        "supportedLoaders": ["fabric", "forge", "neoforge"],
        "requiredContracts": ["metadata", "remapping", "loader_emulation", "bridges", "hooks"],
        "entrypoint": "com.singularity.compat.v1_20_1.CompatModule1201",
        "mappingFiles": {
            "obfToMojmap": "mappings/obf-to-mojmap.tiny",
            "srgToMojmap": "mappings/srg-to-mojmap.tiny",
            "intermediaryToMojmap": "mappings/intermediary-to-mojmap.tiny"
        }
    }
    """.trimIndent()

    @Test
    fun `parse valid module descriptor`() {
        val descriptor = json.decodeFromString<ModuleDescriptorData>(validJson)
        assertEquals("compat-1.20.1", descriptor.moduleId)
        assertEquals("1.0.0", descriptor.moduleVersion)
        assertEquals("1.20.1", descriptor.minecraftVersion)
        assertEquals(setOf("fabric", "forge", "neoforge"), descriptor.supportedLoaders)
        assertEquals(5, descriptor.requiredContracts.size)
        assertTrue(descriptor.requiredContracts.contains("metadata"))
        assertTrue(descriptor.requiredContracts.contains("remapping"))
    }

    @Test
    fun `mapping files have correct default paths`() {
        val minimalJson = """
        {
            "moduleId": "test",
            "moduleVersion": "0.1.0",
            "minecraftVersion": "1.20.1",
            "supportedLoaders": ["fabric"],
            "requiredContracts": ["metadata"],
            "entrypoint": "com.singularity.compat.v1_20_1.CompatModule1201"
        }
        """.trimIndent()
        val descriptor = json.decodeFromString<ModuleDescriptorData>(minimalJson)
        assertEquals("mappings/obf-to-mojmap.tiny", descriptor.mappingFiles.obfToMojmap)
        assertEquals("mappings/srg-to-mojmap.tiny", descriptor.mappingFiles.srgToMojmap)
        assertEquals("mappings/intermediary-to-mojmap.tiny", descriptor.mappingFiles.intermediaryToMojmap)
    }

    @Test
    fun `serialization round-trip preserves data`() {
        val original = ModuleDescriptorData(
            moduleId = "compat-1.20.1",
            moduleVersion = "2.0.0",
            minecraftVersion = "1.20.1",
            supportedLoaders = setOf("fabric", "forge"),
            requiredContracts = setOf("metadata", "remapping"),
            entrypoint = "com.singularity.compat.v1_20_1.CompatModule1201",
            mappingFiles = MappingFiles(
                obfToMojmap = "custom/obf.tiny",
                srgToMojmap = "custom/srg.tiny",
                intermediaryToMojmap = "custom/intermediary.tiny"
            )
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ModuleDescriptorData>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown fields in JSON are ignored`() {
        val jsonWithExtra = """
        {
            "moduleId": "test",
            "moduleVersion": "1.0.0",
            "minecraftVersion": "1.20.1",
            "supportedLoaders": ["fabric"],
            "requiredContracts": ["metadata"],
            "entrypoint": "com.singularity.compat.v1_20_1.CompatModule1201",
            "futureField": "some value",
            "anotherFuture": 42
        }
        """.trimIndent()
        val descriptor = json.decodeFromString<ModuleDescriptorData>(jsonWithExtra)
        assertEquals("test", descriptor.moduleId)
    }

    @Test
    fun `missing required field throws SerializationException`() {
        // test-quality feedback: Exception was too broad — assertion passes
        // nawet jesli NPE w test setup blow up. Narrow to kotlinx.serialization.SerializationException
        // (MissingFieldException dziedziczy po SerializationException, wiec to zlapie oba).
        val incomplete = """{"moduleId": "test"}"""
        assertThrows<kotlinx.serialization.SerializationException> {
            Json.decodeFromString<ModuleDescriptorData>(incomplete)
        }
    }
}
