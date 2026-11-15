package dev.nightjar.app.domain.text

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.TestEvents
import dev.nightjar.app.domain.detect.DetectionDraft
import dev.nightjar.app.domain.detect.DetectorId
import dev.nightjar.app.domain.model.CbInfo
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every class and fate renders with no unfilled placeholder. */
class ExplainerTest {
    private val strings = MapStrings()
    private val labels = PackageLabels { pkg -> if (pkg == "com.example.app") "Example" else null }
    private val explainer = Explainer(strings, labels, DateFormatter { "5 Nov 2026" })

    private fun event(
        classId: ClassId,
        fate: Fate = Fate.SYSTEM,
    ): EventFacts =
        TestEvents.inbound(classId).copy(
            fate = fate,
            destPort = 2948,
            contentType = "application/vnd.wap.connectivity-wbxml",
            callingPackage = "com.example.app",
            fateDetail = mapOf(EventFacts.FATE_DETAIL_PACKAGE to "com.example.app"),
        )

    @Test
    fun `every class title and explanation fills its placeholders`() {
        for (classId in ClassId.entries) {
            val e = event(classId)
            val title = explainer.title(e)
            val explanation = explainer.explanation(e)
            assertEquals(emptyList<String>(), Templates.unfilled(title), "title of $classId: $title")
            assertEquals(emptyList<String>(), Templates.unfilled(explanation), "explanation of $classId: $explanation")
            assertTrue(title.isNotBlank(), "title of $classId")
            assertTrue(explanation.isNotBlank(), "explanation of $classId")
        }
    }

    @Test
    fun `every fate renders`() {
        for (fate in Fate.entries) {
            val text = explainer.fateText(event(ClassId.SILENT, fate))
            assertEquals(emptyList<String>(), Templates.unfilled(text), "fate $fate: $text")
            assertTrue(text.isNotBlank(), "fate $fate")
        }
    }

    @Test
    fun `fate text uses the consumer app label`() {
        val e =
            event(ClassId.WAP_CP, Fate.APP).copy(
                fateDetail = mapOf(EventFacts.FATE_DETAIL_PACKAGE to "com.example.app"),
            )
        assertEquals("Delivered to Example", explainer.fateText(e))
    }

    @Test
    fun `the default sms app is named when known`() {
        val named = Explainer(strings, labels, DateFormatter { "d" }, defaultSmsAppLabel = { "Google Messages" })
        val e = event(ClassId.NORMAL, Fate.SHOWN).copy(classId = ClassId.NORMAL)
        assertEquals("Shown in Google Messages", named.fateText(e))
        // Without a known default app the copy stays generic instead of showing an empty placeholder.
        assertFalse(explainer.fateText(e).contains("{"))
    }

    @Test
    fun `blocked fate names the rule`() {
        val e = event(ClassId.WAP_CP, Fate.BLOCKED).copy(ruleName = "Unknown settings push")
        assertEquals("NightJar blocked it (Unknown settings push)", explainer.fateText(e))
        val byId = event(ClassId.WAP_CP, Fate.BLOCKED).copy(ruleId = 7)
        assertEquals("NightJar blocked it (rule 7)", explainer.fateText(byId))
    }

    @Test
    fun `an OEM redirect without a package names no app`() {
        // HOOKS-R31: HK-31 does not always report the package; the line must not name an app or show a placeholder.
        val named = event(ClassId.OEM, Fate.OEM_REDIRECTED)
        assertEquals("Software from the phone maker sent it to Example", explainer.fateText(named))
        val unnamed = named.copy(callingPackage = null, fateDetail = emptyMap())
        assertEquals("Software from the phone maker took it", explainer.fateText(unnamed))
    }

    @Test
    fun `a block without a rule does not name an unknown rule`() {
        // A class block (no rule) must not read "NightJar blocked it (unknown)".
        assertEquals("NightJar blocked it", explainer.fateText(event(ClassId.WAP_CP, Fate.BLOCKED)))
    }

    @Test
    fun `a send stopped by the outgoing block names the app`() {
        // The W-07 gate reports reason "W-07" or "W-07 <detail>"; {app} is the label, else the package name.
        val reason = EventFacts.FATE_DETAIL_REASON
        val labelled =
            TestEvents
                .outbound(ClassId.MO_DATA, callingPackage = "com.example.app")
                .copy(fate = Fate.BLOCKED, fateDetail = mapOf(reason to "W-07 com.example.app"))
        assertTrue(labelled.blockedByOutgoingGate)
        assertEquals("NightJar blocked it. You blocked Example from sending messages.", explainer.fateText(labelled))
        val unlabelled = labelled.copy(callingPackage = "com.example.other", fateDetail = mapOf(reason to "W-07"))
        assertEquals("NightJar blocked it. You blocked com.example.other from sending messages.", explainer.fateText(unlabelled))
        val noPackage = labelled.copy(callingPackage = null)
        assertEquals("NightJar blocked it. You blocked an app from sending messages.", explainer.fateText(noPackage))
        // Another reason, or an inbound block, keeps the plain block text.
        assertEquals("NightJar blocked it", explainer.fateText(labelled.copy(fateDetail = mapOf(reason to "W-070"))))
        val inbound = event(ClassId.WAP_CP, Fate.BLOCKED).copy(fateDetail = mapOf(reason to "W-07"))
        assertFalse(inbound.blockedByOutgoingGate)
        assertEquals("NightJar blocked it", explainer.fateText(inbound))
    }

