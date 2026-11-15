package dev.nightjar.app.feature.messages

import dev.nightjar.app.core.security.NumberDisplay
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
import java.time.LocalDate
import java.time.ZoneOffset

class MessagesLogicTest {
    private val day = 1_793_836_800_000L // 2026-11-05T00:00Z
    private val noSender = "No sender address"

    private fun row(
        id: Long,
        at: Long,
        classId: ClassId = ClassId.SILENT,
        reviewed: Boolean = false,
        severity: Severity = Severity.SUSPICIOUS,
        have: Int = 1,
        total: Int = 1,
        slot: Int = 0,
    ) = MessageListItem(
        id = id,
        eventId = "e$id",
        receivedAtWall = at,
        classId = classId,
        severity = severity,
        title = "t",
        originator = "+919000000210",
        textPreview = "",
        fate = Fate.SYSTEM,
        verdict = Verdict.ALLOW,
        isQuarantined = false,
        isReviewed = reviewed,
        isStarred = false,
        direction = Direction.IN,
        subId = 1,
        slot = slot,
        partsHave = have,
        partsTotal = total,
        consumerPackage = null,
        consumerPackageLabel = null,
        isReplay = false,
    )

    @Test
    fun `a header appears only on a day change`() {
        val first = row(1, day + 1000)
        val same = row(2, day + 2000)
        val next = row(3, day + 24 * 3600 * 1000)
        assertEquals(LocalDate.parse("2026-11-05"), dayHeader(null, first, ZoneOffset.UTC))
        assertNull(dayHeader(first, same, ZoneOffset.UTC))
        assertEquals(LocalDate.parse("2026-11-06"), dayHeader(same, next, ZoneOffset.UTC))
    }

    @Test
    fun `multipart label only until complete`() {
        assertTrue(needsPartsLabel(row(1, day, have = 2, total = 3)))
        assertFalse(needsPartsLabel(row(1, day, have = 3, total = 3)))
        assertFalse(needsPartsLabel(row(1, day)))
    }

    @Test
    fun `swipe support follows the class profile`() {
        assertTrue(supportsRuleSwipe(row(1, day, classId = ClassId.WAP_CP)))
        assertTrue(supportsMuteSwipe(row(1, day, classId = ClassId.SILENT)))
        assertFalse(supportsRuleSwipe(row(1, day, classId = ClassId.MO_VVM)))
    }

    @Test
    fun `active filter count ignores the chip and counts advanced conditions`() {
        assertEquals(0, activeFilterCount(FilterSpec()))
        // Reports are shown by default, so only hiding them counts as a filter.
        assertEquals(1, activeFilterCount(FilterSpec(showDeliveryReports = false)))
        assertEquals(0, activeFilterCount(FilterSpec(chip = dev.nightjar.app.data.model.FeedChip.SUSPICIOUS)))
        assertEquals(
            3,
            activeFilterCount(
                FilterSpec(
                    classIds = setOf(ClassId.SILENT),
                    severities = setOf(Severity.SUSPICIOUS),
                    unreviewedOnly = true,
                ),
            ),
        )
    }

    @Test
    fun `highlight finds the first case-insensitive match`() {
        assertEquals(2 until 5, highlightRange("123456", "345"))
        assertEquals(0 until 3, highlightRange("Abcdef", "ABC"))
        assertNull(highlightRange("abc", "zzz"))
        assertNull(highlightRange("abc", " "))
    }

    @Test
    fun `filter sheet offers feed visible classes only`() {
        val classes = filterableClasses()
        assertTrue(ClassId.SILENT in classes)
        assertFalse(ClassId.NORMAL in classes)
        assertFalse(ClassId.MO_VISIBLE in classes)
    }

    @Test
    fun `sender line masks only when the setting asks for it`() {
        val message = row(1, day, slot = 1)
        assertEquals("+919000000210", senderLine(message, NumberDisplay.PLAIN, noSender))
        val masked = senderLine(message, NumberDisplay.forSetting(true), noSender)
        assertFalse(masked.contains("9000000210"), "raw number leaked: $masked")
        assertTrue(masked.startsWith("+91"), "mask keeps the country prefix: $masked")
    }

    @Test
    fun `a row without a sender says so instead of leaving a blank`() {
        val anonymous = row(1, day).copy(originator = null)
        assertEquals(noSender, senderLine(anonymous, NumberDisplay.PLAIN, noSender))
    }

    @Test
    fun `the footer names the operator and slot and leaves out what is unknown`() {
        val strings = MapStrings()
        assertEquals("Jio · SIM 1", simLine(row(1, day, slot = 0).copy(carrierName = "Jio"), strings))
        assertEquals("SIM 2", simLine(row(1, day, slot = 1), strings))
        assertEquals("Airtel", simLine(row(1, day, slot = -1).copy(carrierName = "Airtel"), strings))
        assertEquals("", simLine(row(1, day, slot = -1), strings))
        assertEquals("SIM 1", simLine(row(1, day, slot = 0).copy(carrierName = "unknown"), strings))
    }

