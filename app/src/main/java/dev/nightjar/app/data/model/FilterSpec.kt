package dev.nightjar.app.data.model

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.rules.Numbers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The chip row of Messages (03). One chip is active at a time; [SIM1]/[SIM2] filter by SIM slot, the others by
 * severity, quarantine state, direction or class.
 */
enum class FeedChip(val wire: String) {
    ALL("ALL"),
    SUSPICIOUS("SUSPICIOUS"),
    QUARANTINED("QUARANTINED"),
    OUTGOING("OUTGOING"),
    BROADCASTS("BROADCASTS"),
    SIM1("SIM1"),
    SIM2("SIM2"),
    ;

    companion object {
        fun from(wire: String?): FeedChip = entries.firstOrNull { it.wire == wire } ?: ALL
    }
}

/**
 * A feed filter (03). Chips and advanced fields are ANDed; within [classIds] and
 * [severities] the values are ORed.
 *
 * Rows are always excluded by the repository: `CL-NORMAL` and `CL-MO-VISIBLE` (counters) unless the row was
 * blocked (a send the outgoing gate refused), self-test rows, and `CL-STATUS` when [showDeliveryReports] is off
 * (on by default).
 */
data class FilterSpec(
    val chip: FeedChip = FeedChip.ALL,
    val classIds: Set<ClassId> = emptySet(),
    val severities: Set<Severity> = emptySet(),
    val direction: Direction? = null,
    /** Inclusive lower bound on `receivedAtWall`. */
    val fromWall: Long? = null,
    /** Exclusive upper bound on `receivedAtWall`. */
    val toWall: Long? = null,
    val subId: Int? = null,
    val unreviewedOnly: Boolean = false,
    val starredOnly: Boolean = false,
    /** Free text over originator, preview, content type and package label. */
    val search: String? = null,
    val originatorQuery: String? = null,
    val port: Int? = null,
    val contentType: String? = null,
    val packageQuery: String? = null,
    val showDeliveryReports: Boolean = true,
    /** Keeps only rows whose fate is in the set. An empty set means no restriction. */
    val fates: Set<Fate> = emptySet(),
    /** "Needs attention": only held or Suspicious rows that are not reviewed. ANDed with the other fields. */
    val attentionOnly: Boolean = false,
    /**
     * SCREENS1-X16: the relative date preset that produced [fromWall]/[toWall] (the feature's preset name, for example
     * `LAST_7_DAYS`), so a saved view re-resolves it on apply. Null for a fixed range, and for a view saved before the
     * field existed: the caller then detects the preset from the bounds. It narrows nothing by itself.
     */
    val rangePreset: String? = null,
) {
    /** True when nothing narrows the feed beyond the mandatory exclusions. */
    val isDefault: Boolean
        get() =
            chip == FeedChip.ALL && classIds.isEmpty() && severities.isEmpty() && direction == null &&
                fromWall == null && toWall == null && subId == null && !unreviewedOnly && !starredOnly &&
                search.isNullOrBlank() && originatorQuery.isNullOrBlank() && port == null &&
                contentType.isNullOrBlank() && packageQuery.isNullOrBlank() && fates.isEmpty() && !attentionOnly

    /**
     * Flattens the chip and the advanced fields into the DAO's parameters. Every predicate in the DAO query is
     * `:param IS NULL OR …` (or `:count = 0 OR … IN (…)`), so "no filter" is a null/zero/empty-list argument.
     *
     * The `IN (…)` lists are never empty: Room renders an empty list as `IN ()`, which SQLite rejects, so an unused
     * list carries one dummy value that the `count = 0` guard makes irrelevant.
     */
    fun toArgs(): Args {
        val classes = LinkedHashSet<ClassId>(classIds)
        val severityFilter = LinkedHashSet<Severity>(severities)
        var quarantinedOnly = false
        var directionFilter = direction
        var slotFilter: Int? = null
        when (chip) {
            FeedChip.ALL -> Unit
            FeedChip.SUSPICIOUS -> severityFilter.addAll(ATTENTION_SEVERITIES)
            FeedChip.QUARANTINED -> quarantinedOnly = true
            FeedChip.OUTGOING -> directionFilter = Direction.OUT
            FeedChip.BROADCASTS -> classes.addAll(listOf(ClassId.CB, ClassId.CB_SIM))
            FeedChip.SIM1 -> slotFilter = 0
            FeedChip.SIM2 -> slotFilter = 1
        }
        val query = search?.trim()?.takeIf { it.isNotEmpty() }
        val originator = originatorQuery?.trim()?.takeIf { it.isNotEmpty() }
        val pkg = packageQuery?.trim()?.takeIf { it.isNotEmpty() }
        return Args(
            includeStatus = showDeliveryReports,
            classCount = classes.size,
            classIds = classes.map { it.wire() }.ifEmpty { listOf("") },
            severityCount = severityFilter.size,
            severities = severityFilter.map { it.wire() }.ifEmpty { listOf("") },
            fateCount = fates.size,
            fates = fates.map { it.wire() }.ifEmpty { listOf("") },
            attentionOnly = attentionOnly,
            attentionSeverity = ATTENTION_SEVERITIES.map { it.wire() },
            quarantinedOnly = quarantinedOnly,
            unreviewedOnly = unreviewedOnly,
            starredOnly = starredOnly,
            direction = directionFilter?.wire(),
            fromWall = fromWall,
            toWall = toWall,
            subId = subId,
            slot = slotFilter,
            searchLike = query?.let(::containsLike),
            // Search normalises both sides, so the originator side matches regardless of +, spaces or 00.
            searchNormLike = query?.let { containsLike(Numbers.normalize(it)) },
            // AC2: a numeric search also matches the destination port.
            searchPort = query?.toIntOrNull(),
            originatorLike = originator?.let { containsLike(Numbers.normalize(it)) },
            port = port,
            contentTypeLike = contentType?.trim()?.takeIf { it.isNotEmpty() }?.let(::containsLike),
            packageLike = pkg?.let(::containsLike),
        )
    }

    /** DAO parameters for [FilterSpec]. */
    data class Args(
        val includeStatus: Boolean,
        val classCount: Int,
        val classIds: List<String>,
        val severityCount: Int,
        val severities: List<String>,
        val fateCount: Int,
        val fates: List<String>,
        val attentionOnly: Boolean,
        /** The severities that need attention (SUSPICIOUS and HIGH); the DAO tests `severity IN (…)`. */
        val attentionSeverity: List<String>,
        val quarantinedOnly: Boolean,
        val unreviewedOnly: Boolean,
        val starredOnly: Boolean,
        val direction: String?,
        val fromWall: Long?,
        val toWall: Long?,
        val subId: Int?,
        val slot: Int?,
        val searchLike: String?,
        val searchNormLike: String?,
        val searchPort: Int?,
        val originatorLike: String?,
        val port: Int?,
        val contentTypeLike: String?,
        val packageLike: String?,
    )

    /** Stable JSON form for `saved_view.filterJson`. Unknown fields are ignored on read. */
    fun toJson(): String = json.encodeToString(SavedFilter.serializer(), SavedFilter.from(this))

    companion object {
        val DEFAULT = FilterSpec()

        /** "Needs attention" and the Suspicious chip: every severity from SUSPICIOUS up. */
        val ATTENTION_SEVERITIES: List<Severity> = listOf(Severity.SUSPICIOUS, Severity.HIGH)

        /**
         * A `LIKE` pattern that matches [text] anywhere. `%`, `_` and the escape character are escaped, so a search for
         * `_` finds an underscore and not every row; the DAO queries declare `ESCAPE '\'`.
         */
        fun containsLike(text: String): String {
            val escaped =
                buildString(text.length + 2) {
                    for (c in text) {
                        if (c == '\\' || c == '%' || c == '_') append('\\')
                        append(c)
                    }
                }
            return "%$escaped%"
        }

        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** A damaged saved view reads as the default filter instead of throwing (the screen must still open). */
        fun fromJson(text: String?): FilterSpec =
            if (text.isNullOrBlank()) DEFAULT else SavedFilter.fromJson(text)?.toSpec() ?: DEFAULT
    }
}

