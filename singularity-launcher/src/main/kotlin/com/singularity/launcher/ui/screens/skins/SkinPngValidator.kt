// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.skins

import java.io.File
import javax.imageio.ImageIO

/**
 * Pure validator dla Minecraft skin PNG files (#17 edge-case).
 *
 * **Valid formats:**
 * - 64x64 (modern format z outer layers)
 * - 64x32 (legacy format, pre-1.8)
 *
 * **Requirements:**
 * - PNG format (magic bytes 89 50 4E 47)
 * - Alpha channel (RGBA, not RGB)
 * - Max size 24KB (realistic dla Minecraft skin)
 */
object SkinPngValidator {

    const val MAX_SIZE_BYTES = 24 * 1024L  // 24 KB

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun validate(file: File): ValidationResult {
        if (!file.exists()) return ValidationResult.Invalid("File does not exist")
        if (file.length() > MAX_SIZE_BYTES) {
            return ValidationResult.Invalid("File too large (max 24 KB): ${file.length()} bytes")
        }

        val image = try {
            ImageIO.read(file)
        } catch (e: Exception) {
            return ValidationResult.Invalid("Invalid PNG: ${e.message}")
        } ?: return ValidationResult.Invalid("Cannot read image")

        val width = image.width
        val height = image.height

        // Valid: 64x64 (modern) or 64x32 (legacy)
        if (!((width == 64 && height == 64) || (width == 64 && height == 32))) {
            return ValidationResult.Invalid("Invalid dimensions ${width}x${height} (expected 64x64 or 64x32)")
        }

        // Must have alpha channel (Minecraft skins need transparency)
        if (!image.colorModel.hasAlpha()) {
            return ValidationResult.Invalid("PNG must have alpha channel")
        }

        return ValidationResult.Valid
    }

    fun isValid(file: File): Boolean = validate(file) is ValidationResult.Valid
}
