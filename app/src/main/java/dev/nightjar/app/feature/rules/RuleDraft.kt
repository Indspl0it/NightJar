package dev.nightjar.app.feature.rules

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Schemas
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Conditions
import dev.nightjar.rules.EnforcementMatrix
import dev.nightjar.rules.PortRange
import dev.nightjar.rules.Preset
import dev.nightjar.rules.Rule
import dev.nightjar.rules.RuleOrigin
import dev.nightjar.rules.RuleValidator
import dev.nightjar.rules.RulesConfig
import dev.nightjar.rules.StringMatch

/** An optional condition added with "+ Add condition" (02). */
sealed interface DraftCondition {
    data class Port(
        val from: String = "",
        val to: String = "",
    ) : DraftCondition

    data class ContentType(
        val op: StringMatch.Op = StringMatch.Op.IS,
        val value: String = "",
    ) : DraftCondition

    data class Smsc(
        val op: StringMatch.Op = StringMatch.Op.IS,
        val value: String = "",
    ) : DraftCondition

    data class Roaming(
        val roaming: Boolean? = null,
    ) : DraftCondition

    data class Text(
        val value: String = "",
    ) : DraftCondition
}

/** Which editor field an issue belongs to; the screen puts the text under that row. */
enum class DraftField {
    CLASSES,
    FROM,
    PORT,
    CONTENT_TYPE,
    SMSC,
    TEXT,
    ACTION,
    DOCUMENT,
}

/** Typed editor problems: the shared validator's codes plus two the editor adds (classes, port range). */
enum class DraftIssueCode {
    EMPTY_VALUE,
    REGEX_INVALID,
    REGEX_TOO_LONG,
    PORT_INVALID,
    ACTION_NOT_ALLOWED,
    BLOCKS_ALL_NORMAL,
    TOO_LARGE,
    CLASSES_REQUIRED,

    /** A value that can never match, for example a preset subtype that none of the selected types has. */
    INVALID_VALUE,
}

data class DraftIssue(
    val code: DraftIssueCode,
    val field: DraftField,
    val detail: String = "",
)

/**
 * The mutable rule being edited.
 *
 * Text values stay strings while the user types; [toConditions] is the only place that parses them, and
 * [validateDraft] runs the shared [RuleValidator] on the resulting document, so the editor cannot accept a rule the
 * payload would clamp.
 */
data class RuleDraft(
    val id: Long? = null,
    val name: String = "",
    val nameTouched: Boolean = false,
    val classes: Set<ClassId> = emptySet(),
    val fromEnabled: Boolean = false,
    val fromOp: StringMatch.Op = StringMatch.Op.IS,
    val fromValue: String = "",
    val slot: Int? = null,
    val conditions: List<DraftCondition> = emptyList(),
    val action: Verdict = Verdict.ALLOW,
    val enabled: Boolean = true,
    val direction: Direction? = null,
    /**
     * The `subtypes` condition of the stored rule. The editor shows it but cannot change it; [toConditions]
     * writes it back, so a save never drops it and never widens the rule.
     */
    val subtypes: Set<String>? = null,
    /**
     * Who made the stored rule. Kept so the one-rule validation document matches the stored rule: a preset rule keeps
     * its id in the preset range, which the validator refuses for a user rule.
     */
    val origin: RuleOrigin = RuleOrigin.USER,
) {
    fun port(): DraftCondition.Port? = conditions.filterIsInstance<DraftCondition.Port>().firstOrNull()

    fun contentType(): DraftCondition.ContentType? =
        conditions.filterIsInstance<DraftCondition.ContentType>().firstOrNull()

    fun smsc(): DraftCondition.Smsc? = conditions.filterIsInstance<DraftCondition.Smsc>().firstOrNull()

    fun roaming(): DraftCondition.Roaming? = conditions.filterIsInstance<DraftCondition.Roaming>().firstOrNull()

    fun text(): DraftCondition.Text? = conditions.filterIsInstance<DraftCondition.Text>().firstOrNull()

    /** Only a sender, port or text condition widens what a rule may block. */
    val hasSenderPortOrText: Boolean
        get() =
            (fromEnabled && fromValue.isNotBlank()) ||
                (port()?.let { it.from.isNotBlank() } == true) ||
                !text()?.value.isNullOrBlank()

    /** Parses the draft into the engine's condition block. Invalid port/empty text fields are simply absent. */
    fun toConditions(): Conditions {
        val builder = Conditions.builder()
        if (classes.isNotEmpty()) builder.classes(classes)
        if (fromEnabled && fromValue.isNotBlank()) {
            builder.originator(StringMatch(fromOp, fromValue.trim()))
        }
        smsc()?.let { if (it.value.isNotBlank()) builder.smsc(StringMatch(it.op, it.value.trim())) }
        contentType()?.let {
            if (it.value.isNotBlank()) builder.contentType(StringMatch(it.op, it.value.trim()))
        }
        port()?.let {
            // Trimmed exactly as addPortIssues trims, so a value that passes validation is never dropped here.
            val fromText = it.from.trim()
            val from = fromText.toIntOrNull()
            val to =
                it.to
                    .trim()
                    .ifEmpty { fromText }
                    .toIntOrNull()
            if (from != null && to != null) builder.port(PortRange(from, to))
        }
        slot?.let { builder.slot(it) }
        roaming()?.roaming?.let { builder.roaming(it) }
        text()?.let { if (it.value.isNotBlank()) builder.textContains(it.value) }
        direction?.let { builder.direction(it) }
        subtypes?.let { builder.subtypes(LinkedHashSet(it)) }
        return builder.build()
    }

    /** The engine rule this draft would save. The repository fills the priority; the origin is the stored one. */
    fun toRule(): Rule =
        Rule(
            id ?: 0L,
            0,
            enabled,
            origin,
            name.trim(),
            action,
            toConditions(),
        )

    fun withCondition(condition: DraftCondition): RuleDraft {
        val existing =
            when (condition) {
                is DraftCondition.Port -> conditions.filterIsInstance<DraftCondition.Port>()
                is DraftCondition.ContentType -> conditions.filterIsInstance<DraftCondition.ContentType>()
                is DraftCondition.Smsc -> conditions.filterIsInstance<DraftCondition.Smsc>()
                is DraftCondition.Roaming -> conditions.filterIsInstance<DraftCondition.Roaming>()
                is DraftCondition.Text -> conditions.filterIsInstance<DraftCondition.Text>()
            }
        val next = conditions.toMutableList()
        next.removeAll(existing.toSet())
        next += condition
        return copy(conditions = next)
    }

    fun without(condition: DraftCondition): RuleDraft = copy(conditions = conditions - condition)

    fun withAction(next: Verdict): RuleDraft = copy(action = next)
}

