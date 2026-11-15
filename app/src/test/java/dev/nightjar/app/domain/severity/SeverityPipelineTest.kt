package dev.nightjar.app.domain.severity

import dev.nightjar.app.domain.known.KnownOriginator
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Default → override → detector → known-originator downgrade → trusted package. */
class SeverityPipelineTest {
    private val known = KnownOriginatorMatcher(listOf(KnownOriginator("+15559000000001", KnownOriginator.Kind.EXACT)))
    private val config =
        SeverityConfig(
            overrides = mapOf(ClassId.SILENT to Severity.SUSPICIOUS),
            knownOriginators = known,
            trustedPackages = setOf("com.example.trusted"),
        )

    private fun resolve(
        classId: ClassId,
        originator: String? = "+15559876543210",
        direction: Direction = Direction.IN,
        packageName: String? = null,
        simOtaInsecure: Boolean = false,
        detector: Severity? = null,
        config: SeverityConfig = this.config,
    ): Severity =
        SeverityPipeline.resolve(
            config,
            SeverityInput(classId, direction, originator, packageName, simOtaInsecure, detector),
        )

    @Test
    fun `class default applies without overrides`() {
        val plain = SeverityConfig.DEFAULT
        assertEquals(Severity.SUSPICIOUS, resolve(ClassId.WAP_CP, config = plain))
        assertEquals(Severity.SUSPICIOUS, resolve(ClassId.SIM_DD, config = plain))
        assertEquals(Severity.INFO, resolve(ClassId.MWI, config = plain))
        assertEquals(Severity.HIGH, resolve(ClassId.INJECTED, config = plain))
    }

    @Test
    fun `a subtype row gives the default when it has one`() {
        val plain = SeverityConfig.DEFAULT
        val type0Port = SeverityInput(ClassId.SILENT, originator = null, subtype = Subtype.Silent.TYPE0_PORT)
        assertEquals(Severity.HIGH, SeverityPipeline.resolve(plain, type0Port))
        val channel = SeverityInput(ClassId.CB, originator = null, subtype = Subtype.Cb.CHANNEL)
        assertEquals(Severity.NOTABLE, SeverityPipeline.resolve(plain, channel))
        // A CB row without a subtype gets the category of its message identifier: the SIM range is CL-CB-SIM.
        val simRange = SeverityInput(ClassId.CB, originator = null, cbMessageId = 0x1010)
        assertEquals(Severity.SUSPICIOUS, SeverityPipeline.resolve(plain, simRange))
        // The user's override for the class wins over the subtype row.
        val override = SeverityConfig(overrides = mapOf(ClassId.SILENT to Severity.INFO))
        assertEquals(Severity.INFO, SeverityPipeline.resolve(override, type0Port))
    }

    @Test
    fun `severity override replaces the default`() {
        assertEquals(Severity.SUSPICIOUS, resolve(ClassId.SILENT))
        val lowered = SeverityConfig(overrides = mapOf(ClassId.WAP_CP to Severity.INFO))
        assertEquals(Severity.INFO, resolve(ClassId.WAP_CP, config = lowered))
    }

    @Test
    fun `the detector severity replaces the default and the override, up or down`() {
        assertEquals(Severity.SUSPICIOUS, resolve(ClassId.SIM_DD, detector = Severity.SUSPICIOUS))
        assertEquals(Severity.INFO, resolve(ClassId.WAP_SUPL, detector = Severity.INFO))
    }

    @Test
    fun `known originator downgrades one level`() {
        assertEquals(Severity.NOTABLE, resolve(ClassId.WAP_CP, originator = "+15559000000001"))
        assertEquals(Severity.NOTABLE, resolve(ClassId.SIM_DD, originator = "+15559000000001"))
        // Already Info: stays Info, never negative.
        assertEquals(Severity.INFO, resolve(ClassId.MWI, originator = "+15559000000001"))
    }

    @Test
    fun `known originator downgrade applies after the detector`() {
        assertEquals(
            Severity.NOTABLE,
            resolve(ClassId.SIM_DD, originator = "+15559000000001", detector = Severity.SUSPICIOUS),
        )
    }

    @Test
    fun `CL-INJECTED is never downgraded for a known originator`() {
        assertEquals(Severity.HIGH, resolve(ClassId.INJECTED, originator = "+15559000000001"))
    }

    @Test
    fun `insecure SIM update is never downgraded`() {
        assertEquals(
            Severity.SUSPICIOUS,
            resolve(ClassId.SIM_DD, originator = "+15559000000001", simOtaInsecure = true, detector = Severity.SUSPICIOUS),
        )
        // A secure SIM update from a known sender still downgrades.
        assertEquals(Severity.NOTABLE, resolve(ClassId.SIM_DD, originator = "+15559000000001"))
    }

    @Test
    fun `trusted package makes outbound and injected messages info`() {
        assertEquals(
            Severity.INFO,
            resolve(ClassId.MO_DATA, direction = Direction.OUT, packageName = "com.example.trusted"),
        )
        assertEquals(
            Severity.INFO,
            resolve(ClassId.INJECTED, packageName = "com.example.trusted"),
        )
        // Trust only covers outbound and injected traffic.
        assertEquals(Severity.SUSPICIOUS, resolve(ClassId.WAP_CP, packageName = "com.example.trusted"))
        // An untrusted package keeps its severity.
        assertEquals(Severity.HIGH, resolve(ClassId.MO_STK, direction = Direction.OUT, packageName = "com.other"))
    }

    @Test
    fun `downgrade steps exactly one level`() {
        assertEquals(Severity.SUSPICIOUS, SeverityPipeline.downgrade(Severity.HIGH))
        assertEquals(Severity.NOTABLE, SeverityPipeline.downgrade(Severity.SUSPICIOUS))
        assertEquals(Severity.INFO, SeverityPipeline.downgrade(Severity.NOTABLE))
        assertEquals(Severity.INFO, SeverityPipeline.downgrade(Severity.INFO))
    }

    @Test
    fun `prefix and normalised forms match a known originator`() {
        val matcher = KnownOriginatorMatcher(listOf(KnownOriginator("+155590", KnownOriginator.Kind.PREFIX)))
        assertTrue(matcher.matches("+1555 90 0000 0001"))
        assertTrue(matcher.matches("0015559000000001"))
        assertFalse(matcher.matches("+155588"))
        assertTrue(matcher.isEmpty.not())
        assertTrue(KnownOriginatorMatcher.EMPTY.isEmpty)
    }
}
