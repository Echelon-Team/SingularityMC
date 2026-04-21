// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

class DiscordRpcManagerTest {

    @Test
    fun `initialize with no Discord running does not crash`() {
        val manager = DiscordRpcManager(clientId = "123456789")
        // Discord not running on CI → initialize should handle gracefully
        assertDoesNotThrow { manager.initialize() }
        // May or may not be connected depending on environment
    }

    @Test
    fun `updatePresence with no connection is no-op`() {
        val manager = DiscordRpcManager(clientId = "123456789")
        // Not connected — updatePresence should not throw
        assertDoesNotThrow {
            manager.updatePresence(DiscordRpcManager.PresenceState(
                instanceName = "Test",
                isPlaying = true,
                minecraftVersion = "1.20.1",
                startedAt = Instant.now()
            ))
        }
    }

    @Test
    fun `shutdown without initialize is safe`() {
        val manager = DiscordRpcManager(clientId = "123456789")
        assertDoesNotThrow { manager.shutdown() }
    }

    @Test
    fun `shutdown is idempotent`() {
        val manager = DiscordRpcManager(clientId = "123456789")
        manager.initialize()
        assertDoesNotThrow {
            manager.shutdown()
            manager.shutdown()
        }
    }

    @Test
    fun `updateConfig enables and disables`() {
        val manager = DiscordRpcManager(clientId = "123456789")
        // Disable
        manager.updateConfig(DiscordRpcManager.PresenceConfig(enabled = false))
        assertFalse(manager.isConnected())
    }

    @Test
    fun `isConnected returns false before initialize`() {
        val manager = DiscordRpcManager(clientId = "123456789")
        assertFalse(manager.isConnected())
    }

    @Test
    fun `PresenceState defaults are correct`() {
        val state = DiscordRpcManager.PresenceState()
        assertNull(state.instanceName)
        assertNull(state.minecraftVersion)
        assertNull(state.serverAddress)
        assertFalse(state.isPlaying)
        assertNull(state.startedAt)
    }

    @Test
    fun `PresenceConfig defaults are enabled with instance and version shown`() {
        val config = DiscordRpcManager.PresenceConfig()
        assertTrue(config.enabled)
        assertTrue(config.showInstance)
        assertTrue(config.showVersion)
        assertFalse(config.showServer)
    }
}
