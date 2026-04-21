// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import java.nio.file.Path

class DataPathsTest {

    @Test
    fun `userDataDir on Windows resolves to APPDATA SingularityMC`() {
        val paths = DataPaths(
            osName = "Windows 11",
            appDataEnv = "C:\\Users\\test\\AppData\\Roaming",
            userHome = "C:\\Users\\test",
        )
        assertEquals(
            Path.of("C:\\Users\\test\\AppData\\Roaming", "SingularityMC"),
            paths.userDataDir,
        )
    }

    @Test
    fun `userDataDir on Linux resolves to XDG_CONFIG_HOME singularitymc`() {
        val paths = DataPaths(
            osName = "Linux",
            appDataEnv = null,
            userHome = "/home/test",
            xdgConfigHome = "/home/test/.config",
        )
        assertEquals(
            Path.of("/home/test/.config", "singularitymc"),
            paths.userDataDir,
        )
    }

    @Test
    fun `userDataDir on Linux without XDG_CONFIG_HOME falls back to home config`() {
        val paths = DataPaths(
            osName = "Linux",
            appDataEnv = null,
            userHome = "/home/test",
            xdgConfigHome = null,
        )
        assertEquals(
            Path.of("/home/test", ".config", "singularitymc"),
            paths.userDataDir,
        )
    }

    @Test
    fun `userDataDir on Linux treats empty XDG_CONFIG_HOME as unset`() {
        val paths = DataPaths(
            osName = "Linux",
            appDataEnv = null,
            userHome = "/home/test",
            xdgConfigHome = "",
        )
        assertEquals(
            Path.of("/home/test", ".config", "singularitymc"),
            paths.userDataDir,
        )
    }

    @Test
    fun `launcherConfigFile is userDataDir launcher_json`() {
        val paths = DataPaths(
            osName = "Windows 11",
            appDataEnv = "C:\\Users\\test\\AppData\\Roaming",
            userHome = "C:\\Users\\test",
        )
        assertEquals(
            Path.of("C:\\Users\\test\\AppData\\Roaming", "SingularityMC", "launcher.json"),
            paths.launcherConfigFile,
        )
    }

    @Test
    fun `accountsFile is userDataDir accounts_json`() {
        val paths = DataPaths(
            osName = "Linux",
            appDataEnv = null,
            userHome = "/home/test",
            xdgConfigHome = "/home/test/.config",
        )
        assertEquals(
            Path.of("/home/test/.config", "singularitymc", "accounts.json"),
            paths.accountsFile,
        )
    }

    @Test
    fun `instancesDir is userDataDir instances`() {
        val paths = windowsPaths()
        assertEquals(
            Path.of("C:\\Users\\test\\AppData\\Roaming", "SingularityMC", "instances"),
            paths.instancesDir,
        )
    }

    @Test
    fun `cacheDir is userDataDir cache`() {
        val paths = windowsPaths()
        assertEquals(
            Path.of("C:\\Users\\test\\AppData\\Roaming", "SingularityMC", "cache"),
            paths.cacheDir,
        )
    }

    @Test
    fun `logsDir is userDataDir logs`() {
        val paths = windowsPaths()
        assertEquals(
            Path.of("C:\\Users\\test\\AppData\\Roaming", "SingularityMC", "logs"),
            paths.logsDir,
        )
    }

    @Test
    fun `javaDir is userDataDir java`() {
        val paths = windowsPaths()
        assertEquals(
            Path.of("C:\\Users\\test\\AppData\\Roaming", "SingularityMC", "java"),
            paths.javaDir,
        )
    }

    @Test
    fun `Windows without APPDATA throws IllegalStateException at construction (fail-fast)`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            DataPaths(
                osName = "Windows 11",
                appDataEnv = null,
                userHome = "C:\\Users\\test",
            )
        }
        assertTrue(ex.message!!.contains("APPDATA"))
    }

    @Test
    fun `Windows with empty APPDATA throws IllegalStateException (empty treated as unset)`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            DataPaths(
                osName = "Windows 11",
                appDataEnv = "",
                userHome = "C:\\Users\\test",
            )
        }
        assertTrue(ex.message!!.contains("APPDATA"))
    }

    @Test
    fun `default factory does not throw on developer machine`() {
        // Smoke test — both Windows (APPDATA present) and Linux (XDG or HOME present)
        // in normal dev environments. Assert it can construct; specific path depends on OS.
        assertDoesNotThrow { DataPaths.default() }
    }

    private fun windowsPaths() = DataPaths(
        osName = "Windows 11",
        appDataEnv = "C:\\Users\\test\\AppData\\Roaming",
        userHome = "C:\\Users\\test",
    )
}
