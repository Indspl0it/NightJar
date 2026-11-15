package dev.nightjar.app.domain.known

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Known originators; mutes. */
class KnownOriginatorsTest {
    @Test
    fun `exact and prefix entries match normalised numbers`() {
        val matcher =
            KnownOriginatorMatcher(
                listOf(
                    KnownOriginator("+15559000000001", KnownOriginator.Kind.EXACT, "bank"),
                    KnownOriginator("+155590", KnownOriginator.Kind.PREFIX, "carrier"),
                ),
            )
        assertEquals("bank", matcher.find("+15559000000001")?.label)
        // An exact entry wins for its own value; other numbers fall back to the prefix entry.
        assertEquals("carrier", matcher.find("+1555 90 1234 5678")?.label)
        assertEquals("carrier", matcher.find("001555 90 1234 5678")?.label)
        assertNull(matcher.find("+15558800000000"))
        assertNull(matcher.find(null))
        assertTrue(matcher.matches("+15559012345678"))
        assertTrue(matcher.isCarrier("+15559012345678"))
        assertFalse(matcher.isCarrier("+15559000000001"))
    }

    @Test
    fun `the longest prefix wins`() {
        val matcher =
            KnownOriginatorMatcher(
                listOf(
                    KnownOriginator("+1555", KnownOriginator.Kind.PREFIX, "short"),
                    KnownOriginator("+15559012", KnownOriginator.Kind.PREFIX, "long"),
                ),
            )
        assertEquals("long", matcher.find("+15559012345678")?.label)
        assertEquals("short", matcher.find("+15559999999999")?.label)
    }

    @Test
    fun `empty entries are ignored`() {
        val matcher = KnownOriginatorMatcher(listOf(KnownOriginator("   ", KnownOriginator.Kind.EXACT)))
        assertTrue(matcher.isEmpty)
        assertEquals(0, matcher.size)
    }

    @Test
    fun `mutes cover a sender and a class until they expire`() {
        val now = 1_000_000L
        val matcher =
            MuteMatcher(
                listOf(
                    Mute(Mute.Scope.ORIGINATOR, "+1555 98765 43210", now + 60_000L),
                    Mute(Mute.Scope.CLASS, ClassId.SILENT.wire(), now + 60_000L),
                    Mute(Mute.Scope.ORIGINATOR, "+15558800000000", now - 1),
                ),
                now,
            )
        assertTrue(matcher.isMuted(ClassId.WAP_CP, "+15559876543210"))
        assertTrue(matcher.isMuted(ClassId.SILENT, "+15558800000000"))
        // The expired entry is gone, and the class mute covers it anyway; check a different class.
        assertFalse(matcher.isMuted(ClassId.WAP_CP, "+15558800000000"))
        // Outbound messages are not muted by an originator mute.
        assertFalse(matcher.isMuted(ClassId.MO_DATA, "+15559876543210", Direction.OUT))
    }

    @Test
    fun `a mute expires at the check time, not at the build time`() {
        val now = 1_000_000L
        val matcher = MuteMatcher(listOf(Mute(Mute.Scope.CLASS, ClassId.SILENT.wire(), now + 60_000L)), now)
        assertTrue(matcher.isMuted(ClassId.SILENT, null))
        assertFalse(matcher.isMuted(ClassId.SILENT, null, atWallMs = now + 60_000L))
    }

    @Test
    fun `an empty mute list mutes nothing`() {
        val matcher = MuteMatcher(emptyList(), 0)
        assertTrue(matcher.isEmpty)
        assertFalse(matcher.isMuted(ClassId.SILENT, "+15559876543210"))
    }
}
