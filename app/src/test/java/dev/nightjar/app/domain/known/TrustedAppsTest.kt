package dev.nightjar.app.domain.known

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** B4: one trust identity for the Detail action, ingest and the reclassifier. Example packages only. */
class TrustedAppsTest {
    private val app = "dev.example.tester"
    private val other = "dev.example.other"

    @Test
    fun `an outgoing message names the sending app first, then the result package`() {
        assertEquals(listOf(app, other), TrustedApps.candidates(Direction.OUT, ClassId.MO_DATA, app, other))
        assertEquals(app, TrustedApps.target(Direction.OUT, ClassId.MO_DATA, app, other))
    }

    @Test
    fun `an outgoing message without a calling package uses the result package`() {
        assertEquals(app, TrustedApps.target(Direction.OUT, ClassId.MO_DATA, null, app))
    }

    @Test
    fun `the same package twice is one candidate`() {
        assertEquals(listOf(app), TrustedApps.candidates(Direction.OUT, ClassId.MO_RAW, app, app))
    }

    @Test
    fun `either package of an outgoing message matches a trust`() {
        assertEquals(app, TrustedApps.trustedPackage(setOf(app), Direction.OUT, ClassId.MO_DATA, other, app))
        assertEquals(app, TrustedApps.trustedPackage(setOf(app), Direction.OUT, ClassId.MO_DATA, app, other))
        assertNull(TrustedApps.trustedPackage(setOf("dev.example.third"), Direction.OUT, ClassId.MO_DATA, app, other))
    }

    @Test
    fun `an injection counts only the injecting app, never the app that shows it`() {
        assertEquals(listOf(app), TrustedApps.candidates(Direction.IN, ClassId.INJECTED, app, other))
        assertNull(TrustedApps.trustedPackage(setOf(other), Direction.IN, ClassId.INJECTED, app, other))
    }

    @Test
    fun `an unattributed injection has no app to trust`() {
        assertNull(TrustedApps.target(Direction.IN, ClassId.INJECTED, TrustedApps.UNATTRIBUTED, null))
        assertNull(TrustedApps.trustedPackage(setOf(TrustedApps.UNATTRIBUTED), Direction.IN, ClassId.INJECTED, "unknown", null))
    }

    @Test
    fun `an inbound network message is never covered by trust`() {
        assertFalse(TrustedApps.covers(Direction.IN, ClassId.DATA_PORT))
        assertEquals(emptyList<String>(), TrustedApps.candidates(Direction.IN, ClassId.DATA_PORT, null, app))
        assertNull(TrustedApps.trustedPackage(setOf(app), Direction.IN, ClassId.DATA_PORT, null, app))
        assertTrue(TrustedApps.covers(Direction.OUT, ClassId.MO_DATA))
        assertTrue(TrustedApps.covers(Direction.IN, ClassId.INJECTED))
    }

    @Test
    fun `placeholders and blank values are not apps`() {
        assertFalse(TrustedApps.isTrustable(null))
        assertFalse(TrustedApps.isTrustable(" "))
        assertFalse(TrustedApps.isTrustable("null"))
        assertFalse(TrustedApps.isTrustable(TrustedApps.INTERNAL_PACKAGE))
        assertFalse(TrustedApps.isTrustable("dev.example app"))
        assertTrue(TrustedApps.isTrustable(app))
        assertNull(TrustedApps.target(Direction.OUT, ClassId.MO_STK, TrustedApps.INTERNAL_PACKAGE, " "))
    }
}
