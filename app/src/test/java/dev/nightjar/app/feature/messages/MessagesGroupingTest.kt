package dev.nightjar.app.feature.messages

import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.MessageListItem
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
import java.time.LocalDate
import java.time.ZoneOffset

/** Delivery reports under their sent message, day headers, group positions and the entry filter from Home. */
class MessagesGroupingTest {
    private val zone = ZoneOffset.UTC
    private val day = 1_793_836_800_000L // 2026-11-05T00:00Z
    private val hour = 3_600_000L

    private fun row(
        id: Long,
        at: Long,
        classId: ClassId = ClassId.SILENT,
        direction: Direction = Direction.IN,
        deliveryStatus: String? = null,
        relatedMessageId: Long? = null,
    ) = MessageListItem(
        id = id,
        eventId = "e$id",
        receivedAtWall = at,
        classId = classId,
        severity = Severity.INFO,
        title = "t",
        originator = "+15550100000",
        textPreview = "",
        fate = Fate.SYSTEM,
        verdict = Verdict.ALLOW,
        isQuarantined = false,
        isReviewed = false,
        isStarred = false,
        direction = direction,
        subId = 1,
        slot = 0,
        partsHave = 1,
        partsTotal = 1,
        consumerPackage = null,
        consumerPackageLabel = null,
        isReplay = false,
        deliveryStatus = deliveryStatus,
        relatedMessageId = relatedMessageId,
    )

    private fun sent(
        id: Long,
        at: Long,
    ) = row(id, at, ClassId.MO_VISIBLE, Direction.OUT)

    private fun report(
        id: Long,
        at: Long,
        relatedTo: Long?,
        status: String = "DELIVERED",
    ) = row(id, at, ClassId.STATUS, deliveryStatus = status, relatedMessageId = relatedTo)

    private fun messages(entries: List<FeedEntry>) = entries.filterIsInstance<FeedEntry.Message>()

    @Test
    fun `a linked report is grouped under its sent message`() {
        // The feed is newest first, so the report comes before the message it reports on.
        val rows = listOf(report(id = 2, at = day + 2 * hour, relatedTo = 1), sent(id = 1, at = day + hour))
        val entries = buildFeedEntries(rows, zone)
        val shown = messages(entries)
        assertEquals(1, shown.size)
        assertEquals(1L, shown.single().row.id)
        assertEquals(listOf(2L), shown.single().reports.map { it.id })
        assertEquals(ReportStatus.DELIVERED, reportStatus(shown.single().reports.single()))
    }

    @Test
    fun `a report with no link stays its own row`() {
        val rows = listOf(report(id = 2, at = day + 2 * hour, relatedTo = null, status = "FAILED"), sent(id = 1, at = day + hour))
        val shown = messages(buildFeedEntries(rows, zone))
        assertEquals(listOf(2L, 1L), shown.map { it.row.id })
        assertTrue(shown.all { it.reports.isEmpty() })
        assertEquals(ReportStatus.FAILED, reportStatus(shown.first().row))
    }

    @Test
    fun `a report whose sent message is not loaded stays its own row`() {
        val rows = listOf(report(id = 2, at = day + 2 * hour, relatedTo = 99))
        val shown = messages(buildFeedEntries(rows, zone))
        assertEquals(listOf(2L), shown.map { it.row.id })
    }

    @Test
    fun `several reports of one message are sorted oldest first`() {
        val rows =
            listOf(
                report(id = 3, at = day + 3 * hour, relatedTo = 1, status = "DELIVERED"),
                report(id = 2, at = day + 2 * hour, relatedTo = 1, status = "PENDING"),
                sent(id = 1, at = day + hour),
            )
        val shown = messages(buildFeedEntries(rows, zone)).single()
        assertEquals(listOf(2L, 3L), shown.reports.map { it.id })
    }

    @Test
    fun `source index points to the row in the loaded list`() {
        val rows = listOf(report(id = 2, at = day + 2 * hour, relatedTo = 1), sent(id = 1, at = day + hour), row(0, day))
        val shown = messages(buildFeedEntries(rows, zone))
        assertEquals(listOf(1, 2), shown.map { it.sourceIndex })
    }