    @Test
    fun `numbers are masked when the setting is on`() {
        val masked = Explainer(strings, labels, DateFormatter { "d" }, maskNumbers = { true })
        assertEquals("+1•••••210", masked.number("+15559876543210"))
        assertEquals("56789", masked.number("56789"))
        assertEquals("unknown sender", masked.number(null))
    }

    @Test
    fun `explanation includes the detection paragraph`() {
        val detection =
            DetectionDraft(
                detectorId = DetectorId.SILENT_PING,
                severity = Severity.SUSPICIOUS,
                summaryKey = TextKey.DET_SILENT_BURST,
                summaryArgs = mapOf("count" to "6", "minutes" to "10", "senders" to "2"),
                eventIds = listOf("e1"),
                firstAtWallMs = 0,
                lastAtWallMs = 0,
                count = 6,
            )
        val text = explainer.explanation(event(ClassId.SILENT), detection)
        assertTrue(text.contains("6 silent SMS in 10 minutes from 2 senders."))
        assertTrue(text.startsWith(strings.get(TextKey.EXP_CL_SILENT)))
    }

    @Test
    fun `first sentence is the notification big text`() {
        val e = event(ClassId.SILENT)
        val first = explainer.explanationFirstSentence(e)
        assertTrue(first.endsWith("."))
        assertTrue(first.length < explainer.explanation(e).length)
        assertEquals("One sentence.", Explainer.firstSentence("One sentence. Two."))
        assertEquals("No stop", Explainer.firstSentence("No stop"))
    }

    @Test
    fun `CB titles name the channel and emergency alerts are labelled`() {
        val normal = event(ClassId.CB).copy(cb = CbInfo(1, 4370, 1, 1, emergency = true))
        assertTrue(explainer.title(normal).startsWith("Emergency alert"))
        assertEquals("Cell broadcast (channel 4370)", explainer.render(TextKey.TITLE_CL_CB, mapOf("channel" to "4370")))
        val other = event(ClassId.CB).copy(subtype = Subtype.Cb.CHANNEL, cb = CbInfo(1, 221, 1, 1, emergency = false))
        assertEquals("Cell broadcast (channel 221)", explainer.title(other))
        assertTrue(explainer.explanation(other).contains("channel 221"))
    }

    // ------------------------------------------------------------------ titles and evidence

    @Test
    fun `a known port names its type and an unknown port gives its number`() {
        // A known port title is the type name alone. The explanation still names the port.
        val known = event(ClassId.DATA_PORT).copy(destPort = 2949, subtype = Subtype.DataPort.WAP_PUSH_SECURE)
        assertEquals("WAP push (secure)", explainer.title(known))
        assertTrue(explainer.explanation(known).contains("port 2949"))
        // A row from before the subtype field takes the subtype from the port table.
        val oldRow = event(ClassId.DATA_PORT).copy(destPort = 5503)
        assertEquals("Internet access configuration", explainer.title(oldRow))
        val unknown = event(ClassId.DATA_PORT).copy(destPort = 16001, subtype = Subtype.DataPort.UNKNOWN)
        assertEquals("Data message to port 16001", explainer.title(unknown))
        assertFalse(explainer.title(unknown).contains("Hidden"))
    }

    @Test
    fun `a malformed message names its anomaly in the title and the explanation`() {
        val e = event(ClassId.MALFORMED_SMS).copy(subtype = "AN-ADDR-TRUNC", anomalies = listOf("AN-ADDR-TRUNC"))
        assertEquals("Malformed SMS: address cut off", explainer.title(e))
        assertTrue(explainer.explanation(e).contains("address cut off"))
        // The anomaly in the title is not repeated in the evidence line.
        assertEquals("", explainer.evidence(e))
    }

    @Test
    fun `the evidence line names the anomalies and the other matching types`() {
        val e =
            event(ClassId.DATA_PORT).copy(
                destPort = 0,
                subtype = Subtype.DataPort.PORT0,
                anomalies = listOf("AN-PORT-ZERO", "AN-IE-DUP"),
                extraSubtypes = listOf("CL-FLASH:CLASS0", "CL-REPLACE"),
            )
        val evidence = explainer.evidence(e)
        assertTrue(evidence.contains("NightJar found these format errors: port 0, repeated port element."), evidence)
        assertTrue(evidence.contains("This message also matches these types: Flash SMS (Class 0), Replacement message."), evidence)
        assertTrue(explainer.explanation(e).contains(evidence))
    }

