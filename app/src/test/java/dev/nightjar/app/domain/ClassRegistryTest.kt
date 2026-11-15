package dev.nightjar.app.domain

import dev.nightjar.app.domain.cb.CbCategory
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype
import dev.nightjar.payload.firewall.ReleaseEligibility
import dev.nightjar.rules.EnforcementMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every ClassId has a complete registry entry. */
class ClassRegistryTest {
    @Test
    fun `every class has an entry`() {
        for (classId in ClassId.entries) {
            val profile = ClassRegistry.of(classId)
            assertEquals(classId, profile.classId)
        }
        assertEquals(ClassId.entries.size, ClassRegistry.all().size)
    }

    @Test
    fun `registry matches the contract class set exactly`() {
        val registered = ClassRegistry.all().map { it.classId }.toSet()
        assertEquals(ClassId.entries.toSet(), registered)
    }

    @Test
    fun `default severities follow the design taxonomy`() {
        // The class taxonomy ("High" is Severity.HIGH).
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.WAP_CP))
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.WAP_SUPL))
        assertEquals(Severity.HIGH, ClassRegistry.defaultSeverity(ClassId.INJECTED))
        assertEquals(Severity.HIGH, ClassRegistry.defaultSeverity(ClassId.MO_STK))
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.SILENT))
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.SIM_DD))
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.REPLACE))
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.DATA_PORT))
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.FLASH))
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.WAP_DM))
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.WAP_SISL))
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.OEM))
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.APP_TOKEN))
        assertEquals(Severity.INFO, ClassRegistry.defaultSeverity(ClassId.MWI))
        assertEquals(Severity.INFO, ClassRegistry.defaultSeverity(ClassId.VVM))
        assertEquals(Severity.INFO, ClassRegistry.defaultSeverity(ClassId.CARRIER))
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.SIM_STORED))
        assertEquals(Severity.INFO, ClassRegistry.defaultSeverity(ClassId.STATUS))
        assertEquals(Severity.INFO, ClassRegistry.defaultSeverity(ClassId.CB))
        assertEquals(Severity.INFO, ClassRegistry.defaultSeverity(ClassId.MO_VVM))
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.MO_DATA))
        assertEquals(Severity.HIGH, ClassRegistry.defaultSeverity(ClassId.ME_DEPERSO))
        assertEquals(Severity.HIGH, ClassRegistry.defaultSeverity(ClassId.WAP_UNREADABLE))
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.MALFORMED_SMS))
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.CB_SIM))
        assertEquals(Severity.INFO, ClassRegistry.defaultSeverity(ClassId.WAP_EMN))
    }

    @Test
    fun `subtype rows override the class default`() {
        assertEquals(Severity.HIGH, ClassRegistry.defaultSeverity(ClassId.SILENT, Subtype.Silent.TYPE0_PORT))
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.SILENT, Subtype.Silent.TYPE0))
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.FLASH, Subtype.Flash.CLASS0_EMPTY))
        assertEquals(Severity.HIGH, ClassRegistry.defaultSeverity(ClassId.WAP_SUPL, Subtype.WapSupl.SILENT))
        // A code without a row, and no code, give the class default.
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.FLASH, "NOT-A-CODE"))
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.FLASH, null))
    }

    @Test
    fun `cell broadcast severity comes from CbCategory`() {
        assertEquals(Severity.INFO, ClassRegistry.defaultSeverity(ClassId.CB, Subtype.Cb.PRESIDENTIAL))
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.CB, Subtype.Cb.CHANNEL))
        assertEquals(Severity.NOTABLE, ClassRegistry.defaultSeverity(ClassId.CB, null, 221))
        assertEquals(Severity.SUSPICIOUS, ClassRegistry.defaultSeverity(ClassId.CB, null, 0x1010))
        assertTrue(ClassRegistry.subtypes(ClassId.CB).isEmpty())
    }

    @Test
    fun `every subtype row names a known code of its class`() {
        for (classId in ClassId.entries) {
            for (row in ClassRegistry.subtypes(classId)) {
                assertEquals(classId, row.classId)
                for (code in row.codes) {
                    assertTrue(code in Subtype.known(classId), "$code is not a known subtype of $classId")
                }
            }
        }
    }

    @Test
    fun `every known code of a class has a subtype row with a title and an explanation`() {
        // The other direction of the test above. Cell broadcast codes have their rows in CbCategory.
        for (classId in ClassId.entries) {
            for (code in Subtype.known(classId)) {
                if (classId == ClassId.CB) {
                    assertTrue(CbCategory.titleKey(classId, code, null) != null, "$code of $classId has no CB title")
                    assertTrue(CbCategory.explanationKey(classId, code, null) != null, "$code of $classId has no CB explanation")
                } else {
                    assertTrue(ClassRegistry.subtype(classId, code) != null, "$code of $classId has no subtype row")
                }
            }
        }
    }

    @Test
    fun `a port class without a code takes the subtype of the port table`() {
        assertEquals(Subtype.DataPort.WAP_PUSH, ClassRegistry.effectiveSubtype(ClassId.DATA_PORT, null, 2948))
        assertEquals("CODE", ClassRegistry.effectiveSubtype(ClassId.DATA_PORT, "CODE", 2948))
        assertNull(ClassRegistry.effectiveSubtype(ClassId.DATA_PORT, null, null))
    }

    @Test
    fun `counter-only classes never reach the feed`() {
        assertTrue(ClassRegistry.isCounterOnly(ClassId.NORMAL))
        assertTrue(ClassRegistry.isCounterOnly(ClassId.MO_VISIBLE))
        assertFalse(ClassRegistry.isFeedVisible(ClassId.NORMAL))
        assertFalse(ClassRegistry.isFeedVisible(ClassId.MO_VISIBLE))
        // Delivery reports are in the feed by default; the setting can hide them.
        assertTrue(ClassRegistry.isFeedVisible(ClassId.STATUS))
        assertTrue(ClassRegistry.isFeedVisible(ClassId.SILENT))
        assertTrue(ClassRegistry.isFeedVisible(ClassId.CB))
    }

    @Test
    fun `releasable classes match appendix D`() {
        val never =
            listOf(
                ClassId.SILENT,
                ClassId.SIM_DD,
                ClassId.SIM_STORED,
                ClassId.STATUS,
                ClassId.CB,
                ClassId.MALFORMED_SMS,
                ClassId.VVM, // No enforcement point holds it
            )
        for (classId in never) {
            assertFalse(ClassRegistry.isReleasable(classId), "$classId must not be releasable")
        }
        val always =
            listOf(
                ClassId.WAP_CP,
                ClassId.INJECTED,
                ClassId.DATA_PORT,
                ClassId.FLASH,
                ClassId.ME_DOWNLOAD,
                ClassId.ME_DEPERSO,
                ClassId.MWI,
            )
        for (classId in always) {
            assertTrue(ClassRegistry.isReleasable(classId), "$classId must be releasable")
        }
    }

    @Test
    fun `an unreadable SMS is a held, releasable system class`() {
        // The wrong message type. AOSP still reads it, so Recommended holds it and a release works.
        val profile = ClassRegistry.of(ClassId.UNREADABLE_SMS)
        assertEquals(ClassFamily.SYSTEM, profile.family)
        assertEquals(Severity.SUSPICIOUS, profile.defaultSeverity)
        assertEquals(Direction.IN, profile.direction)
        assertTrue(profile.feedVisible)
        assertTrue(ClassRegistry.isReleasable(ClassId.UNREADABLE_SMS))
        assertEquals(dev.nightjar.app.domain.text.TextKey.TITLE_CL_UNREADABLE_SMS, profile.titleKey)
        assertEquals(dev.nightjar.app.domain.text.TextKey.EXP_CL_UNREADABLE_SMS, profile.explanationKey)
        assertEquals(
            dev.nightjar.contract.Verdict.QUARANTINE,
            dev.nightjar.rules.Presets
                .recommendedClassAction(ClassId.UNREADABLE_SMS),
        )
    }

    @Test
    fun `the app and the payload agree on which classes can be released`() {
        // The app offers Release only where the payload accepts it, so the button cannot fail by design.
        for (classId in ClassId.entries) {
            assertEquals(ReleaseEligibility.releasable(classId), ClassRegistry.isReleasable(classId), "$classId")
        }
    }

    @Test
    fun `the app and the rules matrix agree on which classes can be released`() {
        // EnforcementMatrix.releasable is the one table. The app has no own copy: both app paths
        // (ClassRegistry.isReleasable for Detail, ReleaseGateway and notifications, ClassProfile.releasable for Home)
        // read it. No Hold mask exists for a class that nothing can release.
        for (classId in ClassId.entries) {
            assertEquals(EnforcementMatrix.releasable(classId), ClassRegistry.isReleasable(classId), "$classId")
            assertEquals(EnforcementMatrix.releasable(classId), ClassRegistry.of(classId).releasable, "$classId")
        }
    }

    @Test
    fun `outbound classes are outbound and inbound classes are inbound`() {
        for (classId in listOf(
            ClassId.MO_DATA,
            ClassId.MO_RAW,
            ClassId.MO_STK,
            ClassId.MO_VISIBLE,
            ClassId.MO_UNPERSISTED,
            ClassId.MO_VVM,
        )) {
            assertEquals(Direction.OUT, ClassRegistry.of(classId).direction, "$classId")
        }
        for (classId in listOf(ClassId.SILENT, ClassId.WAP_CP, ClassId.CB, ClassId.NORMAL)) {
            assertEquals(Direction.IN, ClassRegistry.of(classId).direction, "$classId")
        }
    }

    @Test
    fun `silent pings offer no blocking action`() {
        // AC1 / silence uses mutes, never a verdict.
        val silent = ClassRegistry.of(ClassId.SILENT)
        assertEquals(PrimaryAction.MARK_EXPECTED, silent.primary)
        assertFalse(
            dev.nightjar.rules.EnforcementMatrix
                .allowedClassActions(ClassId.SILENT)
                .contains(dev.nightjar.contract.Verdict.BLOCK),
        )
    }
}
