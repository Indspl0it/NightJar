package dev.nightjar.app.feature.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The CSV, JSON and PDU writers, redaction, and the schema marker.
 * The numbers are from the range reserved for fiction (+1 555 01xx).
 */
class ExportWriterTest {
    private val message =
        ExportMessage(
            id = 7,
            eventId = "evt-7",
            revision = 2,
            receivedAtWall = 1_760_000_000_000,
            direction = "IN",
            transport = "CS",
            source = 0,
            subId = 1,
            slot = 0,
            carrierName = "Test",
            classId = "CL-WAP-CP",
            severity = "SUSPICIOUS",
            detectorSeverity = null,
            severityOverridden = false,
            title = "Settings push",
            originator = "+15555550123",
            smsc = "+15555550100",
            destPort = 2948,
            contentType = "application/vnd.wap.connectivity-wbxml",
            textBody = "Call +15555550123 now",
            textPreview = "Call +15555550123 now",
            verdict = "QUARANTINE",
            ruleId = 4,
            fate = "FT-QUARANTINED",
            fateDetail = null,
            consumerPackage = null,
            callingPackage = null,
            persistFlag = null,
            concatRef = null,
            partsTotal = 1,
            partsHave = 1,
            isQuarantined = true,
            isReviewed = false,
            isStarred = false,
            isReplay = false,
            buildFingerprint = "fp",
            payloadVersion = "1.0",
            pdus =
                listOf(
                    ExportPdu(partNo = 1, format = "GSM", hex = "00AA", decodedJson = """{"name":"tpdu"}"""),
                ),
            trace = listOf(ExportTrace(seq = 1, stage = "HK-01", tNs = 42, detail = null)),
        )

    /** Text on, numbers not masked: the only options that show the raw PDU. */
    private val raw = ExportOptions(includeText = true)

    /** Writes [messages] with the same writer the export run uses and returns the message count. */
    private fun write(
        out: Appendable,
        messages: List<ExportMessage>,
        format: ExportFormat,
        options: ExportOptions,
    ): Int {
        val writer = exportWriter(format, out, options, 1L, "1.0")
        writer.begin()
        messages.forEach(writer::message)
        writer.end(messages.size)
        return messages.size
    }

    private fun write(
        messages: List<ExportMessage>,
        format: ExportFormat,
        options: ExportOptions,
    ): String = StringBuilder().also { write(it, messages, format, options) }.toString()

    @Test
    fun `json carries the schema marker, PDUs and trace but no text by default`() {
        val json = write(listOf(message), ExportFormat.JSON, ExportOptions())
        assertTrue(json.contains("\"nightjarExport\":2"))
        assertTrue(json.contains("\"includeText\":false"))
        assertTrue(json.contains("\"rawPdu\":false"))
        assertFalse(json.contains("textBody"))
        assertFalse(json.contains("Call"))
        assertTrue(json.contains("\"stage\":\"HK-01\""))
        assertEquals(1, "\"id\":".toRegex().findAll(json).count())
    }

    @Test
    fun `json hides the PDU hex when message text is off`() {
        val json = write(listOf(message), ExportFormat.JSON, ExportOptions(includeTree = true))
        assertFalse(json.contains("\"hex\":\"00AA\""))
        assertTrue(json.contains("\"hex\":\"\\u00ab4 chars\\u00bb\""))
        assertFalse(json.contains("\"decoded\""))
    }

    @Test
    fun `json shows the PDU hex and the tree with text on and numbers not masked`() {
        val json = write(listOf(message), ExportFormat.JSON, raw.copy(includeTree = true))
        assertTrue(json.contains("\"rawPdu\":true"))
        assertTrue(json.contains("\"hex\":\"00AA\""))
        assertTrue(json.contains("\"decoded\":{\"name\":\"tpdu\"}"))
        assertTrue(json.contains("\"includeDecodedTree\":true"))
    }

    @Test
    fun `json text, numbers and PDU hex are redacted when masking is on`() {
        val json =
            write(listOf(message), ExportFormat.JSON, ExportOptions(includeText = true, includeTree = true, maskNumbers = true))
        assertTrue(json.contains("+1\\u2022\\u2022\\u2022\\u2022\\u2022123"))
        assertFalse(json.contains("+15555550123"))
        assertFalse(json.contains("+15555550100"))
        assertFalse(json.contains("\"hex\":\"00AA\""))
        assertTrue(json.contains("\"rawPdu\":false"))
        assertFalse(json.contains("\"decoded\""))
        assertTrue(json.contains("\"includeDecodedTree\":false"))
    }

    @Test
    fun `csv has no PDU column and respects the text option`() {
        val without = write(listOf(message), ExportFormat.CSV, ExportOptions())
        val header = without.lineSequence().first()
        assertFalse(header.contains("hex"))
        assertFalse(header.contains(",text"))
        assertEquals(2, without.lines().count { it.isNotBlank() })

        val with = write(listOf(message), ExportFormat.CSV, raw)
        assertTrue(with.lineSequence().first().endsWith(",text"))
        assertTrue(with.contains("Call +15555550123 now"))
    }