/**
 * Actions the rule may use: the intersection of the `EnforcementMatrix` rule mask of every selected class.
 *
 * A `CL-NORMAL` selection without a sender, port or text condition yields only ALLOW/NOTIFY, which is exactly the
 * G-05 guard (no rule blocks all normal SMS).
 */
fun allowedRuleActions(draft: RuleDraft): List<Verdict> {
    if (draft.classes.isEmpty()) return emptyList()
    val mask =
        draft.classes
            .map { EnforcementMatrix.ruleActionMask(it, draft.hasSenderPortOrText) }
            .reduce { left, right -> left and right }
    return Verdict.entries.filter { EnforcementMatrix.allows(mask, it) }
}

/**
 * Runs the editor's checks plus [RuleValidator].
 *
 * Empty and regex problems are attributed to the row that caused them by validating one condition at a time; the full
 * document is validated for the cross-field rules G-05, class-action membership and the 64 KB limit.
 */
fun validateDraft(draft: RuleDraft): List<DraftIssue> {
    val issues = LinkedHashSet<DraftIssue>()

    if (draft.classes.isEmpty()) {
        issues += DraftIssue(DraftIssueCode.CLASSES_REQUIRED, DraftField.CLASSES)
    }

    if (draft.fromEnabled) {
        addStringIssues(issues, draft, draft.fromValue, DraftField.FROM) {
            Conditions.builder().originator(StringMatch(draft.fromOp, draft.fromValue.trim())).build()
        }
    }

    draft.smsc()?.let { condition ->
        addStringIssues(issues, draft, condition.value, DraftField.SMSC) {
            Conditions.builder().smsc(StringMatch(condition.op, condition.value.trim())).build()
        }
    }

    draft.contentType()?.let { condition ->
        addStringIssues(issues, draft, condition.value, DraftField.CONTENT_TYPE) {
            Conditions.builder().contentType(StringMatch(condition.op, condition.value.trim())).build()
        }
    }

    draft.text()?.let { if (it.value.isBlank()) issues += DraftIssue(DraftIssueCode.EMPTY_VALUE, DraftField.TEXT) }

    draft.port()?.let { addPortIssues(issues, it) }

    if (draft.classes.isNotEmpty() && draft.action !in allowedRuleActions(draft)) {
        issues += DraftIssue(DraftIssueCode.ACTION_NOT_ALLOWED, DraftField.ACTION)
    }

    addValidatorIssues(issues, draft)
    return issues.toList()
}

private fun addStringIssues(
    issues: MutableCollection<DraftIssue>,
    draft: RuleDraft,
    raw: String,
    field: DraftField,
    condition: () -> Conditions,
) {
    if (raw.isBlank()) {
        issues += DraftIssue(DraftIssueCode.EMPTY_VALUE, field)
        return
    }
    issues += singleFieldIssues(draft, condition(), field)
}

