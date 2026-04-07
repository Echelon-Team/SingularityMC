package com.singularity.common.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class InstanceConfigTest {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true  // domyslne wartosci musza byc w JSON (dla instance.json na dysku)
    }

    @Test
    fun `serialization round-trip preserves all fields`() {
        val config = InstanceConfig(
            name = "Test Instance",
            minecraftVersion = "1.20.1",
            type = InstanceType.ENHANCED,
            ramMb = 8192,
            threads = 12,
            jvmArgs = "-XX:+UseZGC"
        )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<InstanceConfig>(encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun `default ramMb is 4096`() {
        val config = InstanceConfig(
            name = "Defaults",
            minecraftVersion = "1.20.1",
            type = InstanceType.VANILLA
        )
        assertEquals(4096, config.ramMb)
    }

    @Test
    fun `default threads is 4`() {
        val config = InstanceConfig(
            name = "Defaults",
            minecraftVersion = "1.20.1",
            type = InstanceType.VANILLA
        )
        assertEquals(4, config.threads)
    }

    @Test
    fun `default jvmArgs is empty string`() {
        val config = InstanceConfig(
            name = "Defaults",
            minecraftVersion = "1.20.1",
            type = InstanceType.ENHANCED
        )
        assertEquals("", config.jvmArgs)
    }

    @Test
    fun `blank name is accepted at model layer (validation deferred to service)`() {
        // InstanceConfig to data class — walidacja jest w warstwie serwisowej,
        // nie w modelu. Model pozwala na pusty string, service layer (Sub 4:
        // InstanceManager) odrzuca.
        val config = InstanceConfig(
            name = "",
            minecraftVersion = "1.20.1",
            type = InstanceType.VANILLA
        )
        assertEquals("", config.name)
    }

    @Test
    fun `JSON output contains expected keys`() {
        val config = InstanceConfig(
            name = "My Instance",
            minecraftVersion = "1.20.1",
            type = InstanceType.ENHANCED
        )
        val encoded = json.encodeToString(config)
        assertTrue(encoded.contains("\"name\""))
        assertTrue(encoded.contains("\"minecraftVersion\""))
        assertTrue(encoded.contains("\"type\""))
        assertTrue(encoded.contains("\"ramMb\""))
        assertTrue(encoded.contains("\"threads\""))
        assertTrue(encoded.contains("\"jvmArgs\""))
    }

    @Test
    fun `deserialization with missing optional fields uses defaults`() {
        val minimal = """{"name":"Minimal","minecraftVersion":"1.20.1","type":"ENHANCED"}"""
        val decoded = Json.decodeFromString<InstanceConfig>(minimal)
        assertEquals("Minimal", decoded.name)
        assertEquals(4096, decoded.ramMb)
        assertEquals(4, decoded.threads)
        assertEquals("", decoded.jvmArgs)
    }
}
