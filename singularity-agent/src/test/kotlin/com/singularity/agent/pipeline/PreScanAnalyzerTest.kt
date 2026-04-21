// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.pipeline

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PreScanAnalyzerTest {

    @Test
    fun `detects RED on double Overwrite`() {
        val report = PreScanAnalyzer.analyze(listOf(
            MixinDeclaration("modA", "Target", "tick", MixinType.OVERWRITE),
            MixinDeclaration("modB", "Target", "tick", MixinType.OVERWRITE)
        ))
        assertEquals(1, report.redCount)
        assertTrue(report.hasBlockingConflicts)
    }

    @Test
    fun `detects RED on double Redirect on same call site`() {
        val report = PreScanAnalyzer.analyze(listOf(
            MixinDeclaration("modA", "Target", "tick", MixinType.REDIRECT),
            MixinDeclaration("modB", "Target", "tick", MixinType.REDIRECT)
        ))
        assertEquals(1, report.redCount)
    }

    @Test
    fun `detects YELLOW on Overwrite plus Inject`() {
        val report = PreScanAnalyzer.analyze(listOf(
            MixinDeclaration("modA", "Target", "tick", MixinType.OVERWRITE),
            MixinDeclaration("modB", "Target", "tick", MixinType.INJECT)
        ))
        assertEquals(1, report.yellowCount)
        assertFalse(report.hasBlockingConflicts)
    }

    @Test
    fun `detects YELLOW on double ModifyVariable`() {
        val report = PreScanAnalyzer.analyze(listOf(
            MixinDeclaration("modA", "Target", "tick", MixinType.MODIFY_VARIABLE),
            MixinDeclaration("modB", "Target", "tick", MixinType.MODIFY_VARIABLE)
        ))
        assertEquals(1, report.yellowCount)
    }

    @Test
    fun `detects YELLOW on double WrapOperation`() {
        val report = PreScanAnalyzer.analyze(listOf(
            MixinDeclaration("modA", "Target", "tick", MixinType.WRAP_OPERATION),
            MixinDeclaration("modB", "Target", "tick", MixinType.WRAP_OPERATION)
        ))
        assertEquals(1, report.yellowCount)
    }

    @Test
    fun `no conflicts for two Inject on same method (GREEN)`() {
        val report = PreScanAnalyzer.analyze(listOf(
            MixinDeclaration("modA", "Target", "tick", MixinType.INJECT),
            MixinDeclaration("modB", "Target", "tick", MixinType.INJECT)
        ))
        assertEquals(0, report.redCount)
        assertEquals(0, report.yellowCount)
    }

    @Test
    fun `same mod on same target is not reported`() {
        val report = PreScanAnalyzer.analyze(listOf(
            MixinDeclaration("modA", "Target", "tick", MixinType.OVERWRITE),
            MixinDeclaration("modA", "Target", "tick", MixinType.OVERWRITE)
        ))
        assertEquals(0, report.conflicts.size)
    }

    @Test
    fun `three mods with overwrites produces multiple RED conflicts`() {
        // Flag z test-quality: 3+ mods scenario (pairwise analysis)
        val report = PreScanAnalyzer.analyze(listOf(
            MixinDeclaration("modA", "Target", "tick", MixinType.OVERWRITE),
            MixinDeclaration("modB", "Target", "tick", MixinType.OVERWRITE),
            MixinDeclaration("modC", "Target", "tick", MixinType.OVERWRITE)
        ))
        // 3 pairs: A-B, A-C, B-C → 3 RED conflicts
        assertEquals(3, report.redCount)
    }

    @Test
    fun `empty declarations produce empty report`() {
        val report = PreScanAnalyzer.analyze(emptyList())
        assertEquals(0, report.redCount)
        assertEquals(0, report.yellowCount)
        assertFalse(report.hasBlockingConflicts)
    }

    @Test
    fun `null target method treats as wildcard`() {
        val report = PreScanAnalyzer.analyze(listOf(
            MixinDeclaration("modA", "Target", null, MixinType.INJECT),
            MixinDeclaration("modB", "Target", null, MixinType.INJECT)
        ))
        assertEquals(0, report.redCount)  // 2x Inject OK nawet na wildcard
    }
}
