// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.skins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

@OptIn(ExperimentalCoroutinesApi::class)
class SkinsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @TempDir lateinit var tempDir: Path

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private fun makeVm(premium: Boolean = false) = SkinsViewModel(
        skinsDir = tempDir.resolve("skins"),
        isPremiumProvider = { premium },
        dispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
    )

    private fun createValidSkinPng(dir: Path, filename: String): Path {
        Files.createDirectories(dir)
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val target = dir.resolve(filename)
        ImageIO.write(image, "png", target.toFile())
        return target
    }

    @Test
    fun `initial state loads empty when no skins`() = runTest {
        val vm = makeVm()
        testDispatcher.scheduler.runCurrent()

        val state = vm.state.first()
        assertTrue(state.skins.isEmpty())
        assertFalse(state.isLoading)
        vm.onCleared()
    }

    @Test
    fun `initial state loads skins from dir`() = runTest {
        val skinsDir = tempDir.resolve("skins")
        createValidSkinPng(skinsDir, "test1.png")
        createValidSkinPng(skinsDir, "test2.png")

        val vm = makeVm()
        testDispatcher.scheduler.runCurrent()

        val state = vm.state.first()
        assertEquals(2, state.skins.size)
        vm.onCleared()
    }

    @Test
    fun `canUpload is false for non-premium`() = runTest {
        val vm = makeVm(premium = false)
        testDispatcher.scheduler.runCurrent()
        assertFalse(vm.state.first().canUpload)
        vm.onCleared()
    }

    @Test
    fun `canUpload is true for premium`() = runTest {
        val vm = makeVm(premium = true)
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.state.first().canUpload)
        vm.onCleared()
    }

    @Test
    fun `selectSkin updates selectedSkin`() = runTest {
        val skinsDir = tempDir.resolve("skins")
        createValidSkinPng(skinsDir, "a.png")
        val pathB = createValidSkinPng(skinsDir, "b.png")

        val vm = makeVm()
        testDispatcher.scheduler.runCurrent()

        val skinB = vm.state.first().skins.first { it.path == pathB }
        vm.selectSkin(skinB)
        assertEquals(skinB, vm.state.first().selectedSkin)
        vm.onCleared()
    }
}
