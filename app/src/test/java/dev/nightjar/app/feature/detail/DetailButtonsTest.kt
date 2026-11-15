package dev.nightjar.app.feature.detail

import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which action buttons the detail screen shows, and the values of the Evidence card. */
class DetailButtonsTest {
    @Test
    fun `an on-hold message offers Release, Block similar and Delete`() {
        val buttons =
            detailButtonsFor(isQuarantined = true, releasable = true, direction = Direction.IN, fate = Fate.QUARANTINED)
        assertEquals(listOf(DetailButton.RELEASE, DetailButton.BLOCK_SIMILAR, DetailButton.DELETE), buttons)
    }

    @Test
    fun `a blocked message has no Release`() {
        val buttons =
            detailButtonsFor(isQuarantined = false, releasable = true, direction = Direction.IN, fate = Fate.BLOCKED)
        assertEquals(listOf(DetailButton.DELETE), buttons)
    }

    @Test
    fun `an on-hold message of a type that cannot be released has no Release`() {
        val buttons =
            detailButtonsFor(isQuarantined = true, releasable = false, direction = Direction.IN, fate = Fate.QUARANTINED)
        assertEquals(listOf(DetailButton.BLOCK_SIMILAR, DetailButton.DELETE), buttons)
    }

    @Test
    fun `a message of a type that no rule can block has no Block similar`() {
        // For example a voicemail control message.
        val buttons =
            detailButtonsFor(
                isQuarantined = false,
                releasable = false,
                direction = Direction.IN,
                fate = Fate.SYSTEM,
                blockable = false,
            )
        assertEquals(listOf(DetailButton.DELETE), buttons)
    }

    @Test
    fun `a sent message has Delete only`() {
        val buttons =
            detailButtonsFor(isQuarantined = false, releasable = false, direction = Direction.OUT, fate = Fate.SENT)
        assertEquals(listOf(DetailButton.DELETE), buttons)
    }

    @Test
    fun `outcome tone follows hold and block`() {
        assertEquals(TimelineTone.HOLD, outcomeTone(Verdict.QUARANTINE, isQuarantined = true, fate = Fate.QUARANTINED))
        assertEquals(TimelineTone.BLOCK, outcomeTone(Verdict.BLOCK, isQuarantined = false, fate = Fate.BLOCKED))
        assertEquals(TimelineTone.INFO, outcomeTone(Verdict.ALLOW, isQuarantined = false, fate = Fate.SHOWN))
    }

    @Test
    fun `report tone follows the delivery status`() {
        assertEquals(TimelineTone.OK, reportTone(STATUS_DELIVERED))
        assertEquals(TimelineTone.FAIL, reportTone(STATUS_FAILED))
        assertEquals(TimelineTone.INFO, reportTone(STATUS_PENDING))
        assertEquals(TimelineTone.INFO, reportTone(null))
    }

    @Test
    fun `evidence of an empty tree has no values and no errors`() {
        val evidence = evidenceOf(emptyList())
        assertNull(evidence.pid)
        assertNull(evidence.dcs)
        assertNull(evidence.udl)
        assertTrue(evidence.formatErrors.isEmpty())
    }

    @Test
    fun `evidence ignores the boolean flags that share a field name`() {
        val nodes =
            listOf(
                DecodedNode("pid", "true", 0, 1, 1, null),
                DecodedNode("udl", "false", 0, 1, 1, null),
                DecodedNode("pid", "7F", 5, 1, 1, null),
                DecodedNode("meaning", "Test meaning", 5, 1, 2, null),
                DecodedNode("user_data", null, 6, 2, 1, "short"),
            )
        val evidence = evidenceOf(nodes)
        assertEquals("0x7F · Test meaning", evidence.pid)
        assertNull(evidence.udl)
        assertEquals(listOf(FormatError("User data", "short")), evidence.formatErrors)
    }
}
