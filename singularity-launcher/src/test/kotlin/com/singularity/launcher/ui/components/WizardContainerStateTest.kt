// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.components

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests pure logic of wizard state machine (extracted dla testowalności
 * bez Compose UI environment).
 */
class WizardContainerStateTest {

    private data class TestState(val name: String = "", val count: Int = 0)

    private fun fakeSteps() = listOf(
        WizardStep<TestState>(
            title = "Step 1",
            validate = { it.name.isNotBlank() }
        ),
        WizardStep<TestState>(
            title = "Step 2",
            validate = { it.count > 0 }
        ),
        WizardStep<TestState>(
            title = "Step 3",
            validate = { true }  // always valid
        )
    )

    @Test
    fun `canAdvance is true when current step validates`() {
        val steps = fakeSteps()
        val logic = WizardLogic(steps)

        // Step 0: name empty → invalid
        assertFalse(logic.canAdvance(currentStep = 0, state = TestState()))

        // Step 0: name filled → valid
        assertTrue(logic.canAdvance(currentStep = 0, state = TestState(name = "foo")))
    }

    @Test
    fun `canGoBack is true except on first step`() {
        val steps = fakeSteps()
        val logic = WizardLogic(steps)

        assertFalse(logic.canGoBack(currentStep = 0))
        assertTrue(logic.canGoBack(currentStep = 1))
        assertTrue(logic.canGoBack(currentStep = 2))
    }

    @Test
    fun `isLastStep true only on last index`() {
        val logic = WizardLogic(fakeSteps())
        assertFalse(logic.isLastStep(currentStep = 0))
        assertFalse(logic.isLastStep(currentStep = 1))
        assertTrue(logic.isLastStep(currentStep = 2))
    }

    @Test
    fun `nextStep increments by 1`() {
        val logic = WizardLogic(fakeSteps())
        assertEquals(1, logic.nextStep(currentStep = 0))
        assertEquals(2, logic.nextStep(currentStep = 1))
    }

    @Test
    fun `nextStep caps at last`() {
        val logic = WizardLogic(fakeSteps())
        assertEquals(2, logic.nextStep(currentStep = 2), "Should not exceed last index")
    }

    @Test
    fun `previousStep decrements by 1`() {
        val logic = WizardLogic(fakeSteps())
        assertEquals(0, logic.previousStep(currentStep = 1))
        assertEquals(1, logic.previousStep(currentStep = 2))
    }

    @Test
    fun `previousStep caps at zero`() {
        val logic = WizardLogic(fakeSteps())
        assertEquals(0, logic.previousStep(currentStep = 0))
    }

    @Test
    fun `totalSteps returns list size`() {
        assertEquals(3, WizardLogic(fakeSteps()).totalSteps)
    }
}
