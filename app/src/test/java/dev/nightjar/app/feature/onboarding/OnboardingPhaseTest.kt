package dev.nightjar.app.feature.onboarding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingPhaseTest {
    @Test
    fun `there are three phases numbered 1 to 3`() {
        assertEquals(OnboardingPhase.COUNT, OnboardingPhase.entries.size)
        assertEquals(listOf(1, 2, 3), OnboardingPhase.entries.map { it.number })
    }

    @Test
    fun `every step is one phase`() {
        assertEquals(OnboardingPhase.WELCOME, OnboardingPhase.of(OnboardingStep.WELCOME))
        assertEquals(OnboardingPhase.SETUP, OnboardingPhase.of(OnboardingStep.CHECKS))
        assertEquals(OnboardingPhase.FINISH, OnboardingPhase.of(OnboardingStep.DONE))
        assertEquals(OnboardingPhase.COUNT, OnboardingStep.entries.size)
    }

    @Test
    fun `the phase never goes back when the step goes forward`() {
        OnboardingStep.entries.zipWithNext().forEach { (before, after) ->
            assertTrue(OnboardingPhase.of(before).number < OnboardingPhase.of(after).number)
        }
    }

    @Test
    fun `the step label and the dots count 1 of 3 to 3 of 3`() {
        // The label is onb_page_indicator "Step %1$d of %2$d" with these two numbers. The current dot is the same phase.
        assertEquals(
            listOf(1 to 3, 2 to 3, 3 to 3),
            OnboardingStep.entries.map { OnboardingPhase.of(it).number to OnboardingPhase.COUNT },
        )
    }
}
