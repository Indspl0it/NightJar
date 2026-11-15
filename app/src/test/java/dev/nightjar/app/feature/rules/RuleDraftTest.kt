package dev.nightjar.app.feature.rules

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Conditions
import dev.nightjar.rules.PortRange
import dev.nightjar.rules.Presets
import dev.nightjar.rules.Rule
import dev.nightjar.rules.RuleOrigin
import dev.nightjar.rules.StringMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The editor's draft, the enforcement matrix action lists and the guard against blocking every normal message. */
class RuleDraftTest {
    @Test
    fun `blocking a normal message without a sender is refused by the shared validator`() {
        val draft =
            RuleDraft(
                classes = setOf(ClassId.NORMAL),
                action = Verdict.QUARANTINE,
            )
        val issues = validateDraft(draft)
        assertTrue(issues.any { it.code == DraftIssueCode.BLOCKS_ALL_NORMAL && it.field == DraftField.ACTION })
    }

    @Test
    fun `a sender condition widens normal messages to every action`() {
        val draft =
            RuleDraft(
                classes = setOf(ClassId.NORMAL),
                fromEnabled = true,
                fromValue = "+10000004821",
                action = Verdict.QUARANTINE,
            )
        assertEquals(
            listOf(Verdict.ALLOW, Verdict.NOTIFY, Verdict.QUARANTINE, Verdict.BLOCK),
            allowedRuleActions(draft),
        )
        assertFalse(validateDraft(draft).any { it.code == DraftIssueCode.BLOCKS_ALL_NORMAL })
    }

    @Test
    fun `without a sender a normal message can only be allowed or notified`() {
        val draft = RuleDraft(classes = setOf(ClassId.NORMAL), action = Verdict.ALLOW)
        assertEquals(listOf(Verdict.ALLOW, Verdict.NOTIFY), allowedRuleActions(draft))
    }

    @Test
    fun `no selected class means no action choice`() {
        val draft = RuleDraft(action = Verdict.ALLOW)
        assertTrue(allowedRuleActions(draft).isEmpty())
        assertTrue(validateDraft(draft).any { it.code == DraftIssueCode.CLASSES_REQUIRED })
    }

    @Test
    fun `an invalid regex is reported on the From row`() {
        val draft =
            RuleDraft(
                classes = setOf(ClassId.WAP_CP),
                fromEnabled = true,
                fromOp = StringMatch.Op.REGEX,
                fromValue = "[",
                action = Verdict.ALLOW,
            )
        val issue = validateDraft(draft).first { it.code == DraftIssueCode.REGEX_INVALID }
        assertEquals(DraftField.FROM, issue.field)
    }

    @Test
    fun `an empty enabled From value is an error`() {
        val draft = RuleDraft(classes = setOf(ClassId.WAP_CP), fromEnabled = true, fromValue = " ")
        assertTrue(validateDraft(draft).any { it.code == DraftIssueCode.EMPTY_VALUE && it.field == DraftField.FROM })
    }

    @Test
    fun `port values outside 0 to 65535 are rejected`() {
        val draft =
            RuleDraft(
                classes = setOf(ClassId.DATA_PORT),
                conditions = listOf(DraftCondition.Port(from = "70000", to = "")),
                action = Verdict.ALLOW,
            )
        assertTrue(validateDraft(draft).any { it.code == DraftIssueCode.PORT_INVALID && it.field == DraftField.PORT })
    }

    @Test
    fun `a valid port range becomes the engine condition`() {
        val draft =
            RuleDraft(
                classes = setOf(ClassId.DATA_PORT),
                conditions = listOf(DraftCondition.Port(from = "2948", to = "2949")),
            )
        assertEquals(PortRange(2948, 2949), draft.toConditions().port())
    }

    @Test
    fun `a port value with spaces passes validation and stays in the saved rule`() {
        val draft =
            RuleDraft(
                classes = setOf(ClassId.DATA_PORT),
                conditions = listOf(DraftCondition.Port(from = " 2948 ", to = "")),
                action = Verdict.ALLOW,
            )
        assertTrue(validateDraft(draft).none { it.field == DraftField.PORT })
        assertEquals(PortRange(2948, 2948), draft.toConditions().port())
    }

    @Test
    fun `editing a preset rule keeps its origin, so its preset id is valid`() {
        val conditions =
            Conditions
                .builder()
                .classes(ClassId.WAP_SISL)
                .subtypes("SL")
                .build()
        val preset = Rule(Presets.P_REC_1_ID, 0, true, RuleOrigin.PRESET, "preset", Verdict.BLOCK, conditions)
        val draft = draftOf(preset)
        assertEquals(RuleOrigin.PRESET, draft.toRule().origin())
        assertTrue(validateDraft(draft).isEmpty())
    }

    @Test
    fun `a stored rule round-trips through the draft`() {
        val conditions =
            Conditions
                .builder()
                .classes(ClassId.WAP_CP, ClassId.WAP_DM)
                .originator(StringMatch.prefix("+9198"))
                .port(PortRange.exact(2948))
                .textContains("token")
                .roaming(false)
                .build()
        val rule = Rule(7L, 3, true, RuleOrigin.USER, "settings", Verdict.QUARANTINE, conditions)
        val draft = draftOf(rule)
        assertEquals(7L, draft.id)
        assertEquals(setOf(ClassId.WAP_CP, ClassId.WAP_DM), draft.classes)
        assertTrue(draft.fromEnabled)
        assertEquals(StringMatch.Op.PREFIX, draft.fromOp)
        assertEquals("+9198", draft.fromValue)
        assertEquals(Verdict.QUARANTINE, draft.action)
        assertEquals(2948, draft.toConditions().port()?.from())
        assertEquals("token", draft.toConditions().textContains())
        assertEquals(false, draft.toConditions().roaming())
        assertEquals(conditions, draft.toConditions())
    }

    @Test
    fun `editing a rule never drops its subtypes condition`() {
        val conditions =
            Conditions
                .builder()
                .classes(ClassId.WAP_SISL)
                .subtypes("SL")
                .build()
        val rule = Rule(9L, 0, true, RuleOrigin.PRESET, "Block auto-open link pushes", Verdict.BLOCK, conditions)
        val draft = draftOf(rule)
        assertEquals(setOf("SL"), draft.subtypes)
        // A save after an edit keeps the condition, so the rule does not widen to every SI and SL push.
        val edited = draft.copy(name = "Block SL", action = Verdict.QUARANTINE).withCondition(DraftCondition.Text("x"))
        assertEquals(setOf("SL"), edited.toConditions().subtypes())
        assertEquals(conditions, draft.toConditions())
        assertNull(draftOf(Rule(1L, 0, true, RuleOrigin.USER, "n", Verdict.ALLOW, Conditions.ANY)).subtypes)
        assertTrue(validateDraft(draft).isEmpty())
    }

    @Test
    fun `removing a condition leaves the others`() {
        val port = DraftCondition.Port(from = "1", to = "1")
        val text = DraftCondition.Text(value = "x")
        val draft = RuleDraft(classes = setOf(ClassId.DATA_PORT)).withCondition(port).withCondition(text)
        val withoutPort = draft.without(port)
        assertNull(withoutPort.port())
        assertEquals("x", withoutPort.text()?.value)
    }
}