    @Test
    fun `old rows are reworded at draw time and repeats get a count`() {
        val strings = MapStrings()
        val push = row(1, day, classId = ClassId.WAP_OTHER).copy(title = "Push message (unknown)")
        assertEquals("Malformed WAP push: content type is unreadable", rowTitle(push, strings))
        val data = row(2, day, classId = ClassId.DATA_PORT).copy(title = "Hidden data SMS (port 2948)")
        assertEquals("Hidden data SMS (port 2948)", rowTitle(data, strings))
        assertEquals("Hidden data SMS (port 2948) ×3", rowTitle(data.copy(repeatCount = 3), strings))
        val readable = row(3, day, classId = ClassId.WAP_OTHER).copy(title = "Push message (text/x-vcard)")
        assertEquals("Push message (text/x-vcard)", rowTitle(readable, strings))
    }

    @Test
    fun `a flagged row carries a reason and a normal row does not`() {
        val strings = MapStrings()
        val push = row(1, day, classId = ClassId.WAP_OTHER).copy(title = "Push message (unknown)")
        assertEquals(strings.get(TextKey.REASON_MALFORMED), rowReason(push, strings))
        assertNull(rowReason(row(2, day, classId = ClassId.NORMAL, severity = Severity.INFO), strings))
        assertNull(rowReason(row(3, day).copy(direction = Direction.OUT), strings))
    }

    @Test
    fun `a smart message row names what the message contains`() {
        // "This message contains a ringtone. Most phones do not receive this type."
        val strings = MapStrings()
        val ringtone = row(1, day, classId = ClassId.SMART, severity = Severity.NOTABLE).copy(title = "Ringtone")
        assertEquals(strings.get(TextKey.REASON_SMART_RINGTONE), rowReason(ringtone, strings))
        assertEquals("This message contains a ringtone. Most phones do not receive this type.", rowReason(ringtone, strings))
        val other = ringtone.copy(title = "Smart message")
        assertEquals(strings.get(TextKey.REASON_RARE_SMART), rowReason(other, strings))
        val empty = row(2, day, classId = ClassId.EMPTY, severity = Severity.NOTABLE)
        assertEquals(strings.get(TextKey.REASON_RARE_EMPTY), rowReason(empty, strings))
    }

    @Test
    fun `a sent row shows To and the number only when the number is known`() {
        // "To No sender address" never shows. The destination is synthetic.
        val sent = row(1, day, classId = ClassId.MO_DATA).copy(direction = Direction.OUT, originator = null, destination = "+15550100000")
        assertEquals("+15550100000", sentToNumber(sent, NumberDisplay.PLAIN))
        val masked = sentToNumber(sent, NumberDisplay.forSetting(true))
        assertFalse(masked.orEmpty().contains("5550100000"), "raw number leaked: $masked")
        assertNull(sentToNumber(sent.copy(destination = null), NumberDisplay.PLAIN))
        assertNull(sentToNumber(sent.copy(destination = " "), NumberDisplay.PLAIN))
        assertEquals("To x · Jio · SIM 2", sentLine("To x", "Jio · SIM 2"))
        assertEquals("Jio · SIM 2", sentLine(null, "Jio · SIM 2"))
        assertEquals("To x", sentLine("To x", ""))
        assertEquals("", sentLine(null, ""))
    }

    @Test
    fun `a sent data message names the app that sent it`() {
        val sent = row(1, day, classId = ClassId.MO_DATA).copy(direction = Direction.OUT)
        assertTrue(isSentDataMessage(sent))
        assertFalse(isSentDataMessage(sent.copy(classId = ClassId.MO_VISIBLE)))
        assertFalse(isSentDataMessage(row(2, day, classId = ClassId.DATA_PORT)))
        val labelled = sent.copy(callingPackage = "com.example.sender", callingPackageLabel = "Example Sender")
        assertEquals("Example Sender", sentDataApp(labelled))
        assertEquals("com.example.sender", sentDataApp(labelled.copy(callingPackageLabel = null)))
        assertNull(sentDataApp(sent))
    }

    @Test
    fun `an export of a selection carries the ids and the active filter`() {
        val spec = FilterSpec(chip = dev.nightjar.app.data.model.FeedChip.SUSPICIOUS)
        val request = exportRequestFor(listOf(3L, 7L), spec)
        assertEquals(listOf(3L, 7L), request.selection)
        assertEquals(spec, request.filter)
    }

    @Test
    fun `an export with a default filter leaves the filter slot empty`() {
        val request = exportRequestFor(emptyList(), FilterSpec.DEFAULT)
        assertTrue(request.selection.isEmpty())
        assertNull(request.filter, "a default spec must preselect Everything, not a disabled Filter scope")
    }

    @Test
    fun `the spec echo does not overwrite what is being typed`() {
        assertEquals(
            "two words ",
            queryAfterSpecChange(current = "two words ", echo = "two", writtenHere = true),
        )
        assertEquals(
            "saved",
            queryAfterSpecChange(current = "typing", echo = "saved", writtenHere = false),
        )
    }
}