    @Test
    fun `U15 an app name is the label, or the package name when the app has no label`() {
        assertEquals("Example", explainer.appName("com.example.app"))
        assertEquals("com.example.other", explainer.appName("com.example.other"))
    }

    @Test
    fun `an unknown anomaly code is shown as the code`() {
        assertEquals("AN-FUTURE", explainer.anomalyName("AN-FUTURE"))
        assertEquals("unknown WSP type", explainer.anomalyName("AN-WSP-TYPE"))
    }

    @Test
    fun `app classes name the app from the subtype`() {
        val injected = event(ClassId.INJECTED).copy(callingPackage = null, fateDetail = emptyMap(), subtype = "com.example.app")
        assertEquals("Message added by an app (Example)", explainer.title(injected))
        val moData =
            TestEvents.outbound(ClassId.MO_DATA).copy(callingPackage = "com.example.app", destPort = null, subtype = "16001")
        // The title is "Data message sent". The explanation names the app and the port.
        assertEquals("Data message sent", explainer.title(moData))
        assertTrue(explainer.explanation(moData).contains("Example"))
        assertTrue(explainer.explanation(moData).contains("port 16001"))
    }

    @Test
    fun `subtype rows give their own title`() {
        val type0Port = event(ClassId.SILENT).copy(destPort = 16001, subtype = Subtype.Silent.TYPE0_PORT)
        assertEquals("Silent SMS (Type 0) to port 16001", explainer.title(type0Port))
        val replace = event(ClassId.REPLACE).copy(subtype = Subtype.known(ClassId.REPLACE)[2])
        assertEquals("Replacement message (type 3)", explainer.title(replace))
    }

    @Test
    fun `no subtype row leaves a placeholder in its title or explanation`() {
        for (classId in ClassId.entries) {
            for (row in ClassRegistry.subtypes(classId)) {
                val e = event(classId).copy(subtype = row.codes.first())
                val title = explainer.title(e)
                val explanation = explainer.explanation(e)
                assertEquals(emptyList<String>(), Templates.unfilled(title), "title of $classId ${row.codes}: $title")
                assertEquals(emptyList<String>(), Templates.unfilled(explanation), "explanation of $classId ${row.codes}")
            }
        }
    }

    @Test
    fun `every catalogue entry renders with generic arguments`() {
        val generic =
            mapOf(
                "port" to "2948",
                "contentType" to "application/vnd.wap.si",
                "channel" to "4370",
                "meaning" to "emergency alert",
                "package" to "com.example.app",
                "app" to "Example",
                "number" to "+15559876543210",
                "detail" to "health check",
                "rule" to "Unknown settings push",
                "date" to "5 Nov 2026",
                "code" to "1",
                "n" to "2",
                "count" to "6",
                "minutes" to "10",
                "senders" to "2",
                "tar" to "B00001",
                "apn" to "internet",
                "proxy" to "10.0.0.1:8080",
                "server" to "cp.example",
                "held" to "3",
                "blocked" to "1",
                // Notification copy (U7/U8).
                "what" to "network settings change",
                "hidden" to "9",
                "suspicious" to "2",
                "family" to "silent pings",
                "title" to "Silent ping",
                "who" to "+1•••••210",
                "time" to "14:02",
                "sim" to "SIM 1",
                "manager" to "Magisk",
                "hook" to "HK-08",
                "subs" to "SIM 1",
                "installed" to "0.0.9",
                "bundled" to "0.1.0",
                "id" to "7",
                "reason" to "storage full",
                "anomaly" to "port 0",
                "anomalies" to "port 0, repeated port element",
                "replaceType" to "1",
                "types" to "Flash SMS (Class 0)",
                "first" to "SIM 1",
                "second" to "SIM 2",
            )
        for (key in TextKey.entries) {
            val rendered = explainer.render(key, generic)
            assertEquals(emptyList<String>(), Templates.unfilled(rendered), "${key.name}: $rendered")
        }
    }

    @Test
    fun `severity labels exist for every level`() {
        for (severity in Severity.entries) {
            assertTrue(explainer.severityLabel(severity).isNotBlank())
        }
    }

    @Test
    fun `templates keep unknown placeholders visible`() {
        assertEquals("Hello {name}", Templates.format("Hello {name}", emptyMap()))
        assertEquals("Hello Ann", Templates.format("Hello {name}", mapOf("name" to "Ann")))
        assertEquals(listOf("name"), Templates.unfilled("Hello {name}"))
    }

    @Test
    fun `every class has a family`() {
        for (classId in ClassId.entries) {
            assertTrue(
                ClassRegistry
                    .familyOf(classId)
                    .labelKey.template
                    .isNotBlank(),
            )
        }
    }
}
