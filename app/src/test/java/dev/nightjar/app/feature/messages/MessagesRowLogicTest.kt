package dev.nightjar.app.feature.messages

import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.domain.text.MapStrings
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The held section, the status line, the reviewed state, the list title and the count line. */
class MessagesRowLogicTest {
    private val strings = MapStrings()

    private fun row(
        classId: ClassId = ClassId.SILENT,
        title: String = "Silent SMS (Type 0)",
        severity: Severity = Severity.SUSPICIOUS,
        fate: Fate = Fate.SYSTEM,
        quarantined: Boolean = false,
        reviewed: Boolean = false,
        direction: Direction = Direction.IN,
        debug: Boolean = false,
    ) = MessageListItem(
        id = 1,
        eventId = "e1",
        receivedAtWall = 1_793_836_800_000L,
        classId = classId,
        severity = severity,
        title = title,
        originator = "+15550100000",
        textPreview = "",
        fate = fate,
        verdict = Verdict.ALLOW,
        isQuarantined = quarantined,
        isReviewed = reviewed,
        isStarred = false,
        direction = direction,
        subId = 1,
        slot = 1,
        carrierName = "Example Net",
        partsHave = 1,
        partsTotal = 1,
        consumerPackage = null,
        consumerPackageLabel = null,
        isReplay = false,
        debugInjected = debug,
    )

    @Test
    fun `a held row is quarantined and not blocked`() {
        assertTrue(isHeld(row(quarantined = true, fate = Fate.QUARANTINED)))
        assertFalse(isHeld(row(quarantined = true, fate = Fate.BLOCKED)))
        assertFalse(isHeld(row()))
    }

    @Test
    fun `waiting for you shows only in the unfiltered view with held messages`() {
        assertTrue(showsWaitingSection(FilterSpec(), heldCount = 2))
        assertFalse(showsWaitingSection(FilterSpec(), heldCount = 0))
        assertFalse(showsWaitingSection(FilterSpec(chip = FeedChip.SIM2), heldCount = 2))
        // Hiding delivery reports is not a narrowing filter for the section.
        assertTrue(showsWaitingSection(FilterSpec(showDeliveryReports = false), heldCount = 1))
    }

    @Test
    fun `status line parts come in a fixed order`() {
        val status = rowStatus(row(severity = Severity.HIGH, fate = Fate.BLOCKED, debug = true))
        assertEquals(RowStatus(RowFate.BLOCKED, Severity.HIGH, test = true), status)
        val held = rowStatus(row(quarantined = true, fate = Fate.QUARANTINED))
        assertEquals(RowStatus(RowFate.ON_HOLD, Severity.SUSPICIOUS, test = false), held)
    }

    @Test
    fun `a normal row has no severity word`() {
        val status = rowStatus(row(severity = Severity.INFO, fate = Fate.SHOWN))
        assertEquals(RowFate.DELIVERED, status.fate)
        assertNull(status.severity)
        assertFalse(status.test)
    }

    @Test
    fun `fate words`() {
        assertEquals(RowFate.SENT, rowFate(row(direction = Direction.OUT, fate = Fate.SENT)))
        assertEquals(RowFate.NOT_SENT, rowFate(row(direction = Direction.OUT, fate = Fate.SEND_FAILED)))
        assertEquals(RowFate.NOT_DELIVERED, rowFate(row(fate = Fate.NO_RECEIVER)))
        assertEquals(RowFate.RELEASED, rowFate(row(fate = Fate.RELEASED)))
        assertEquals(RowFate.WAITING, rowFate(row(fate = Fate.INCOMPLETE)))
        assertEquals(RowFate.BLOCKED, rowFate(row(fate = Fate.BLOCKLIST)))
        assertNull(rowFate(row(fate = Fate.UNKNOWN)))
        assertNull(rowFate(row(classId = ClassId.STATUS, fate = Fate.SYSTEM)))
        assertTrue(rowStatus(row(severity = Severity.INFO, fate = Fate.UNKNOWN)).isEmpty)
    }

    @Test
    fun `every row has a review state, a blocked row too`() {
        assertTrue(needsReview(row(fate = Fate.BLOCKED)))
        assertFalse(needsReview(row(fate = Fate.BLOCKED, reviewed = true)))
        assertTrue(needsReview(row(severity = Severity.INFO)))
        assertFalse(needsReview(row(direction = Direction.OUT, fate = Fate.SENT)))
        // A delivery report shows as reviewed from the start.
        assertFalse(needsReview(row(classId = ClassId.STATUS)))
    }

    @Test
    fun `only a high row has a red icon`() {
        assertTrue(isHighRisk(row(severity = Severity.HIGH)))
        assertFalse(isHighRisk(row(severity = Severity.SUSPICIOUS)))
    }

    @Test
    fun `the list title is short and keeps the repeat count`() {
        assertEquals("Silent SMS", listRowTitle(row(), strings))
        assertEquals("Silent SMS ×3", listRowTitle(row().copy(repeatCount = 3), strings))
        // Home keeps the full title.
        assertEquals("Silent SMS (Type 0)", rowTitle(row(), strings))
    }

    @Test
    fun `the description of a row`() {
        val malformed = row(classId = ClassId.MALFORMED_SMS, title = "Malformed SMS: unknown message type", severity = Severity.HIGH)
        assertEquals("Unknown message type. A damaged header like this can crash a phone.", rowDescription(malformed, strings))
        assertEquals(strings.get(TextKey.REASON_SILENT), rowDescription(row(), strings))
        assertNull(rowDescription(row(classId = ClassId.NORMAL, title = "Normal message", severity = Severity.HIGH), strings))
    }

    @Test
    fun `line 2 names the SIM slot without the network`() {
        assertEquals("SIM 2", simSlotLine(row(), strings))
        assertEquals("Example Net · SIM 2", simLine(row(), strings))
    }

    @Test
    fun `count line shows the filters and Clear only when filters are on`() {
        assertNull(countLine(null, FilterSpec()))
        val plain = countLine(10, FilterSpec())!!
        assertEquals(CountLine(10, 0), plain)
        assertFalse(plain.canClear)
        val filtered = countLine(4, FilterSpec(starredOnly = true, search = "ab"))!!
        assertEquals(2, filtered.filters)
        assertTrue(filtered.canClear)
    }

    @Test
    fun `positions in a card`() {
        assertEquals(GroupPosition.ONLY, positionInGroup(0, 1))
        assertEquals(GroupPosition.FIRST, positionInGroup(0, 3))
        assertEquals(GroupPosition.MIDDLE, positionInGroup(1, 3))
        assertEquals(GroupPosition.LAST, positionInGroup(2, 3))
    }
}
