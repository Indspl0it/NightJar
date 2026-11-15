package dev.nightjar.app.data.model

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every chip and advanced field maps to a DAO argument, and "no filter" maps to the neutral argument.
 * These are the cases the feed query's `:param IS NULL OR …` guards depend on.
 */
class FilterSpecTest {
    @Test
    fun `default spec sets no filter arguments`() {
        val a = FilterSpec.DEFAULT.toArgs()
        assertTrue(a.includeStatus)
        assertEquals(0, a.classCount)
        assertEquals(listOf(""), a.classIds)
        assertEquals(0, a.severityCount)
        assertEquals(listOf(""), a.severities)
        assertFalse(a.quarantinedOnly)
        assertFalse(a.unreviewedOnly)
        assertFalse(a.starredOnly)
        assertNull(a.direction)
        assertNull(a.fromWall)
        assertNull(a.toWall)
        assertNull(a.subId)
        assertNull(a.slot)
        assertNull(a.searchLike)
        assertNull(a.originatorLike)
        assertNull(a.port)
    }

    @Test
    fun `default spec is recognised as default`() {
        assertTrue(FilterSpec.DEFAULT.isDefault)
        assertFalse(FilterSpec.DEFAULT.copy(unreviewedOnly = true).isDefault)
    }

    @Test
    fun `suspicious chip filters by severity`() {
        val a = FilterSpec(chip = FeedChip.SUSPICIOUS).toArgs()
        assertEquals(2, a.severityCount)
        assertEquals(listOf(Severity.SUSPICIOUS.wire(), Severity.HIGH.wire()), a.severities)
        assertEquals(0, a.classCount)
    }

    @Test
    fun `quarantined chip filters by quarantine state`() {
        val a = FilterSpec(chip = FeedChip.QUARANTINED).toArgs()
        assertTrue(a.quarantinedOnly)
    }

    @Test
    fun `outgoing chip filters by direction`() {
        val a = FilterSpec(chip = FeedChip.OUTGOING).toArgs()
        assertEquals(Direction.OUT.wire(), a.direction)
    }

    @Test
    fun `broadcasts chip filters to the CB classes`() {
        val a = FilterSpec(chip = FeedChip.BROADCASTS).toArgs()
        assertEquals(2, a.classCount)
        assertEquals(listOf(ClassId.CB.wire(), ClassId.CB_SIM.wire()), a.classIds)
    }

    @Test
    fun `SIM chips filter by slot`() {
        assertEquals(0, FilterSpec(chip = FeedChip.SIM1).toArgs().slot)
        assertEquals(1, FilterSpec(chip = FeedChip.SIM2).toArgs().slot)
    }

    @Test
    fun `chip and advanced fields are combined`() {
        val a =
            FilterSpec(
                chip = FeedChip.SUSPICIOUS,
                classIds = setOf(ClassId.WAP_CP),
                unreviewedOnly = true,
                starredOnly = true,
                fromWall = 100,
                toWall = 200,
                subId = 2,
            ).toArgs()
        assertEquals(1, a.classCount)
        assertEquals(listOf(ClassId.WAP_CP.wire()), a.classIds)
        // The Suspicious chip means "SUSPICIOUS and up", so HIGH is in the filter too (VR01-A05).
        assertEquals(2, a.severityCount)
        assertEquals(listOf(Severity.SUSPICIOUS.wire(), Severity.HIGH.wire()), a.severities)
        assertTrue(a.unreviewedOnly)
        assertTrue(a.starredOnly)
        assertEquals(100L, a.fromWall)
        assertEquals(200L, a.toWall)
        assertEquals(2, a.subId)
    }

    @Test
    fun `search normalises the originator side`() {
        // " +91 98765 " must match a stored "9198765".
        val a = FilterSpec(search = "  +91 98765 ").toArgs()
        assertEquals("%9198765%", a.searchNormLike)
        // The raw side keeps the text as typed, so a preview or content-type match still works.
        assertEquals("%+91 98765%", a.searchLike)
    }

    @Test
    fun `numeric search also matches the destination port`() {
        val a = FilterSpec(search = "2948").toArgs()
        assertEquals(2948, a.searchPort)
        assertNull(FilterSpec(search = "wapproxy").toArgs().searchPort)
    }

