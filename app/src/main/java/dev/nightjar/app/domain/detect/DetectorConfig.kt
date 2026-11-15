package dev.nightjar.app.domain.detect

import org.json.JSONObject

/**
 * Detector thresholds (`detect.*`). Values are clamped instead of rejected: a corrupted settings
 * blob must never stop ingest (fail open).
 *
 * @param silentBurstCount `detect.silent.count`, 2–20, default 3
 * @param silentWindowMin `detect.silent.windowMin`, 5/15/30/60, default 15
 * @param silentFlagNewSender `detect.silent.newSender`, default on
 * @param silentFlagRoaming `detect.silent.roaming`, default on
 * @param suplGraceMin `detect.supl.graceMin`, 5–60 (flo), default 5
 * @param premium destination patterns that indicate a premium-rate number ("premium pattern from resources")
 * @param tarDenylist TARs of SIM toolkit applications used by SIM-Trojan families
 */
data class DetectorConfig(
    val silentBurstCount: Int = DEFAULT_BURST_COUNT,
    val silentWindowMin: Int = DEFAULT_WINDOW_MIN,
    val silentFlagNewSender: Boolean = true,
    val silentFlagRoaming: Boolean = true,
    val suplGraceMin: Int = DEFAULT_SUPL_GRACE_MIN,
    val premium: PremiumPatterns = PremiumPatterns.NONE,
    val tarDenylist: Set<Int> = emptySet(),
) {
    /** The same configuration with every numeric value inside its documented range. */
    fun coerced(): DetectorConfig =
        copy(
            silentBurstCount = silentBurstCount.coerceIn(MIN_BURST_COUNT, MAX_BURST_COUNT),
            silentWindowMin = if (silentWindowMin in WINDOW_CHOICES) silentWindowMin else DEFAULT_WINDOW_MIN,
            suplGraceMin = suplGraceMin.coerceIn(MIN_SUPL_GRACE_MIN, MAX_SUPL_GRACE_MIN),
        )

    val silentWindowMs: Long get() = silentWindowMin * 60_000L

    val suplGraceMs: Long get() = suplGraceMin * 60_000L

    companion object {
        const val MIN_BURST_COUNT = 2
        const val MAX_BURST_COUNT = 20
        const val DEFAULT_BURST_COUNT = 3
        const val DEFAULT_WINDOW_MIN = 15
        const val MIN_SUPL_GRACE_MIN = 5
        const val MAX_SUPL_GRACE_MIN = 60
        const val DEFAULT_SUPL_GRACE_MIN = 5

        /** Offers exactly these burst windows. */
        val WINDOW_CHOICES = setOf(5, 15, 30, 60)

        val DEFAULT = DetectorConfig()
    }
}

/**
 * Premium-rate destination patterns. Prefixes are digit strings compared against the digits of the
 * destination; regexes are matched with `find()` against the same digits. The app loads the list from a resource
 * (`premium_numbers.json`), so it can be updated with an app release without a code change.
 */
class PremiumPatterns(
    prefixes: List<String>,
    regexes: List<Regex>,
) {
    private val prefixes: List<String> = prefixes.map { it.filter(Char::isDigit) }.filter { it.isNotEmpty() }
    private val regexes: List<Regex> = regexes.toList()

    val isEmpty: Boolean get() = prefixes.isEmpty() && regexes.isEmpty()

    fun matches(destination: String?): Boolean {
        if (destination == null || isEmpty) return false
        val digits = destination.filter(Char::isDigit)
        if (digits.isEmpty()) return false
        return prefixes.any { digits.startsWith(it) } || regexes.any { it.containsMatchIn(digits) }
    }

    companion object {
        val NONE = PremiumPatterns(emptyList(), emptyList())

        /**
         * Parses `{"prefixes":["..."],"regexes":["..."]}` with `org.json` (on the device and in the JVM tests). Malformed
         * input yields [NONE], and an entry that is not a valid regex is left out, rather than a crash.
         */
        fun parseJson(json: String?): PremiumPatterns {
            if (json.isNullOrBlank()) return NONE
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return NONE
            val prefixes = stringArray(root, "prefixes")
            val regexes = stringArray(root, "regexes").mapNotNull { pattern -> runCatching { Regex(pattern) }.getOrNull() }
            return PremiumPatterns(prefixes, regexes)
        }

        private fun stringArray(
            root: JSONObject,
            key: String,
        ): List<String> {
            val array = root.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotEmpty) }
        }
    }
}

/**
 * Parses the SIM-toolkit TAR denylist (`sim_tar_denylist.json`): `{"tars":["505348","0x534054"]}`. Each entry
 * is 6 hex digits (3 octets, ETSI TS 101 220 Annex D) with an optional `0x` prefix. Malformed input yields an empty
 * set, and an entry that is not a 3-octet hex value is left out, rather than a crash.
 */
object TarDenylistJson {
    private const val MAX_TAR = 0xFFFFFF

    fun parseJson(json: String?): Set<Int> {
        if (json.isNullOrBlank()) return emptySet()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptySet()
        val array = root.optJSONArray("tars") ?: return emptySet()
        return (0 until array.length())
            .mapNotNull { (array.opt(it) as? String)?.removePrefix("0x")?.removePrefix("0X")?.toIntOrNull(16) }
            .filter { it in 0..MAX_TAR }
            .toSet()
    }
}
