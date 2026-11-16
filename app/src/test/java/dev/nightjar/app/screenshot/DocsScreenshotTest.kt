package dev.nightjar.app.screenshot

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screenshots of the documentation website. Each image is one dark-theme `@Preview` of the app, drawn at the
 * size of a phone screen. The previews use synthetic data only.
 *
 * The plain `test` task runs this class without writing images. To write the images to
 * `app/build/outputs/docs-screens/`, run `./gradlew :app:recordRoborazziDebug`. Then copy them to the website with
 * `python website/scripts/copy_screenshots.py`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = PHONE)
class DocsScreenshotTest {
    @Test
    fun onboarding() {
        shot("onboarding-welcome", "onboarding.OnboardingScreenKt", "OnboardingWelcomeDarkPreview")
        shot("onboarding-setup", "onboarding.OnboardingScreenKt", "OnboardingSetupDarkPreview")
        shot("onboarding-ready", "onboarding.OnboardingScreenKt", "OnboardingReadyDarkPreview")
    }

    @Test
    fun home() {
        shot("home", "home.HomePreviewsKt", "HomeProtectedDarkPreview")
        shot("home-safe-mode", "home.HomePreviewsKt", "HomeSafeModeDarkPreview")
        shot("home-not-active", "home.HomePreviewsKt", "HomeNotActiveDarkPreview")
    }

    @Test
    fun messages() {
        shot("messages", "messages.MessagesScreenKt", "MessagesDarkPreview")
        shot("messages-filter", "messages.FilterScreenKt", "FilterDarkPreview")
        shot("detail", "detail.DetailScreenKt", "DetailDarkPreview")
        shot("detail-sent", "detail.DetailScreenKt", "DetailDarkSentPreview")
    }

    @Test
    fun protection() {
        shot("protection", "firewall.FirewallScreenKt", "ProtectionDarkPreview")
        shot("custom-rules", "rules.CustomRulesScreenKt", "CustomRulesDarkPreview")
        shot("rule-editor", "rules.RuleEditorScreenKt", "RuleEditorDarkPreview")
        shot("known-senders", "known.KnownSendersScreenKt", "KnownSendersDarkPreview")
    }

    @Test
    fun status() {
        shot("protection-status", "health.HealthScreenKt", "HealthDarkPreview")
        shot("protection-status-problems", "health.HealthScreenKt", "HealthProblemsDarkPreview")
        shot("self-test-picker", "health.SelfTestSheetKt", "SelfTestPickerDarkPreview")
        shot("self-test-report", "health.SelfTestSheetKt", "SelfTestReportDarkPreview")
        shot("blind-spots", "health.BlindSpotsScreenKt", "BlindSpotsDarkPreview")
        shot("diagnostics", "diagnostics.DiagnosticsSheetKt", "DiagnosticsDarkPreview")
    }

    @Test
    fun settings() {
        shot("settings", "settings.SettingsHomeKt", "SettingsHomeDarkPreview")
        shot("sending-apps", "settings.SendingAppsScreenKt", "SendingAppsDarkPreview")
        shot("export", "export.ExportScreenKt", "ExportDarkPreview")
        shot("logs", "logs.LogsScreenKt", "LogsDarkPreview")
        shot("audit-history", "logs.AuditHistoryScreenKt", "AuditDarkPreview")
    }

    /** Draws the private preview [function] of the file class [fileClass] (below `feature`) and writes [name].png. */
    private fun shot(
        name: String,
        fileClass: String,
        function: String,
    ) {
        val preview = previewOf("$FEATURE.$fileClass", function)
        captureRoboImage(filePath = "$OUT_DIR/$name.png") { preview() }
    }

    private fun previewOf(
        fileClass: String,
        function: String,
    ): @Composable () -> Unit {
        val method = Class.forName(fileClass).declaredMethods.single { it.name == function }
        method.isAccessible = true
        // A @Composable function without parameters compiles to a static method with a Composer and a change mask.
        return { method.invoke(null, currentComposer, 0) }
    }
}

/** A phone screen of 412 x 915 dp at 2x density (824 x 1830 px). */
private const val PHONE = "w412dp-h915dp-port-xhdpi"

private const val FEATURE = "dev.nightjar.app.feature"

private const val OUT_DIR = "build/outputs/docs-screens"
