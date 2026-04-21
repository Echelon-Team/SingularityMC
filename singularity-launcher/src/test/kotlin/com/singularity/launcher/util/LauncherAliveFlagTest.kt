// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LauncherAliveFlagTest {

    @Test
    fun `writeTo creates flag file in given install_dir`(@TempDir installDir: Path) {
        LauncherAliveFlag.writeTo(installDir)
        val flag = installDir.resolve("launcher-alive-flag")
        assertTrue(Files.exists(flag), "flag must exist after writeTo")
        // Zawartość jest irrelevant — plik istnieje, to cały kontrakt.
        // Rust side patrzy tylko na obecność, nie czyta content.
        assertTrue(Files.isRegularFile(flag))
    }

    @Test
    fun `writeTo is idempotent — repeated calls leave single file`(
        @TempDir installDir: Path,
    ) {
        LauncherAliveFlag.writeTo(installDir)
        LauncherAliveFlag.writeTo(installDir)
        LauncherAliveFlag.writeTo(installDir)
        val flag = installDir.resolve("launcher-alive-flag")
        assertTrue(Files.exists(flag))
        // No exception, no side effect difference — Rust `consume` usuwa
        // plik niezależnie od ile razy launcher go napisał.
    }

    @Test
    fun `write with missing env var is silent no-op`(@TempDir installDir: Path) {
        // Empty env map simulates launcher started directly (no parent
        // auto-update process, no SINGULARITY_INSTALL_DIR). Must not
        // throw and must not create any flag anywhere — we don't know
        // install_dir in this context.
        LauncherAliveFlag.write(env = emptyMap())
        // Sanity: flag not created in TempDir because write() used empty env
        // (nie installDir passed). Nothing writable without explicit path.
        assertFalse(Files.exists(installDir.resolve("launcher-alive-flag")))
    }

    @Test
    fun `write with blank SINGULARITY_INSTALL_DIR is silent no-op`(
        @TempDir installDir: Path,
    ) {
        // JVM may surface empty string instead of null for unset vars on
        // some Windows configs — blank value must be treated as "unset",
        // not fall through to `Path.of("")` which would write to CWD.
        LauncherAliveFlag.write(env = mapOf("SINGULARITY_INSTALL_DIR" to ""))
        assertFalse(Files.exists(installDir.resolve("launcher-alive-flag")))
    }

    @Test
    fun `write with env var set writes flag at resolved path`(
        @TempDir installDir: Path,
    ) {
        LauncherAliveFlag.write(
            env = mapOf("SINGULARITY_INSTALL_DIR" to installDir.toString()),
        )
        assertTrue(Files.exists(installDir.resolve("launcher-alive-flag")))
    }

    @Test
    fun `write swallows IOException when target unwritable`(
        @TempDir installDir: Path,
    ) {
        // Seed a directory where the flag file should be — writeString
        // will throw because target is a dir not a file. LauncherAliveFlag
        // must catch + log, not propagate (advisory flag, missing write
        // just delays crash detection, doesn't break the launcher).
        val flagPath = installDir.resolve("launcher-alive-flag")
        Files.createDirectory(flagPath)

        // Must not throw.
        LauncherAliveFlag.writeTo(installDir)

        // Flag path is still a directory — write failed silently.
        assertTrue(Files.isDirectory(flagPath))
    }
}
