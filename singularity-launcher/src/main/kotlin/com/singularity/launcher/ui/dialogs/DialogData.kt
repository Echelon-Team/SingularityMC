// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.dialogs

enum class ConflictSeverity { WARNING, ERROR, CRITICAL }

data class ModConflict(
    val modA: String,
    val modB: String,
    val severity: ConflictSeverity,
    val description: String
)

data class UpdateInfo(
    val newVersion: String,
    val currentVersion: String,
    val changelog: String,
    val downloadUrl: String
)

data class ImportScanResult(
    val totalJars: Int,
    val fractureiserDetected: Boolean,
    val suspiciousJars: List<String>
)
