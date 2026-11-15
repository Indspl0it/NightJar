package dev.nightjar.app.domain.settings

import dev.nightjar.app.domain.detect.DetectorConfig
import dev.nightjar.app.domain.detect.PremiumPatterns
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Wire

/**
 * The settings the classification layer reads, parsed from the stored key/value map.
 *
 * Parsing never throws: an unreadable value keeps its default, so a corrupted DataStore entry degrades one setting
 * instead of stopping ingest. Ranges are enforced by [DetectorConfig.coerced] and by the helpers here.
 */
data class ClassifierSettings(
    val severityOverrides: Map<ClassId, Severity> = emptyMap(),
    val detectors: DetectorConfig = DetectorConfig.DEFAULT,
    val showDeliveryReports: Boolean = true,
    val maskNumbers: Boolean = false,
    val hideNumbersInNotifications: Boolean = false,
    val notifySuspicious: Boolean = true,
    val notifyNotable: Boolean = false,
    val notifyBroadcasts: Boolean = false,
    val notifyRateLimitMin: Int = DEFAULT_RATE_LIMIT_MIN,
) {
    companion object {
        const val DEFAULT_RATE_LIMIT_MIN = 5
        val RATE_LIMIT_CHOICES = setOf(1, 5, 15)

        const val KEY_SEVERITY_PREFIX = "severity."
        const val KEY_SILENT_COUNT = "detect.silent.count"
        const val KEY_SILENT_WINDOW = "detect.silent.windowMin"
        const val KEY_SILENT_NEW_SENDER = "detect.silent.newSender"
        const val KEY_SILENT_ROAMING = "detect.silent.roaming"
        const val KEY_SUPL_GRACE = "detect.supl.graceMin"
        const val KEY_SHOW_DELIVERY = "messages.showDeliveryReports"
        const val KEY_MASK_NUMBERS = "privacy.maskNumbers"
        const val KEY_HIDE_IN_NOTIFICATIONS = "privacy.hideInNotifications"
        const val KEY_NOTIFY_SUSPICIOUS = "notify.suspicious"
        const val KEY_NOTIFY_NOTABLE = "notify.notable"
        const val KEY_NOTIFY_CB = "notify.cb"
        const val KEY_NOTIFY_RATE = "notify.rateLimitMin"

        /**
         * @param raw every settings key with its stored string value (`true`/`false`, numbers, wire names)
         * @param premium premium-rate patterns, loaded once from resources
         * @param tarDenylist TARs of known SIM-toolkit attack applications
         */
        fun fromRaw(
            raw: Map<String, String>,
            premium: PremiumPatterns = PremiumPatterns.NONE,
            tarDenylist: Set<Int> = emptySet(),
        ): ClassifierSettings {
            val overrides = LinkedHashMap<ClassId, Severity>()
            for ((key, value) in raw) {
                if (!key.startsWith(KEY_SEVERITY_PREFIX)) continue
                val classId = Wire.parse(ClassId::class.java, key.removePrefix(KEY_SEVERITY_PREFIX), null) ?: continue
                val severity = Wire.parse(Severity::class.java, value, null) ?: continue
                overrides[classId] = severity
            }
            val defaults = DetectorConfig.DEFAULT
            val detectors =
                DetectorConfig(
                    silentBurstCount = raw.int(KEY_SILENT_COUNT, defaults.silentBurstCount),
                    silentWindowMin = raw.int(KEY_SILENT_WINDOW, defaults.silentWindowMin),
                    silentFlagNewSender = raw.bool(KEY_SILENT_NEW_SENDER, defaults.silentFlagNewSender),
                    silentFlagRoaming = raw.bool(KEY_SILENT_ROAMING, defaults.silentFlagRoaming),
                    suplGraceMin = raw.int(KEY_SUPL_GRACE, defaults.suplGraceMin),
                    premium = premium,
                    tarDenylist = tarDenylist,
                ).coerced()
            val rate = raw.int(KEY_NOTIFY_RATE, DEFAULT_RATE_LIMIT_MIN)
            return ClassifierSettings(
                severityOverrides = overrides,
                detectors = detectors,
                showDeliveryReports = raw.bool(KEY_SHOW_DELIVERY, true),
                maskNumbers = raw.bool(KEY_MASK_NUMBERS, false),
                hideNumbersInNotifications = raw.bool(KEY_HIDE_IN_NOTIFICATIONS, false),
                notifySuspicious = raw.bool(KEY_NOTIFY_SUSPICIOUS, true),
                notifyNotable = raw.bool(KEY_NOTIFY_NOTABLE, false),
                notifyBroadcasts = raw.bool(KEY_NOTIFY_CB, false),
                notifyRateLimitMin = if (rate in RATE_LIMIT_CHOICES) rate else DEFAULT_RATE_LIMIT_MIN,
            )
        }

        private fun Map<String, String>.int(
            key: String,
            fallback: Int,
        ): Int = this[key]?.trim()?.toIntOrNull() ?: fallback

        private fun Map<String, String>.bool(
            key: String,
            fallback: Boolean,
        ): Boolean =
            when (this[key]?.trim()?.lowercase()) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> fallback
            }
    }
}
