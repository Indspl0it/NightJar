package dev.nightjar.app.domain.model

import dev.nightjar.app.data.db.StringListColumn
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Subtype
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The extra subtype entry format and the list column that stores the entries. */
class ExtraSubtypeTest {
    @Test
    fun `a bare subtype belongs to the event class`() {
        val match = ExtraSubtype.parse(Subtype.SimOta.NOSEC, ClassId.SIM_DD)
        assertEquals(ClassId.SIM_DD, match.classId)
        assertEquals(Subtype.SimOta.NOSEC, match.subtype)
    }

    @Test
    fun `class and subtype name another class`() {
        val match = ExtraSubtype.parse("${ClassId.DATA_PORT.wire()}:UNKNOWN", ClassId.SILENT)
        assertEquals(ClassId.DATA_PORT, match.classId)
        assertEquals("UNKNOWN", match.subtype)
    }

    @Test
    fun `a bare class wire is a rule without a subtype`() {
        val match = ExtraSubtype.parse(ClassId.BINARY.wire(), ClassId.SILENT)
        assertEquals(ClassId.BINARY, match.classId)
        assertNull(match.subtype)
    }

    @Test
    fun `an unknown class keeps the raw entry and has no class`() {
        val match = ExtraSubtype.parse("CL-FUTURE:X", ClassId.SILENT)
        assertNull(match.classId)
        assertEquals("X", match.subtype)
        assertEquals("CL-FUTURE:X", match.raw)
    }

    @Test
    fun `a bare unknown class wire has no class and no subtype`() {
        val match = ExtraSubtype.parse("CL-FUTURE", ClassId.SILENT)
        assertNull(match.classId)
        assertNull(match.subtype)
        assertEquals("CL-FUTURE", match.raw)
    }

    @Test
    fun `an unreadable push content type is found in any case`() {
        val base =
            dev.nightjar.app.domain.TestEvents
                .inbound(ClassId.WAP_OTHER)
        assertTrue(base.copy(contentType = "UNKNOWN").wapContentTypeUnreadable())
        assertTrue(base.copy(contentType = " ").wapContentTypeUnreadable())
        assertTrue(!base.copy(contentType = "application/vnd.wap.sic").wapContentTypeUnreadable())
    }

    @Test
    fun `a free-form subtype with a colon stays a subtype of the event class`() {
        val match = ExtraSubtype.parse("application/x-test:v1", ClassId.WAP_OTHER)
        assertEquals(ClassId.WAP_OTHER, match.classId)
        assertEquals("application/x-test:v1", match.subtype)
    }

    @Test
    fun `the list column round-trips entries with commas and colons`() {
        val values = listOf("${ClassId.DATA_PORT.wire()}:UNKNOWN", "text/plain; a=1, b=2")
        val column = StringListColumn.encode(values)
        assertEquals(values, StringListColumn.decode(column))
    }

    @Test
    fun `an empty list is stored as null and null reads as empty`() {
        assertNull(StringListColumn.encode(emptyList()))
        assertTrue(StringListColumn.decode(null).isEmpty())
    }

    @Test
    fun `a damaged column reads as an empty list`() {
        assertTrue(StringListColumn.decode("not json").isEmpty())
        assertEquals(listOf("A"), StringListColumn.decode("""["A", 3, null, ""]"""))
    }
}
