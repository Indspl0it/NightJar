package dev.nightjar.app.feature.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The diagnostics bundle is complete, redacted and opt-in.
 * The numbers are from ranges reserved for fiction (+1 555 01xx, UK 07700 900xxx).
 */
class DiagnosticsBundleTest {
    private val device = listOf("app.version" to "1.0.0", "build.fingerprint" to "brand/device:14/UP1A/1:user/release-keys")

    private val hooks =
        listOf(
            HookRow("SmsHook", "LIVE", 0, null),
            HookRow("PduHook", "FAILED", 2, "NoSuchMethod for +15555550123"),
        )

    private val audit =
        listOf(
            AuditRow(1_793_836_800_000, "USER", "SETTINGS_CHANGE", "privacy.maskNumbers", "false", "true"),
        )

    @Test
    fun `every entry carries the marker`() {
        val entries = DiagnosticsBundle.build(device, hooks, audit, appLog = "line\n", messages = null)

        assertEquals(listOf("device.txt", "hooks.txt", "audit.json", "app.log"), entries.map { it.name })
        entries.forEach { entry -> assertTrue(entry.text.startsWith(DiagnosticsBundle.MARKER), entry.name) }
    }

    @Test
    fun `payload log and phone process entries are added and masked`() {
        val entries =
            DiagnosticsBundle.build(
                device = device,
                hooks = hooks,
                audit = audit,
                appLog = "",
                messages = null,
                payloadLog = listOf("first sender=+15555550123", "second"),
                phoneProcess = listOf("boot.count" to "3"),
            )

        val log = entries.single { it.name == "payload-log.txt" }.text
        assertFalse(log.contains("+15555550123"), log)
        assertTrue(log.indexOf("first") < log.indexOf("second"))
        assertTrue(entries.single { it.name == "phone-process.txt" }.text.contains("boot.count=3"))
    }

    @Test
    fun `audit target, before and after are masked`() {
        val change = AuditRow(1_793_836_800_000, "USER", "KNOWN_SENDER_ADD", "+15555550123", null, "{\"number\":\"07700 900123\"}")
        val entries = DiagnosticsBundle.build(device, hooks, listOf(change), appLog = "", messages = null)

        val json = entries.single { it.name == "audit.json" }.text
        assertFalse(json.contains("+15555550123"), json)
        assertFalse(json.contains("07700 900123"), json)
        assertTrue(json.contains("KNOWN_SENDER_ADD"))
        assertTrue(json.contains("\"before\":null"))
    }

    @Test
    fun `the app log keeps the newest lines when it is too long`() {
        val newest = "N".repeat(DiagnosticsBundle.MAX_LOG_CHARS / 2) + " newest"
        val oldest = "oldest " + "O".repeat(DiagnosticsBundle.MAX_LOG_CHARS)
        val text = DiagnosticsBundle.appLogText(listOf(newest, oldest))
        assertTrue(text.indexOf("oldest") < text.indexOf("newest"))

        val log = DiagnosticsBundle.build(device, hooks, audit, appLog = text, messages = null).single { it.name == "app.log" }.text
        assertTrue(log.contains("newest"))
        assertFalse(log.contains("oldest"))
    }

    @Test
    fun `messages are absent unless they were requested`() {
        val entries = DiagnosticsBundle.build(device, hooks, audit, appLog = "", messages = null)

        assertFalse(entries.any { it.name == "messages.txt" })
    }

    @Test
    fun `the message list has metadata and a masked sender, and no message body`() {
        val entries =
            DiagnosticsBundle.build(
                device = device,
                hooks = hooks,
                audit = audit,
                appLog = "",
                messages =
                    listOf(
                        MessageRow(
                            id = 7,
                            receivedAtWall = 1_793_836_800_000,
                            classId = "CL-SILENT",
                            severity = "SUSPICIOUS",
                            direction = "IN",
                            slot = 0,
                            originator = "+15555550123",
                            callingPackage = null,
                            consumerPackage = "com.example.messages",
                        ),
                        MessageRow(
                            id = 8,
                            receivedAtWall = 1_793_836_800_000,
                            classId = "CL-MO-DATA",
                            severity = "NOTABLE",
                            direction = "OUT",
                            slot = 0,
                            originator = null,
                            callingPackage = "com.example.sender",
                            consumerPackage = null,
                        ),
                    ),
            )

        val lines = entries.single { it.name == "messages.txt" }.text.lines()
        // The header has no text column, and each row has exactly the header's columns.
        // The package columns follow the masked sender.
        assertEquals(
            "id\treceivedAtWall\tclass\tseverity\tdirection\tslot\toriginator\tcallingPackage\tconsumerPackage",
            lines[1],
        )
        val incoming = lines[2].split('\t')
        assertEquals(9, incoming.size)
        assertEquals("CL-SILENT", incoming[2])
        assertFalse(lines[2].contains("+15555550123"), lines[2])
        assertTrue(incoming[6].isNotEmpty())
        assertEquals("", incoming[7])
        assertEquals("com.example.messages", incoming[8])
        val outgoing = lines[3].split('\t')
        assertEquals(9, outgoing.size)
        assertEquals("com.example.sender", outgoing[7])
        assertEquals("", outgoing[8])
    }

    @Test
    fun `a message row has no field that can carry the message body`() {
        // The export must not contain text even when a caller has it, so the row type has no place for it.
        val fields = MessageRow::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { it.contains("text") || it.contains("body") || it.contains("preview") }, fields.toString())
    }

    @Test
    fun `the app log is redacted`() {
        val entries =
            DiagnosticsBundle.build(
                device = device,
                hooks = hooks,
                audit = audit,
                appLog = "I Sms  sender=+15555550123 stored\n",
                messages = null,
            )

        val log = entries.single { it.name == "app.log" }.text
        assertFalse(log.contains("+15555550123"), log)
        assertTrue(log.contains("stored"))
    }

    @Test
    fun `a hook error is masked in the text report`() {
        val entries = DiagnosticsBundle.build(device, hooks, audit, appLog = "", messages = null)

        val report = entries.single { it.name == "hooks.txt" }.text
        assertFalse(report.contains("+15555550123"), report)
        assertTrue(report.contains("PduHook"))
    }

    @Test
    fun `the zip contains one member per entry`() {
        val dir = Files.createTempDirectory("diag").toFile()
        try {
            val target = File(dir, "bundle.zip")
            val entries = DiagnosticsBundle.build(device, hooks, audit, appLog = "", messages = null)

            DiagnosticsBundle.writeZip(target, entries)

            assertTrue(target.isFile)
            val names =
                java.util.zip
                    .ZipFile(target)
                    .use { zip -> zip.entries().toList().map { it.name } }
            assertEquals(entries.map { it.name }, names)
        } finally {
            dir.deleteRecursively()
        }
    }
}