    @Test
    fun `a day header goes before the first entry of each day`() {
        val rows = listOf(row(3, day + 24 * hour + hour), row(2, day + 2 * hour), row(1, day + hour))
        val entries = buildFeedEntries(rows, zone)
        assertEquals(
            listOf("day-2026-11-06", "msg-3", "day-2026-11-05", "msg-2", "msg-1"),
            entries.map { it.key },
        )
        assertEquals(LocalDate.parse("2026-11-06"), (entries.first() as FeedEntry.Day).day)
    }

    @Test
    fun `the day of a message with reports is the day of the message`() {
        val nextDay = day + 24 * hour + hour
        val rows = listOf(report(id = 2, at = nextDay, relatedTo = 1), sent(id = 1, at = day + 23 * hour))
        val entries = buildFeedEntries(rows, zone)
        assertEquals(listOf("day-2026-11-05", "msg-1"), entries.map { it.key })
    }

    @Test
    fun `group positions follow the day groups`() {
        val rows = listOf(row(4, day + 25 * hour), row(3, day + 3 * hour), row(2, day + 2 * hour), row(1, day + hour))
        val entries = buildFeedEntries(rows, zone)
        // day, 4, day, 3, 2, 1
        assertEquals(GroupPosition.ONLY, groupPosition(entries, 1))
        assertEquals(GroupPosition.FIRST, groupPosition(entries, 3))
        assertEquals(GroupPosition.MIDDLE, groupPosition(entries, 4))
        assertEquals(GroupPosition.LAST, groupPosition(entries, 5))
    }

    @Test
    fun `an empty list gives no entries`() {
        assertTrue(buildFeedEntries(emptyList(), zone).isEmpty())
    }

    @Test
    fun `report status reads the wire value`() {
        assertEquals(ReportStatus.PENDING, reportStatus(report(1, day, null, "PENDING")))
        assertNull(reportStatus(row(1, day)))
        assertTrue(isDeliveryReport(report(1, day, null)))
        assertFalse(isDeliveryReport(sent(1, day)))
    }

    @Test
    fun `an entry filter for a tile sets the chip and drops delivery reports`() {
        val suspicious = entryFilter(EntryFamily.SUSPICIOUS, null, zone)
        assertEquals(FeedChip.SUSPICIOUS, suspicious.chip)
        assertFalse(suspicious.showDeliveryReports)
        assertTrue(suspicious.fates.isEmpty())
        assertEquals(FeedChip.QUARANTINED, entryFilter(EntryFamily.ON_HOLD, null, zone).chip)
        assertEquals(setOf(Fate.BLOCKED), entryFilter(EntryFamily.BLOCKED, null, zone).fates)
        assertEquals(FeedChip.ALL, entryFilter(EntryFamily.HIDDEN, null, zone).chip)
    }

    @Test
    fun `an entry filter for a day covers the whole local day`() {
        val epochDay = LocalDate.parse("2026-11-05").toEpochDay()
        val spec = entryFilter(null, epochDay, zone)
        assertEquals(day, spec.fromWall)
        assertEquals(day + 24 * hour, spec.toWall)
    }

    @Test
    fun `the route argument reads a family, a day or both`() {
        val epochDay = LocalDate.parse("2026-11-05").toEpochDay()
        assertEquals(setOf(Fate.BLOCKED), parseEntryArgument("family:blocked", zone)!!.fates)
        assertEquals(day, parseEntryArgument("day:$epochDay", zone)!!.fromWall)
        val both = parseEntryArgument("family:suspicious,day:$epochDay", zone)!!
        assertEquals(FeedChip.SUSPICIOUS, both.chip)
        assertEquals(day, both.fromWall)
    }

    @Test
    fun `a chip name or junk is not an entry argument`() {
        assertNull(parseEntryArgument("quarantined", zone))
        assertNull(parseEntryArgument("family:unknown,day:x", zone))
        assertNull(parseEntryArgument(null, zone))
        assertNull(parseEntryArgument("", zone))
    }

    @Test
    fun `a skipped row has no entry and the others keep their index`() {
        val rows = listOf(row(1, day + 3 * hour), row(2, day + 2 * hour), row(3, day + hour))
        val entries = messages(buildFeedEntries(rows, zone, skip = { it.id == 2L }))
        assertEquals(listOf(1L, 3L), entries.map { it.row.id })
        assertEquals(listOf(0, 2), entries.map { it.sourceIndex })
    }
}