private fun addPortIssues(
    issues: MutableCollection<DraftIssue>,
    condition: DraftCondition.Port,
) {
    val from = condition.from.trim()
    val to = condition.to.trim()
    if (from.isEmpty()) {
        issues += DraftIssue(DraftIssueCode.EMPTY_VALUE, DraftField.PORT)
        return
    }
    val fromValue = from.toIntOrNull()
    val toValue = if (to.isEmpty()) fromValue else to.toIntOrNull()
    if (fromValue == null || toValue == null || !PortRange(fromValue, toValue).isValid) {
        issues += DraftIssue(DraftIssueCode.PORT_INVALID, DraftField.PORT)
    }
}

private fun addValidatorIssues(
    issues: MutableCollection<DraftIssue>,
    draft: RuleDraft,
) {
    for (error in RuleValidator.validate(documentOf(draft))) {
        when (error.code()) {
            RuleValidator.Code.BLOCKS_ALL_NORMAL -> {
                issues += DraftIssue(DraftIssueCode.BLOCKS_ALL_NORMAL, DraftField.ACTION, error.detail())
            }

            RuleValidator.Code.TOO_LARGE -> {
                issues += DraftIssue(DraftIssueCode.TOO_LARGE, DraftField.DOCUMENT, error.detail())
            }

            RuleValidator.Code.ACTION_NOT_ALLOWED_FOR_CLASS -> {
                issues += DraftIssue(DraftIssueCode.ACTION_NOT_ALLOWED, DraftField.ACTION, error.detail())
            }

            // A bad port range is reported on the Port row by addPortIssues; what is left is a subtype that the
            // selected types do not have, or subtypes with no type.
            RuleValidator.Code.INVALID_VALUE -> {
                if (draft.port() == null || issues.none { it.field == DraftField.PORT }) {
                    issues += DraftIssue(DraftIssueCode.INVALID_VALUE, DraftField.CLASSES, error.detail())
                }
            }

            // Ids are the repository's job: the one-rule document carries the stored id and origin, so this code
            // cannot come from an edit.
            RuleValidator.Code.INVALID_ID -> {
                Unit
            }

            // Field-level codes are attributed by the per-field passes above.
            RuleValidator.Code.REGEX_INVALID,
            RuleValidator.Code.REGEX_TOO_LONG,
            RuleValidator.Code.EMPTY_VALUE,
            -> {
                Unit
            }
        }
    }
}

/** Validates one condition alone, as an enabled ALLOW rule, so its error lands on its own row. */
private fun singleFieldIssues(
    draft: RuleDraft,
    conditions: Conditions,
    field: DraftField,
): List<DraftIssue> {
    val rule = Rule(draft.id ?: 0L, 0, true, RuleOrigin.USER, draft.name, Verdict.ALLOW, conditions)
    return RuleValidator
        .validate(documentOf(rule))
        .mapNotNull { error ->
            when (error.code()) {
                RuleValidator.Code.REGEX_INVALID -> {
                    DraftIssue(DraftIssueCode.REGEX_INVALID, field, error.detail())
                }

                RuleValidator.Code.REGEX_TOO_LONG -> {
                    DraftIssue(DraftIssueCode.REGEX_TOO_LONG, field)
                }

                RuleValidator.Code.EMPTY_VALUE -> {
                    DraftIssue(DraftIssueCode.EMPTY_VALUE, field)
                }

                else -> {
                    null
                }
            }
        }
}

/** A one-rule document used for validation, the previews and the 64 KB check. */
fun documentOf(draft: RuleDraft): RulesConfig = documentOf(draft.toRule())

/** The same one-rule document for an engine [rule]: every preview and check evaluates exactly this shape. */
fun documentOf(rule: Rule): RulesConfig =
    RulesConfig(
        Schemas.RULES,
        0,
        Preset.CUSTOM,
        emptyMap(),
        listOf(rule),
        emptyList(),
        emptyList(),
        RulesConfig.SUPL_GRACE_MIN_FLOOR,
    )

/** Turns a stored rule back into an editable draft (edit). */
fun draftOf(rule: Rule): RuleDraft {
    val conditions = rule.`when`()
    val extras = ArrayList<DraftCondition>()
    conditions.smsc()?.let { extras += DraftCondition.Smsc(it.op(), it.value()) }
    conditions.port()?.let { extras += DraftCondition.Port(it.from().toString(), it.to().toString()) }
    conditions.contentType()?.let { extras += DraftCondition.ContentType(it.op(), it.value()) }
    conditions.roaming()?.let { extras += DraftCondition.Roaming(it) }
    conditions.textContains()?.let { extras += DraftCondition.Text(it) }
    return RuleDraft(
        id = rule.id(),
        name = rule.name(),
        nameTouched = rule.name().isNotBlank(),
        classes = conditions.classes()?.toSet() ?: emptySet(),
        fromEnabled = conditions.originator() != null,
        fromOp = conditions.originator()?.op() ?: StringMatch.Op.IS,
        fromValue = conditions.originator()?.value() ?: "",
        slot = conditions.slot(),
        conditions = extras,
        action = rule.action(),
        enabled = rule.enabled(),
        direction = conditions.direction(),
        subtypes = conditions.subtypes()?.let { LinkedHashSet(it) },
        origin = rule.origin(),
    )
}
