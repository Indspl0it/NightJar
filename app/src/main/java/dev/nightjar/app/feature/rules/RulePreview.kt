package dev.nightjar.app.feature.rules

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Verdict
import dev.nightjar.decoder.Decoders
import dev.nightjar.decoder.Field
import dev.nightjar.rules.Decision
import dev.nightjar.rules.Guards
import dev.nightjar.rules.MatchInput
import dev.nightjar.rules.RuleEngine

/** Result of decoding the pasted PDU and evaluating the draft rule against it (04). */
sealed interface RulePreview {
    data object Empty : RulePreview

    /** Why a paste cannot be previewed. The screen shows a translated text for each reason, never decoder English. */
    enum class InvalidReason {
        /** The decoder reported an error: not hex, odd length, truncated or a wrong field. */
        MALFORMED,

        /** The PDU decoded, but it has no sender, SMSC or text the rule could test. */
        NO_FIELDS,
    }

    data class Invalid(
        val reason: InvalidReason,
    ) : RulePreview

    data class Decoded(
        val originator: String?,
        val smsc: String?,
        val destinationPort: Int?,
        val matched: Boolean,
        val matchedClass: ClassId?,
        /** What the engine would do: the rule's action after the clamps, null when the rule does not match. */
        val action: Verdict?,
        /** True when a clamp changed the rule's action (for example the matrix). */
        val clamped: Boolean = false,
    ) : RulePreview
}

/**
 * Decodes one TPDU with the shared `:decoder` and evaluates [draft] against the facts the decoder exposes.
 *
 * The preview never guesses: class, sender, SMSC, port (from a UDH port IE) and text come from the byte tree, and the
 * result is what [RuleEngine] returns for the same one-rule document the save would produce ([documentOf]). A PDU
 * cannot tell us the SIM slot or roaming state, so those inputs use the draft's own choice; content type is absent at
 * TPDU level.
 */
fun previewRule(
    draft: RuleDraft,
    hex: String,
): RulePreview {
    // A paste from a terminal or a file can carry CR, tabs or other spaces between the bytes.
    val trimmed = hex.filterNot(Char::isWhitespace)
    if (trimmed.isEmpty()) return RulePreview.Empty
    val field = Decoders.decode(Decoders.TPDU, trimmed)
    if (firstError(field) != null) return RulePreview.Invalid(RulePreview.InvalidReason.MALFORMED)
    val originator = field.textValue("originator")?.takeIf { it.isNotBlank() }
    val smsc = field.textValue("smsc")?.takeIf { it.isNotBlank() && it != "none" }
    val port = destinationPort(field)
    val textField = field.child("user_data/text")
    val text = textField?.value?.toString()
    if (originator == null && smsc == null && text == null) {
        return RulePreview.Invalid(RulePreview.InvalidReason.NO_FIELDS)
    }

    val engine = RuleEngine.compile(documentOf(draft.copy(enabled = true)))
    val guards = Guards.NOMINAL
    val classes = draft.classes.ifEmpty { setOf<ClassId?>(null) }
    var matchedClass: ClassId? = null
    var decision: Decision? = null
    for (classId in classes) {
        val input =
            MatchInput()
                .classId(classId)
                .direction(Direction.IN)
                .originator(originator)
                .smsc(smsc)
                .destPort(port ?: -1)
                .slot(draft.slot ?: -1)
                .roaming(draft.roaming()?.roaming ?: false)
                .apply { if (text != null) textProvider { text } }
        val candidate = Decision()
        engine.evaluate(input, guards, candidate)
        if (candidate.hasRule()) {
            matchedClass = classId
            decision = candidate
            break
        }
    }
    return RulePreview.Decoded(
        originator = originator,
        smsc = smsc,
        destinationPort = port,
        matched = decision != null,
        matchedClass = matchedClass,
        action = decision?.action(),
        clamped = decision?.isClamped == true,
    )
}

private fun firstError(field: Field): String? {
    if (field.error != null) return field.error
    for (child in field.children) {
        firstError(child)?.let { return it }
    }
    return null
}

/** Finds the destination port from a UDH port IE (`port_8bit` / `port_16bit`). */
private fun destinationPort(field: Field): Int? {
    val udh = field.child("user_data/udh") ?: return null
    val port = udh.children.firstOrNull(::isPortElement)?.childValue("destination_port") ?: return null
    return when (port) {
        is Number -> port.toInt()
        else -> port.toString().toIntOrNull()
    }
}

private fun isPortElement(element: Field): Boolean {
    if (element.name != "information_element") return false
    val kind = element.childValue("name")?.toString()
    return (kind == "port_8bit" || kind == "port_16bit") && element.childValue("destination_port") != null
}
