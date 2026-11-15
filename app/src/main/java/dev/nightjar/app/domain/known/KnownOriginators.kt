package dev.nightjar.app.domain.known

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.rules.Numbers

/** A sender the user marked as expected (`known_originator`). */
data class KnownOriginator(
    val value: String,
    val kind: Kind,
    val label: String? = null,
    val addedAtWallMs: Long = 0,
) {
    enum class Kind { EXACT, PREFIX }

    companion object {
        /** Label used by a carrier may legitimately push OMA CP settings. */
        const val LABEL_CARRIER = "carrier"
    }
}

/**
 * Exact/prefix matching for known originators.
 *
 * Matching normalises both sides with [Numbers.normalize] (: strip spaces, `, `, a leading `00`) and folds ASCII
 * case, so `+91 90000 00000`, `00919000000000` and `+919000000000` are the same sender. A prefix entry matches a
 * longer number, never the other way round.
 */
class KnownOriginatorMatcher(
    originators: List<KnownOriginator>,
) {
    private val exact: Map<String, KnownOriginator>
    private val prefixes: List<Pair<String, KnownOriginator>>

    init {
        val exactMap = HashMap<String, KnownOriginator>()
        val prefixList = ArrayList<Pair<String, KnownOriginator>>()
        for (o in originators) {
            val norm = normalize(o.value) ?: continue
            when (o.kind) {
                KnownOriginator.Kind.EXACT -> exactMap[norm] = o
                KnownOriginator.Kind.PREFIX -> prefixList += norm to o
            }
        }
        // Longest prefix first so the most specific label wins.
        prefixList.sortByDescending { it.first.length }
        exact = exactMap
        prefixes = prefixList
    }

    val size: Int get() = exact.size + prefixes.size

    val isEmpty: Boolean get() = exact.isEmpty() && prefixes.isEmpty()

    /** The matching entry, or null. */
    fun find(originator: String?): KnownOriginator? {
        val norm = normalize(originator) ?: return null
        exact[norm]?.let { return it }
        for ((prefix, entry) in prefixes) {
            if (norm.startsWith(prefix)) return entry
        }
        return null
    }

    fun matches(originator: String?): Boolean = find(originator) != null

    fun isCarrier(originator: String?): Boolean = find(originator)?.label == KnownOriginator.LABEL_CARRIER

    companion object {
        val EMPTY = KnownOriginatorMatcher(emptyList())

        fun normalize(value: String?): String? {
            val raw = Numbers.normalize(value) ?: return null
            if (raw.isEmpty()) return null
            return raw.lowercase()
        }
    }
}

/**
 * A temporary silence (`mute`). Mutes only stop notifications; the message is still recorded
 * and the firewall still runs.
 */
data class Mute(
    val scope: Scope,
    val value: String,
    /** Wall-clock expiry; `Long.MAX_VALUE` means "until the user removes it". */
    val untilWallMs: Long,
) {
    enum class Scope { ORIGINATOR, CLASS }
}

/**
 * Answers "is this event muted?" for the notifier.
 *
 * Each check compares the expiry with the check time, not with the time the matcher was built: [nowWallMs] is only the
 * default check time, so a matcher that is kept longer does not keep an expired mute.
 */
class MuteMatcher(
    mutes: List<Mute>,
    private val nowWallMs: Long,
) {
    /** Normalised originator to the latest expiry of its mutes. */
    private val originators: Map<String, Long> =
        mutes
            .filter { it.scope == Mute.Scope.ORIGINATOR }
            .mapNotNull { mute -> KnownOriginatorMatcher.normalize(mute.value)?.let { it to mute.untilWallMs } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, until) -> until.max() }

    /** Class wire name to the latest expiry of its mutes. */
    private val classes: Map<String, Long> =
        mutes
            .filter { it.scope == Mute.Scope.CLASS }
            .groupBy({ it.value }, { it.untilWallMs })
            .mapValues { (_, until) -> until.max() }

    fun isMuted(
        classId: ClassId,
        originator: String?,
        direction: Direction = Direction.IN,
        atWallMs: Long = nowWallMs,
    ): Boolean {
        if ((classes[classId.wire()] ?: Long.MIN_VALUE) > atWallMs) return true
        if (direction != Direction.IN) return false
        val norm = KnownOriginatorMatcher.normalize(originator) ?: return false
        return (originators[norm] ?: Long.MIN_VALUE) > atWallMs
    }

    /** True when no mute is active at [nowWallMs]. */
    val isEmpty: Boolean get() = originators.values.none { it > nowWallMs } && classes.values.none { it > nowWallMs }
}
