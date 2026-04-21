// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.skins

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

class SkinPngValidatorTest {

    @TempDir lateinit var tempDir: Path

    private fun createSkinPng(width: Int, height: Int, hasAlpha: Boolean = true): java.io.File {
        val type = if (hasAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        val file = tempDir.resolve("skin-${width}x${height}.png").toFile()
        ImageIO.write(image, "png", file)
        return file
    }

    @Test
    fun `valid 64x64 PNG with alpha passes`() {
        val file = createSkinPng(64, 64, hasAlpha = true)
        val result = SkinPngValidator.validate(file)
        assertTrue(result is SkinPngValidator.ValidationResult.Valid, "64x64 with alpha should be valid")
    }

    @Test
    fun `valid 64x32 legacy PNG with alpha passes`() {
        val file = createSkinPng(64, 32, hasAlpha = true)
        val result = SkinPngValidator.validate(file)
        assertTrue(result is SkinPngValidator.ValidationResult.Valid, "64x32 legacy should be valid")
    }

    @Test
    fun `invalid dimensions 128x128 rejected`() {
        val file = createSkinPng(128, 128, hasAlpha = true)
        val result = SkinPngValidator.validate(file)
        assertTrue(result is SkinPngValidator.ValidationResult.Invalid)
        assertTrue((result as SkinPngValidator.ValidationResult.Invalid).reason.contains("dimensions"))
    }

    @Test
    fun `no alpha channel rejected`() {
        val file = createSkinPng(64, 64, hasAlpha = false)
        val result = SkinPngValidator.validate(file)
        assertTrue(result is SkinPngValidator.ValidationResult.Invalid)
        assertTrue((result as SkinPngValidator.ValidationResult.Invalid).reason.contains("alpha"))
    }

    @Test
    fun `nonexistent file rejected`() {
        val file = tempDir.resolve("missing.png").toFile()
        val result = SkinPngValidator.validate(file)
        assertTrue(result is SkinPngValidator.ValidationResult.Invalid)
        assertTrue((result as SkinPngValidator.ValidationResult.Invalid).reason.contains("exist"))
    }

    @Test
    fun `isValid returns boolean`() {
        val valid = createSkinPng(64, 64, hasAlpha = true)
        val invalid = createSkinPng(100, 100, hasAlpha = true)
        assertTrue(SkinPngValidator.isValid(valid))
        assertFalse(SkinPngValidator.isValid(invalid))
    }
}