/** Wire-stable serialised filter (saved views). */
@Serializable
internal data class SavedFilter(
    val chip: String = FeedChip.ALL.wire,
    val classIds: List<String> = emptyList(),
    val severities: List<String> = emptyList(),
    val direction: String? = null,
    val fromWall: Long? = null,
    val toWall: Long? = null,
    val subId: Int? = null,
    val unreviewedOnly: Boolean = false,
    val starredOnly: Boolean = false,
    val search: String? = null,
    val originatorQuery: String? = null,
    val port: Int? = null,
    val contentType: String? = null,
    val packageQuery: String? = null,
    val showDeliveryReports: Boolean = true,
    // Added later. Old JSON lacks them and reads as "no restriction".
    val fates: List<String> = emptyList(),
    val attentionOnly: Boolean = false,
    // Added later. Old JSON lacks it and reads as null.
    val rangePreset: String? = null,
) {
    fun toSpec(): FilterSpec =
        FilterSpec(
            chip = FeedChip.from(chip),
            classIds = classIds.mapNotNull { wire -> ClassId.entries.firstOrNull { it.wire() == wire } }.toSet(),
            severities = severities.mapNotNull { wire -> Severity.entries.firstOrNull { it.wire() == wire } }.toSet(),
            direction = direction?.let { d -> Direction.entries.firstOrNull { it.wire() == d } },
            fromWall = fromWall,
            toWall = toWall,
            subId = subId,
            unreviewedOnly = unreviewedOnly,
            starredOnly = starredOnly,
            search = search,
            originatorQuery = originatorQuery,
            port = port,
            contentType = contentType,
            packageQuery = packageQuery,
            showDeliveryReports = showDeliveryReports,
            fates = fates.mapNotNull { wire -> Fate.entries.firstOrNull { it.wire() == wire } }.toSet(),
            attentionOnly = attentionOnly,
            rangePreset = rangePreset?.takeIf { it.isNotBlank() },
        )

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        fun from(spec: FilterSpec): SavedFilter =
            SavedFilter(
                chip = spec.chip.wire,
                classIds = spec.classIds.map { it.wire() },
                severities = spec.severities.map { it.wire() },
                direction = spec.direction?.wire(),
                fromWall = spec.fromWall,
                toWall = spec.toWall,
                subId = spec.subId,
                unreviewedOnly = spec.unreviewedOnly,
                starredOnly = spec.starredOnly,
                search = spec.search,
                originatorQuery = spec.originatorQuery,
                port = spec.port,
                contentType = spec.contentType,
                packageQuery = spec.packageQuery,
                showDeliveryReports = spec.showDeliveryReports,
                fates = spec.fates.map { it.wire() },
                attentionOnly = spec.attentionOnly,
                rangePreset = spec.rangePreset,
            )

        /** Null for text that is not a saved filter object. */
        fun fromJson(text: String): SavedFilter? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