    @Test
    fun `originator query is normalised`() {
        val a = FilterSpec(originatorQuery = "00 91 98765").toArgs()
        assertEquals("%9198765%", a.originatorLike)
    }

    @Test
    fun `blank search fields are dropped`() {
        val a = FilterSpec(search = "   ", originatorQuery = "", packageQuery = " ").toArgs()
        assertNull(a.searchLike)
        assertNull(a.originatorLike)
        assertNull(a.packageLike)
    }

    @Test
    fun `delivery reports flag is carried`() {
        assertTrue(FilterSpec.DEFAULT.toArgs().includeStatus)
        assertFalse(FilterSpec(showDeliveryReports = false).toArgs().includeStatus)
    }

    @Test
    fun `spec round-trips through its saved-view json`() {
        val spec =
            FilterSpec(
                chip = FeedChip.BROADCASTS,
                classIds = setOf(ClassId.CB, ClassId.WAP_CP),
                severities = setOf(Severity.NOTABLE),
                direction = Direction.IN,
                fromWall = 10,
                toWall = 20,
                subId = 1,
                unreviewedOnly = true,
                starredOnly = true,
                search = "2948",
                originatorQuery = "+9190",
                port = 2948,
                contentType = "application/vnd.wap",
                packageQuery = "Messages",
                showDeliveryReports = false,
            )
        val restored = FilterSpec.fromJson(spec.toJson())
        assertEquals(spec, restored)
    }

    @Test
    fun `fate and attention filters map to DAO arguments`() {
        val none = FilterSpec.DEFAULT.toArgs()
        assertEquals(0, none.fateCount)
        assertEquals(listOf(""), none.fates)
        assertFalse(none.attentionOnly)
        val a = FilterSpec(fates = setOf(Fate.BLOCKED), attentionOnly = true).toArgs()
        assertEquals(1, a.fateCount)
        assertEquals(listOf(Fate.BLOCKED.wire()), a.fates)
        assertTrue(a.attentionOnly)
        assertEquals(listOf(Severity.SUSPICIOUS.wire(), Severity.HIGH.wire()), a.attentionSeverity)
        assertFalse(FilterSpec(fates = setOf(Fate.BLOCKED)).isDefault)
        assertFalse(FilterSpec(attentionOnly = true).isDefault)
    }

    @Test
    fun `fate and attention round-trip, and old json without them reads as no restriction`() {
        val spec = FilterSpec(fates = setOf(Fate.BLOCKED), attentionOnly = true)
        assertEquals(spec, FilterSpec.fromJson(spec.toJson()))
        val old = """{"chip":"SUSPICIOUS","unreviewedOnly":true,"showDeliveryReports":false}"""
        val restored = FilterSpec.fromJson(old)
        assertEquals(FeedChip.SUSPICIOUS, restored.chip)
        assertTrue(restored.fates.isEmpty())
        assertFalse(restored.attentionOnly)
    }

    @Test
    fun `range preset round-trips, and old json without it reads as null`() {
        val spec = FilterSpec(fromWall = 1_000L, toWall = 2_000L, rangePreset = "LAST_7_DAYS")
        assertEquals(spec, FilterSpec.fromJson(spec.toJson()))
        val old = """{"chip":"ALL","fromWall":1000,"toWall":2000}"""
        assertNull(FilterSpec.fromJson(old).rangePreset)
        // A preset alone narrows nothing.
        assertTrue(FilterSpec(rangePreset = "TODAY").isDefault)
    }

    @Test
    fun `saved-view json ignores unknown fields and tolerates junk`() {
        assertEquals(FilterSpec.DEFAULT, FilterSpec.fromJson(null))
        assertEquals(FilterSpec.DEFAULT, FilterSpec.fromJson(""))
        val withUnknown = """{"chip":"ALL","futureField":42}"""
        assertEquals(FilterSpec.DEFAULT, FilterSpec.fromJson(withUnknown))
        // Malformed JSON and a JSON value of the wrong shape read as the default filter.
        assertEquals(FilterSpec.DEFAULT, FilterSpec.fromJson("{bad"))
        assertEquals(FilterSpec.DEFAULT, FilterSpec.fromJson("[]"))
    }

    @Test
    fun `chip wire names are stable`() {
        FeedChip.entries.forEach { chip -> assertEquals(chip, FeedChip.from(chip.wire)) }
        assertEquals(FeedChip.ALL, FeedChip.from("nonsense"))
    }
}
