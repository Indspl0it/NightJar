package dev.nightjar.app.feature.onboarding

/**
 * The three phases of the flow: Welcome, Setup (root, Xposed framework, module, reboot) and Ready
 * (notifications, detail level). Each [OnboardingStep] is one phase. The step label and the dots show the phase
 * .
 */
enum class OnboardingPhase(
    val number: Int,
) {
    WELCOME(1),
    SETUP(2),
    FINISH(3),
    ;

    companion object {
        const val COUNT: Int = 3

        fun of(step: OnboardingStep): OnboardingPhase =
            when (step) {
                OnboardingStep.WELCOME -> WELCOME
                OnboardingStep.CHECKS -> SETUP
                OnboardingStep.DONE -> FINISH
            }
    }
}
