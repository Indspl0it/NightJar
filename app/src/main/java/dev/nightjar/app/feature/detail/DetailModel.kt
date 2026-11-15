package dev.nightjar.app.feature.detail

import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict

/** The action buttons at the bottom of the detail screen. The order of the enum is the order on screen. */
enum class DetailButton { RELEASE, BLOCK_SIMILAR, DELETE }

/**
 * Chooses the action buttons of a message.
 *
 * - Release needs a message on hold that its type lets the user release.
 * - Block similar needs an incoming message that NightJar did not block yet, of a type that a rule can block
 *  ([blockable]).
 * - Delete is always there.
 */
fun detailButtonsFor(
    isQuarantined: Boolean,
    releasable: Boolean,
    direction: Direction,
    fate: Fate,
    blockable: Boolean = true,
): List<DetailButton> =
    buildList {
        if (isQuarantined && releasable) add(DetailButton.RELEASE)
        if (direction == Direction.IN && fate != Fate.BLOCKED && blockable) add(DetailButton.BLOCK_SIMILAR)
        add(DetailButton.DELETE)
    }

/** The colour group of a timeline dot. */
enum class TimelineTone { INFO, HOLD, BLOCK, OK, FAIL }

/**
 * One step of the "What happened" timeline. [linkId] is the id of a linked message that opens when the user taps the step, or
 * null when the step is only text.
 */
data class TimelineStep(
    val text: String,
    val time: String,
    val tone: TimelineTone,
    val linkId: Long? = null,
)

/** The tone of the step that tells what happened to the message. */
fun outcomeTone(
    verdict: Verdict,
    isQuarantined: Boolean,
    fate: Fate,
): TimelineTone =
    when {
        isQuarantined || fate == Fate.QUARANTINED -> TimelineTone.HOLD
        verdict == Verdict.BLOCK || fate == Fate.BLOCKED || fate == Fate.BLOCKLIST -> TimelineTone.BLOCK
        else -> TimelineTone.INFO
    }

/** The tone of a delivery report: delivered is OK, failed is FAIL, anything else is a neutral step. */
fun reportTone(status: String?): TimelineTone =
    when (status) {
        STATUS_DELIVERED -> TimelineTone.OK
        STATUS_FAILED -> TimelineTone.FAIL
        else -> TimelineTone.INFO
    }

const val STATUS_DELIVERED = "DELIVERED"
const val STATUS_PENDING = "PENDING"
const val STATUS_FAILED = "FAILED"

/**
 * One anomaly line in the Evidence card. [anomalyRows] makes them from the event's anomaly codes. [code] is the
 * wire code, which Researcher mode shows after the name.
 */
data class AnomalyRow(
    val text: String,
    val severity: Severity,
    val code: String = "",
)

/**
 * One line of "Other matching types" in the Evidence card: the title of another detection rule that matched.
 * [code] is the raw entry, which Researcher mode shows after the title.
 */
data class ExtraMatchRow(
    val text: String,
    val code: String,
)

/** A decoder field that carries an error. The Evidence card shows one line for each format error. */
data class FormatError(
    val field: String,
    val message: String,
)

/** The values of the Evidence card that come from the decoded PDU. A value is null when the PDU has no such field. */
data class Evidence(
    /** TP-PID as `0x40`, followed by its meaning when the decoder gives one. */
    val pid: String?,
    /** TP-DCS as `0x00`, followed by the alphabet when the decoder gives one. */
    val dcs: String?,
    /** The user data length as a number. */
    val udl: String?,
    val formatErrors: List<FormatError>,
)

private val HEX_BYTE = Regex("^[0-9A-Fa-f]{2}$")
private val NUMBER = Regex("^[0-9]+$")

/**
 * Reads PID, DCS, user data length and the format errors from the flattened decoder tree.
 *
 * The decoder also has boolean fields with the names `pid`, `dcs` and `udl` (the status report parameter flags), so a
 * node counts only when its value has the right shape. For a message of several parts the first part decides.
 */
fun evidenceOf(nodes: List<DecodedNode>): Evidence {
    fun valueNode(
        name: String,
        shape: Regex,
    ): Int = nodes.indexOfFirst { it.name == name && it.value != null && shape.matches(it.value) }

    fun childValue(
        index: Int,
        child: String,
    ): String? {
        val parent = nodes[index]
        for (i in index + 1 until nodes.size) {
            val node = nodes[i]
            if (node.depth <= parent.depth) break
            if (node.name == child && isRealValue(node.value)) return node.value
        }
        return null
    }

    fun hexWithMeaning(
        name: String,
        meaningChild: String,
    ): String? {
        val index = valueNode(name, HEX_BYTE)
        if (index < 0) return null
        val hex = "0x" + nodes[index].value!!.uppercase()
        return childValue(index, meaningChild)?.let { "$hex · $it" } ?: hex
    }
    val udlIndex = valueNode("udl", NUMBER)
    return Evidence(
        pid = hexWithMeaning("pid", "meaning"),
        dcs = hexWithMeaning("dcs", "alphabet"),
        udl = if (udlIndex >= 0) nodes[udlIndex].value else null,
        formatErrors =
            nodes
                .filter { it.error != null }
                .map { FormatError(readableName(it.name), it.error.orEmpty()) },
    )
}

/** The first fact row of a row with the legacy debug flag; null for every other message. */
fun debugTestFact(
    debugInjected: Boolean,
    label: String,
    text: String,
): DetailFact? = if (debugInjected) DetailFact(label = label, value = text, full = true) else null

/** `user_data_header` -> "User data header". */
fun readableName(name: String): String = name.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

/** Placeholder words that a decoder or a lookup can give when a field has no real value. */
private val PLACEHOLDER_VALUES = setOf("none", "unknown", "null", "n/a", "-")

/**
 * True when [value] is a real value. A null, blank or placeholder value ("None", "unknown") is not a real
 * value, so the Evidence card does not show its row.
 */
fun isRealValue(value: String?): Boolean = !value.isNullOrBlank() && value.trim().lowercase() !in PLACEHOLDER_VALUES

/** The Evidence rows that have a real value, in the same order. */
fun factsWithValue(facts: List<DetailFact>): List<DetailFact> = facts.filter { isRealValue(it.value) }

/**
 * True when the Evidence card has a line to show: a fact with a real value, a format error, an anomaly or an
 * other matching type. When this is false, the detail screen does not show the Evidence card.
 */
fun hasEvidence(
    facts: List<DetailFact>,
    formatErrors: List<String>,
    anomalies: List<AnomalyRow>,
    extraMatches: List<ExtraMatchRow>,
): Boolean = factsWithValue(facts).isNotEmpty() || formatErrors.isNotEmpty() || anomalies.isNotEmpty() || extraMatches.isNotEmpty()
