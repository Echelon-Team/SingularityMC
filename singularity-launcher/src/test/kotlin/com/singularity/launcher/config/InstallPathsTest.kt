// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path

class InstallPathsTest {

    @Test
    fun `current with jpackage app-path returns Packaged with parent dir`() {
        val result = InstallPaths.current(
            jpackageAppPath = "/opt/SingularityMC/bin/SingularityMC",
            userDir = "/home/test",
        )
        assertTrue(result is InstallLocation.Packaged)
        assertEquals(Path.of("/opt/SingularityMC/bin"), result.path)
    }

    @Test
    fun `current without jpackage app-path returns DevFallback with user_dir`() {
        val result = InstallPaths.current(
            jpackageAppPath = null,
            userDir = "/home/test/projects/launcher",
        )
        assertTrue(result is InstallLocation.DevFallback)
        assertEquals(Path.of("/home/test/projects/launcher"), result.path)
    }

    @Test
    fun `current with root-level jpackage path falls back to DevFallback`() {
        // Edge case: jpackage.app-path at filesystem root has null parent.
        val result = InstallPaths.current(
            jpackageAppPath = "/",  // Path.of("/").parent == null
            userDir = "/home/test",
        )
        assertTrue(
            result is InstallLocation.DevFallback,
            "Root-level jpackage path (no parent) must fall back, not crash",
        )
    }

    @Test
    fun `Packaged exposes path via base class`() {
        val installLocation: InstallLocation = InstallLocation.Packaged(Path.of("/opt/app"))
        assertEquals(Path.of("/opt/app"), installLocation.path)
    }

    @Test
    fun `DevFallback exposes path via base class`() {
        val installLocation: InstallLocation = InstallLocation.DevFallback(Path.of("/tmp"))
        assertEquals(Path.of("/tmp"), installLocation.path)
    }

    @Test
    fun `default factory reads real system properties without throwing`() {
        assertDoesNotThrow { InstallPaths.current() }
    }
}
