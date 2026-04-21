// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.config

import java.nio.file.Path

/**
 * Location of the install directory (Location 1 per spec 4.11) — where binaries, manifest,
 * and `File-Backups/` live. Separate from [DataPaths] (Location 2 — user data).
 *
 * **Two variants, machine-distinguishable:**
 * - [Packaged] — detected via `jpackage.app-path` system property (real install).
 * - [DevFallback] — no jpackage property, using `user.dir` (dev mode or non-jpackage build).
 *
 * Callers performing destructive writes (e.g. backup directory creation) should check the
 * variant: production code should tolerate either; CI/strict environments can refuse on
 * [DevFallback] to avoid polluting repo/dev cwd with runtime state.
 */
sealed class InstallLocation {
    abstract val path: Path

    /** Install path detected from `jpackage.app-path` — real packaged app. */
    data class Packaged(override val path: Path) : InstallLocation()

    /** Fallback to `user.dir` in dev / non-jpackage runs. */
    data class DevFallback(override val path: Path) : InstallLocation()
}

/**
 * Resolver for [InstallLocation]. Stateless object.
 */
object InstallPaths {

    /**
     * Detect install location from jpackage system property, or fall back to `user.dir`.
     *
     * Uses explicit parameters for testability; defaults read real system properties.
     */
    fun current(
        jpackageAppPath: String? = System.getProperty("jpackage.app-path"),
        userDir: String = System.getProperty("user.dir"),
    ): InstallLocation {
        val packagedParent = jpackageAppPath?.let { Path.of(it).parent }
        return if (packagedParent != null) {
            InstallLocation.Packaged(packagedParent)
        } else {
            InstallLocation.DevFallback(Path.of(userDir))
        }
    }
}
