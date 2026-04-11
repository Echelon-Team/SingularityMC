package com.singularity.launcher.ui.screens.screenshots

import java.nio.file.Path

/**
 * Screenshot entry — pozycja w galerii. Reprezentuje pojedynczy PNG plik z `<instance>/minecraft/screenshots/`.
 */
data class ScreenshotEntry(
    val path: Path,
    val instanceId: String,
    val instanceName: String,
    val filename: String,
    val lastModified: Long,
    val sizeBytes: Long
)
