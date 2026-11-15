package dev.nightjar.app.domain.rules

import dev.nightjar.app.domain.known.KnownOriginator
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Conditions
import dev.nightjar.rules.RuleValidator
import dev.nightjar.rules.RulesConfig
import dev.nightjar.rules.StringMatch

/**
 * Builds the snapshot that is both sent to the payload and written to `rules.json`.
 *
 * One builder for both destinations is the point: a snapshot that differs between the live binder and the file would
 * apply different rules after a phone-process restart.
 *
 * The class actions, custom rules and guard values come from the `RulesRepository`; this object adds the two things
 * that live outside it — the known-originator lists (which the payload needs for `originatorKnown`) and the
 * revision bump.
 */
object RulesSnapshot {
    fun build(
        base: RulesConfig,
        knownOriginators: List<KnownOriginator>,
        revision: Long,
    ): RulesConfig {
        val exact = knownOriginators.filter { it.kind == KnownOriginator.Kind.EXACT }.map { it.value }.distinct()
        val prefix = knownOriginators.filter { it.kind == KnownOriginator.Kind.PREFIX }.map { it.value }.distinct()
        return base.withKnownOriginators(exact, prefix).withRevision(revision)
    }

    /** Matcher over the same list, so the app and the payload agree on what "known" means. */
    fun matcher(knownOriginators: List<KnownOriginator>): KnownOriginatorMatcher = KnownOriginatorMatcher(knownOriginators)
}

/** Validation shared by the "add known sender" sheet and the Known senders editor. */
object KnownOriginatorPolicy {
    const val MAX_VALUE_LENGTH = 32

    /** Why [check] refuses a sender value. */
    enum class Error {
        EMPTY,
        TOO_LONG,
        NOT_A_NUMBER,
    }

    /** A sender value is kept as typed; only the empty, over-long and symbol-only cases are refused. */
    fun check(value: String): Error? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return Error.EMPTY
        if (trimmed.length > MAX_VALUE_LENGTH) return Error.TOO_LONG
        if (trimmed.none { it.isLetterOrDigit() }) return Error.NOT_A_NUMBER
        return null
    }

    /** The name of the [check] result, for the callers that still compare strings. New code uses [check]. */
    fun validate(value: String): String? = check(value)?.name

    /** Labels offered by the "add known sender" sheet; `carrier` is what the OMA CP detector looks for. */
    val LABELS = listOf(KnownOriginator.LABEL_CARRIER, "bank", "other")

    /** An exact entry is the safe default: a prefix silences a whole range of senders. */
    fun defaultKind(): KnownOriginator.Kind = KnownOriginator.Kind.EXACT
}

/**
 * Turns a message into a prefilled rule for the rule editor.
 *
 * Only conditions the user can see on the message are prefilled — the class and the exact originator — so a rule made
 * from a notification is predictable. A class with no blockable action yields no rule at all, because the UI must not
 * offer an action the payload will clamp.
 */
object RulePrefill {
    fun forMessage(
        classId: ClassId,
        originator: String?,
        allowedActions: Set<Verdict>,
    ): Prefill? {
        val action = allowedActions.firstOrNull { it == Verdict.QUARANTINE || it == Verdict.BLOCK } ?: return null
        val builder = Conditions.builder().classes(setOf(classId))
        if (originator != null) builder.originator(StringMatch(StringMatch.Op.IS, originator))
        return Prefill(action = action, conditions = builder.build())
    }

    data class Prefill(
        val action: Verdict,
        val conditions: Conditions,
    )

    /** Validates a candidate document with the shared engine validator. */
    fun validate(config: RulesConfig): List<RuleValidator.Error> = RuleValidator.validate(config)
}