    @Test
    fun `csv neutralises a formula in the content type and the event id`() {
        val hostile = message.copy(eventId = "=evt", contentType = "=HYPERLINK(\"x\")")
        val row = write(listOf(hostile), ExportFormat.CSV, ExportOptions()).lines()[1]
        assertTrue(row.contains(",'=evt,"))
        assertTrue(row.contains("\"'=HYPERLINK(\"\"x\"\")\""))
    }

    @Test
    fun `json carries the subtype and the lists, and an old message gets null and empty arrays`() {
        val v2 =
            message.copy(
                subtype = "NOSEC",
                extraSubtypes = listOf("CL-DATA-PORT:UNKNOWN"),
                anomalies = listOf("AN-IE-RSV", "AN-PORT-ZERO"),
            )
        val json = write(listOf(v2, message), ExportFormat.JSON, ExportOptions())
        assertTrue(json.contains("\"nightjarExport\":2"))
        assertTrue(json.contains("\"subtype\":\"NOSEC\""))
        assertTrue(json.contains("\"extraSubtypes\":[\"CL-DATA-PORT:UNKNOWN\"]"))
        assertTrue(json.contains("\"anomalies\":[\"AN-IE-RSV\",\"AN-PORT-ZERO\"]"))
        assertTrue(json.contains("\"subtype\":null,\"extraSubtypes\":[],\"anomalies\":[]"))
        // The old keys keep their names and order.
        assertTrue(json.contains("\"payloadVersion\":\"1.0\",\"subtype\""))
    }

    @Test
    fun `csv adds the new columns before text so text stays last`() {
        val v2 = message.copy(subtype = "NOSEC", anomalies = listOf("AN-IE-RSV", "AN-PORT-ZERO"))
        val lines = write(listOf(v2), ExportFormat.CSV, raw).lines().filter { it.isNotBlank() }
        assertTrue(lines[0].endsWith(",partsTotal,subtype,extraSubtypes,anomalies,text"))
        assertTrue(lines[1].contains(",NOSEC,,AN-IE-RSV|AN-PORT-ZERO,"))
    }

    @Test
    fun `csv fields are quoted per RFC 4180`() {
        assertEquals("plain", csvField("plain"))
        assertEquals("\"a,b\"", csvField("a,b"))
        assertEquals("\"a\"\"b\"", csvField("a\"b"))
    }

    @Test
    fun `pdu hex writes one line per part with a metadata comment`() {
        val lines = write(listOf(message), ExportFormat.PDU_HEX, raw).lines()
        assertEquals("# nightjarExport: 2", lines[0])
        assertTrue(lines.any { it.startsWith("# message id=7") })
        assertTrue(lines.any { it == "# part 1 GSM" })
        assertTrue(lines.any { it == "00AA" })
        assertTrue(lines.any { it.startsWith("# count=1") })
    }

    @Test
    fun `pdu hex writes only the length as a comment when the PDU must stay private`() {
        listOf(ExportOptions(), ExportOptions(includeText = true, maskNumbers = true)).forEach { options ->
            val lines = write(listOf(message), ExportFormat.PDU_HEX, options).lines()
            assertFalse(lines.any { it.contains("00AA") }, options.toString())
            assertTrue(lines.any { it == "# \u00ab4 chars\u00bb" }, options.toString())
            assertTrue(lines.filter { it.isNotBlank() }.all { it.startsWith("#") }, options.toString())
        }
    }

    @Test
    fun `file names follow the format extension`() {
        val json = exportFileName(ExportFormat.JSON, 0)
        assertTrue(json.startsWith("nightjar-export-"))
        assertTrue(json.endsWith(".json"))
        assertTrue(exportFileName(ExportFormat.CSV, 0).endsWith(".csv"))
    }

    @Test
    fun `json strings escape quotes and non-ascii`() {
        assertEquals("\"a\\\"b\"", jsonString("a\"b"))
        assertEquals("\"\\u00b7\"", jsonString("\u00b7"))
        assertEquals("null", jsonString(null))
    }

    @Test
    fun `an empty export still produces a valid document`() {
        val json = write(emptyList(), ExportFormat.JSON, ExportOptions())
        assertTrue(json.endsWith("\"count\":0}"))
    }

    @Test
    fun `writer rejects a closed appendable`() {
        val closed =
            object : Appendable {
                override fun append(csq: CharSequence?): Appendable = throw IllegalStateException("closed")

                override fun append(
                    csq: CharSequence?,
                    start: Int,
                    end: Int,
                ): Appendable = throw IllegalStateException("closed")

                override fun append(c: Char): Appendable = throw IllegalStateException("closed")
            }
        assertThrows<IllegalStateException> {
            write(closed, listOf(message), ExportFormat.JSON, ExportOptions())
        }
    }
}
