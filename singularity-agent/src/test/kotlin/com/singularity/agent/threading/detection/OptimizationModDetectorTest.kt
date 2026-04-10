package com.singularity.agent.threading.detection

import com.singularity.agent.registry.SingularityModRegistry
import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class OptimizationModDetectorTest {

    private lateinit var registry: SingularityModRegistry
    private lateinit var detector: OptimizationModDetector

    @BeforeEach
    fun setup() {
        registry = SingularityModRegistry()
        detector = OptimizationModDetector(registry)
    }

    private fun register(modId: String, loader: LoaderType = LoaderType.FABRIC) {
        registry.register(SingularityModRegistry.RegisteredMod(
            modId = modId, version = "1.0", name = modId,
            loaderType = loader, side = ModSide.BOTH
        ))
    }

    @Test
    fun `detects Sodium`() {
        register("sodium")
        assertTrue(detector.detect().sodiumDetected)
    }

    @Test
    fun `detects Embeddium as Sodium variant`() {
        register("embeddium", LoaderType.FORGE)
        assertTrue(detector.detect().sodiumDetected)
    }

    @Test
    fun `detects C2ME and signals blockade`() {
        register("c2me")
        val result = detector.detect()
        assertTrue(result.c2meDetected)
        assertTrue(result.shouldBlockStartup)
        assertNotNull(result.blockReason)
        assertTrue(result.blockReason!!.contains("C2ME"))
    }

    @Test
    fun `detects Lithium as compatible`() {
        register("lithium")
        val result = detector.detect()
        assertTrue(result.lithiumDetected)
        assertFalse(result.shouldBlockStartup)
    }

    @Test
    fun `detects Starlight`() {
        register("starlight")
        assertTrue(detector.detect().starlightDetected)
    }

    @Test
    fun `detects ScalableLux as Starlight variant`() {
        register("scalablelux")
        assertTrue(detector.detect().starlightDetected)
    }

    @Test
    fun `no optimization mods detected when none installed`() {
        val result = detector.detect()
        assertFalse(result.sodiumDetected)
        assertFalse(result.c2meDetected)
        assertFalse(result.lithiumDetected)
        assertFalse(result.starlightDetected)
        assertFalse(result.shouldBlockStartup)
    }

    @Test
    fun `multiple optimization mods detected together`() {
        register("sodium")
        register("lithium")
        register("starlight")

        val result = detector.detect()
        assertTrue(result.sodiumDetected)
        assertTrue(result.lithiumDetected)
        assertTrue(result.starlightDetected)
        assertFalse(result.shouldBlockStartup)
    }
}
