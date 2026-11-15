package dev.nightjar.app.core.settings

import dev.nightjar.app.ui.theme.NjAccent
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity

/** Detail level (`ui.mode`). Switching never touches data. */
enum class UiMode(
    val wire: String,
) {
    SIMPLE("SIMPLE"),
    RESEARCHER("RESEARCHER"),
    ;

    companion object {
        fun from(wire: String?): UiMode = if (wire == RESEARCHER.wire) RESEARCHER else SIMPLE
    }
}

/** Theme choice (`ui.theme`). */
enum class ThemeMode(
    val wire: String,
) {
    SYSTEM("SYSTEM"),
    LIGHT("LIGHT"),
    DARK("DARK"),
    BLACK("BLACK"),
    ;

    companion object {
        fun from(wire: String?): ThemeMode = entries.firstOrNull { it.wire == wire } ?: SYSTEM
    }
}

/**
 * Digest cadence (`notify.digest`). The digest posts at most once a week: DAILY was removed,
 * and a stored DAILY reads as WEEKLY. No stored value is the default, WEEKLY.
 */
enum class DigestMode(
    val wire: String,
) {
    OFF("OFF"),
    WEEKLY("WEEKLY"),
    ;

    companion object {
        fun from(wire: String?): DigestMode = if (wire == OFF.wire) OFF else WEEKLY
    }
}

/** App-lock grace (`privacy.lockTimeout`). [millis] is 0 for "Immediately". */
enum class LockTimeout(
    val wire: String,
    val millis: Long,
) {
    IMMEDIATELY("IMMEDIATELY", 0),
    ONE_MIN("ONE_MIN", 60_000),
    FIVE_MIN("FIVE_MIN", 5 * 60_000),
    FIFTEEN_MIN("FIFTEEN_MIN", 15 * 60_000),
    ;

    companion object {
        fun from(wire: String?): LockTimeout = entries.firstOrNull { it.wire == wire } ?: FIVE_MIN
    }
}

/** Retention (`data.retentionDays`). [days] is null for "Forever". */
enum class Retention(
    val wire: String,
    val days: Int?,
) {
    DAYS_30("DAYS_30", 30),
    DAYS_90("DAYS_90", 90),
    DAYS_365("DAYS_365", 365),
    FOREVER("FOREVER", null),
    ;

    companion object {
        fun from(wire: String?): Retention = entries.firstOrNull { it.wire == wire } ?: DAYS_90
    }
}

/**
 * The complete settings catalogue. Immutable snapshot; [SettingsStore] clamps every write to the documented
 * range, so a value read from here is always in range.
 */
data class Settings(
    val mode: UiMode = UiMode.SIMPLE,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Wallpaper colours (Monet) replace the accent of [accent]. Off by default. */
    val dynamicColor: Boolean = false,
    /** Colour theme (`ui.accent`). */
    val accent: NjAccent = NjAccent.NIGHT_BLUE,
    /** True-black surfaces in dark theme (`ui.trueBlack`). [ThemeMode.BLACK] from an old file counts as on. */
    val trueBlack: Boolean = false,
    /** Floating tab bar, solid (`ui.floatingBar`). Off: the Material bar at the bottom edge. */
    val floatingBar: Boolean = true,
    /** Enter animation for list items (`ui.listAnimations`). */
    val listAnimations: Boolean = true,
    /** Bounce at the end of a list (`ui.listBounce`). */
    val listBounce: Boolean = true,
    /** All app motion off, as the system "Remove animations" does (`ui.reduceMotion`). */
    val reduceMotion: Boolean = false,
    /** Haptic feedback on taps and switches (`ui.haptics`). */
    val haptics: Boolean = true,
    val autoReview: Boolean = true,
    val notifySuspicious: Boolean = true,
    val notifyNotable: Boolean = false,
    val notifyCb: Boolean = false,
    val notifyDigest: DigestMode = DigestMode.WEEKLY,
    val notifyRateLimitMin: Int = DEFAULT_RATE_LIMIT_MIN,
    val detectSilentCount: Int = DEFAULT_SILENT_COUNT,
    val detectSilentWindowMin: Int = DEFAULT_SILENT_WINDOW_MIN,
    val detectSilentNewSender: Boolean = true,
    val detectSilentRoaming: Boolean = true,
    val detectSuplGraceMin: Int = DEFAULT_SUPL_GRACE_MIN,
    /** Per-class severity override (`severity.<classId>`); absent means the class default. */
    val severityOverrides: Map<ClassId, Severity> = emptyMap(),
    val outgoingEnabled: Boolean = true,
    val outgoingLogVisible: Boolean = false,
    val cbLog: Boolean = true,
    val cbAllChannels: Boolean = false,
    val appLock: Boolean = false,
    val lockTimeout: LockTimeout = LockTimeout.FIVE_MIN,
    val maskNumbers: Boolean = false,
    val hideInNotifications: Boolean = false,
    val blockScreenshots: Boolean = false,
    val retention: Retention = Retention.DAYS_90,
    val debugLogging: Boolean = false,
    /** Wall-clock ms after which debug logging turns itself off. 0 when off. */
    val debugLoggingUntil: Long = 0,
    val safeMode: Boolean = false,
    val onboardingDone: Boolean = false,
    /** Last build fingerprint the app saw, for OTA detection. */
    val lastBuildFingerprint: String? = null,
    /** Delivery reports (CL-STATUS) show in the message list (`messages.showDeliveryReports`). */
    val showDeliveryReports: Boolean = true,
) {
    /** True when the surfaces are true black: the switch is on, or an old file stored [ThemeMode.BLACK]. */
    val effectiveTrueBlack: Boolean get() = trueBlack || theme == ThemeMode.BLACK

    /** True when the user asked for D-level logging and the 24 h window has not expired. */
    fun debugLoggingActive(now: Long): Boolean = debugLogging && debugLoggingUntil > now

    /** The effective severity for a class: the user's override, else [fallback] (the class default). */
    fun severityFor(
        classId: ClassId,
        fallback: Severity,
    ): Severity = severityOverrides[classId] ?: fallback

    companion object {
        const val DEFAULT_RATE_LIMIT_MIN = 5
        const val DEFAULT_SILENT_COUNT = 3
        const val DEFAULT_SILENT_WINDOW_MIN = 15
        const val DEFAULT_SUPL_GRACE_MIN = 5

        /** ranges. */
        val RATE_LIMIT_CHOICES = listOf(1, 5, 15)
        val SILENT_COUNT_RANGE = 2..20
        val SILENT_WINDOW_CHOICES = listOf(5, 15, 30, 60)
        val SUPL_GRACE_RANGE = 5..60

        /** `advanced.debugLogging` turns itself off 24 h after it was enabled. */
        const val DEBUG_LOGGING_WINDOW_MS = 24L * 60 * 60 * 1000

        fun clampRateLimit(minutes: Int): Int = RATE_LIMIT_CHOICES.minBy { kotlin.math.abs(it - minutes) }

        fun clampSilentCount(count: Int): Int = count.coerceIn(SILENT_COUNT_RANGE)

        fun clampSilentWindow(minutes: Int): Int = SILENT_WINDOW_CHOICES.minBy { kotlin.math.abs(it - minutes) }

        fun clampSuplGrace(minutes: Int): Int = minutes.coerceIn(SUPL_GRACE_RANGE)
    }
}

/** One user-visible settings change, fed to `SettingsSync` and the audit log (S03). */
data class SettingsChange(
    /** Key, e.g. `ui.mode`. */
    val key: String,
    val before: Any?,
    val after: Any?,
)
