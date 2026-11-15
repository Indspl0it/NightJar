package dev.nightjar.app.feature.health

import dev.nightjar.app.data.model.Coverage
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.selftest.CanaryClass
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Conditions
import dev.nightjar.rules.Rule
import dev.nightjar.rules.RuleOrigin
import dev.nightjar.rules.RulesConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure self-test helpers: hook health, the block confirmation, canary preconditions and coverage order. */
class SelfTestLogicTest {
    @Test
    fun `hook health states are read from the control binder document`() {
        val json = """{"hooks":{"HK-08":{"state":"LIVE"},"HK-14":{"state":"FAILED"}}}"""
        assertEquals(mapOf("HK-08" to "LIVE", "HK-14" to "FAILED"), hookStatesFromHealth(json))
        assertTrue(hookStatesFromHealth(null).isEmpty())
        assertTrue(hookStatesFromHealth("").isEmpty())
        assertTrue(hookStatesFromHealth("{}").isEmpty())
        assertTrue(hookStatesFromHealth("not json").isEmpty())
    }

    @Test
    fun `a class action that blocks asks for confirmation`() {
        assertFalse(hasBlockingRules(RulesConfig.DEFAULT))
        assertTrue(hasBlockingRules(RulesConfig.DEFAULT.withClassAction(ClassId.WAP_CP, Verdict.BLOCK)))
        assertTrue(hasBlockingRules(RulesConfig.DEFAULT.withClassAction(ClassId.WAP_CP, Verdict.QUARANTINE)))
        assertFalse(hasBlockingRules(RulesConfig.DEFAULT.withClassAction(ClassId.WAP_CP, Verdict.NOTIFY)))
    }

    @Test
    fun `an enabled blocking rule asks for confirmation and a disabled one does not`() {
        val rule =
            Rule(
                1,
                1,
                true,
                RuleOrigin.USER,
                "mine",
                Verdict.BLOCK,
                Conditions.ANY,
            )
        assertTrue(hasBlockingRules(RulesConfig.DEFAULT.withRules(listOf(rule))))
        assertFalse(hasBlockingRules(RulesConfig.DEFAULT.withRules(listOf(rule.withEnabled(false)))))
        assertFalse(
            hasBlockingRules(
                RulesConfig.DEFAULT.withRules(listOf(rule.withAction(Verdict.ALLOW))),
            ),
        )
    }

    @Test
    fun `appendix F preconditions skip only the canaries whose hook is missing`() {
        val live = listOf("HK-06", "HK-07", "HK-08", "HK-14", "HK-15", "HK-23").associateWith { "LIVE" }
        assertTrue(skippedCanaries(CanaryClass.order, live).isEmpty())

        val failed = live + ("HK-14" to "FAILED")
        assertEquals(
            mapOf(CanaryClass.WAP_SI to "HK-14", CanaryClass.WAP_CP to "HK-14"),
            skippedCanaries(CanaryClass.order, failed),
        )

        val missing = live - "HK-23"
        assertEquals(mapOf(CanaryClass.MWI to "HK-23"), skippedCanaries(CanaryClass.order, missing))
    }

    @Test
    fun `no hook health means no pre-skip so the payload decides`() {
        assertTrue(skippedCanaries(CanaryClass.order, emptyMap()).isEmpty())
    }

    @Test
    fun `silent needs both capture hooks`() {
        val live = listOf("HK-06", "HK-08", "HK-14", "HK-15", "HK-23").associateWith { "LIVE" }
        assertEquals(mapOf(CanaryClass.SILENT to "HK-07"), skippedCanaries(listOf(CanaryClass.SILENT), live))
    }

    @Test
    fun `coverage covers every network class including never-seen ones`() {
        val classes = networkClasses()
        assertTrue(classes.contains(ClassId.SILENT))
        assertTrue(classes.contains(ClassId.WAP_CP))
        assertTrue(classes.contains(ClassId.SIM_DD))
        assertFalse(classes.contains(ClassId.NORMAL), "normal messages are counters only")
        assertFalse(classes.contains(ClassId.INJECTED), "injected messages do not arrive from the network")
        assertFalse(classes.contains(ClassId.STATUS), "delivery reports are not a protection type")
        assertFalse(classes.contains(ClassId.MO_VISIBLE), "outbound classes are not network coverage")

        val rows = orderedCoverage(emptyList(), classes)
        assertEquals(classes, rows.map { it.classId })
        assertTrue(rows.all { it.lastSeenFromNetwork == null })
    }

    @Test
    fun `an existing coverage row keeps its date and order`() {
        val rows =
            orderedCoverage(
                listOf(Coverage(ClassId.SILENT, 1234L, 99L, true)),
                listOf(ClassId.FLASH, ClassId.SILENT, ClassId.WAP_CP),
            )
        assertEquals(listOf(ClassId.FLASH, ClassId.SILENT, ClassId.WAP_CP), rows.map { it.classId })
        assertEquals(1234L, rows[1].lastSeenFromNetwork)
        assertNull(rows[0].lastSeenFromNetwork)
        assertNull(rows[2].lastSeenFromNetwork)
    }

    @Test
    fun `the registry order is used for coverage`() {
        val rows = orderedCoverage(emptyList(), networkClasses())
        val expected = ClassRegistry.all().filter { it.classId in networkClasses() }.map { it.classId }
        assertEquals(expected, rows.map { it.classId })
    }
}
