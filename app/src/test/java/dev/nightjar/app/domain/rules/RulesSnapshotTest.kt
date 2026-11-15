package dev.nightjar.app.domain.rules

import dev.nightjar.app.domain.known.KnownOriginator
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.EnforcementMatrix
import dev.nightjar.rules.RulesConfig
import dev.nightjar.rules.StringMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One snapshot feeds both the binder and `rules.json`; the rule prefill from a message respects the matrix. */
class RulesSnapshotTest {
    @Test
    fun `known originators are split into exact and prefix lists`() {
        val config =
            RulesSnapshot.build(
                base = RulesConfig.DEFAULT,
                knownOriginators =
                    listOf(
                        KnownOriginator("+15559000000001", KnownOriginator.Kind.EXACT, "bank"),
                        KnownOriginator("+155590", KnownOriginator.Kind.PREFIX, "carrier"),
                        KnownOriginator("+15559000000001", KnownOriginator.Kind.EXACT, "duplicate"),
                    ),
                revision = 42,
            )
        assertEquals(listOf("+15559000000001"), config.knownExact())
        assertEquals(listOf("+155590"), config.knownPrefix())
        assertEquals(42, config.revision())
    }

    @Test
    fun `the matcher agrees with the payload lists`() {
        val matcher = RulesSnapshot.matcher(listOf(KnownOriginator("+155590", KnownOriginator.Kind.PREFIX)))
        assertTrue(matcher.matches("+15559012345678"))
        assertFalse(matcher.matches("+15558800000000"))
    }

    @Test
    fun `known sender values are validated`() {
        assertNull(KnownOriginatorPolicy.validate("+15559876543210"))
        assertEquals("EMPTY", KnownOriginatorPolicy.validate("   "))
        assertEquals("TOO_LONG", KnownOriginatorPolicy.validate("1".repeat(KnownOriginatorPolicy.MAX_VALUE_LENGTH + 1)))
        assertEquals("NOT_A_NUMBER", KnownOriginatorPolicy.validate("++"))
        assertEquals(KnownOriginatorPolicy.Error.NOT_A_NUMBER, KnownOriginatorPolicy.check("--"))
        assertNull(KnownOriginatorPolicy.check("VM-BANK"))
        assertEquals(KnownOriginator.Kind.EXACT, KnownOriginatorPolicy.defaultKind())
        assertTrue(KnownOriginatorPolicy.LABELS.contains(KnownOriginator.LABEL_CARRIER))
    }

    @Test
    fun `prefill uses the class and the sender`() {
        val prefill =
            RulePrefill.forMessage(
                classId = ClassId.WAP_CP,
                originator = "+15559876543210",
                allowedActions = EnforcementMatrix.allowedClassActions(ClassId.WAP_CP),
            )!!
        assertEquals(Verdict.QUARANTINE, prefill.action)
        assertEquals(setOf(ClassId.WAP_CP), prefill.conditions.classes())
        assertEquals(StringMatch.Op.IS, prefill.conditions.originator().op())
        assertEquals("+15559876543210", prefill.conditions.originator().value())
        assertTrue(prefill.conditions.hasSenderPortOrText())
    }

    @Test
    fun `prefill refuses classes that cannot be blocked`() {
        // AC1: a silent ping has no blocking action, so no rule is offered.
        assertNull(
            RulePrefill.forMessage(
                ClassId.SILENT,
                "+15559876543210",
                EnforcementMatrix.allowedClassActions(ClassId.SILENT),
            ),
        )
        assertNull(
            RulePrefill.forMessage(
                ClassId.STATUS,
                null,
                EnforcementMatrix.allowedClassActions(ClassId.STATUS),
            ),
        )
    }

    @Test
    fun `prefill without a sender still narrows by class`() {
        val prefill =
            RulePrefill.forMessage(
                ClassId.DATA_PORT,
                null,
                EnforcementMatrix.allowedClassActions(ClassId.DATA_PORT),
            )!!
        assertNull(prefill.conditions.originator())
        assertEquals(setOf(ClassId.DATA_PORT), prefill.conditions.classes())
    }
}
