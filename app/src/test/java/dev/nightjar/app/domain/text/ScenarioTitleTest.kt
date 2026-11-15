package dev.nightjar.app.domain.text

import dev.nightjar.app.domain.FakeDetectionHistory
import dev.nightjar.app.domain.TestEvents
import dev.nightjar.app.domain.classify.EventClassifier
import dev.nightjar.app.domain.detect.DetectorConfig
import dev.nightjar.app.domain.detect.DetectorEngine
import dev.nightjar.app.domain.detect.FakePatternHistory
import dev.nightjar.app.domain.detect.WallClock
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.model.CbInfo
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.severity.SeverityConfig
import dev.nightjar.contract.AnomalyCode
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Wire
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every scenario in `testdata/scenarios.json` gets the title that it expects, and the default severity that it
 * expects.
 *
 * The event is built from the scenario: the class, subtype, anomalies and extra subtypes from `expect`, the port from
 * `port` or from the UDH of the PDU, the message identifier from a cell broadcast PDU. A sequence uses its last step.
 * The severity runs through the real [EventClassifier] and detectors, with a sender that is not known and an empty
 * history (a single send). A sequence of more than one message needs stored history, so its severity is not checked.
 *
 * The [MapStrings] templates equal the resources (PlaceholderCoverageTest and the Python mirror check this), so the
 * titles here are the titles the user sees.
 */
class ScenarioTitleTest {
    private val labels = PackageLabels { pkg -> if (pkg == SAMPLE_APP) "Example Sender" else null }
    private val explainer = Explainer(MapStrings(), labels)

    private fun scenarios(): List<JsonObject> {
        val file =
            listOf("../testdata/scenarios.json", "testdata/scenarios.json")
                .map(::File)
                .firstOrNull { it.isFile } ?: error("scenarios.json not found from ${File("").absolutePath}")
        return Json
            .parseToJsonElement(file.readText())
            .jsonObject
            .getValue("scenarios")
            .jsonArray
            .map { it.jsonObject }
    }

    private fun JsonObject.text(key: String): String? = this[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.list(key: String): List<String> =
        this[key]
            ?.takeIf { it != JsonNull }
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()

    /** The step that the expectation describes: the last step of a sequence. */
    private fun lastStep(
        scenario: JsonObject,
        byId: Map<String, JsonObject>,
    ): JsonObject {
        var s = scenario
        while (s["sequence"] != null) {
            val ref =
                s
                    .getValue("sequence")
                    .jsonArray
                    .last()
                    .jsonObject
                    .text("ref")
            s = byId.getValue(ref!!)
        }
        return s
    }

    private fun facts(
        scenario: JsonObject,
        byId: Map<String, JsonObject>,
    ): EventFacts {
        val step = lastStep(scenario, byId)
        val expect = scenario.getValue("expect").jsonObject
        val classWire = expect.text("class")!!
        val classId = Wire.parse(ClassId::class.java, classWire, null) ?: error("unknown class $classWire")
        val subtype = expect.text("subtype")
        val method = step.text("method")
        val cbMessageId = if (method == "cb-pdu") step.text("pdu")!!.substring(4, 8).toInt(16) else null
        // A push has a readable content type, except a push that NightJar cannot read. CL-WAP-OTHER stores the
        // content type as its subtype.
        val contentType =
            when {
                classId == ClassId.WAP_OTHER -> subtype
                classWire.startsWith("CL-WAP") && classId != ClassId.WAP_UNREADABLE -> "application/x-test"
                else -> null
            }
        val app = if (method == "app-send" || method == "inject-provider") SAMPLE_APP else null
        val base =
            if (classWire.startsWith("CL-MO-")) {
                TestEvents.outbound(classId, destination = SENDER, callingPackage = app)
            } else {
                TestEvents.inbound(classId, originator = SENDER).copy(callingPackage = app)
            }
        return base.copy(
            subtype = subtype,
            anomalies = expect.list("anomalies"),
            extraSubtypes = expect.list("extraSubtypes"),
            destPort = step["port"]?.jsonPrimitive?.int ?: step.text("pdu")?.let(::destPort),
            contentType = contentType,
            cb = cbMessageId?.let { CbInfo(serial = 0, messageId = it, page = 1, pages = 1, emergency = false) },
        )
    }

    @Test
    fun `every scenario gets its expected title`() {
        val all = scenarios()
        val byId = all.associateBy { it.text("id")!! }
        val failures = mutableListOf<String>()
        var checked = 0
        for (s in all) {
            val id = s.text("id")!!
            val want = s.getValue("expect").jsonObject.text("title")
            val got = explainer.title(facts(s, byId))
            if (got != want) failures += "$id: got \"$got\", expected \"$want\""
            checked++
        }
        assertTrue(checked >= 150, "scenarios checked: $checked")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `every single send gets its expected severity`() =
        runTest {
            val all = scenarios()
            val byId = all.associateBy { it.text("id")!! }
            val failures = mutableListOf<String>()
            var checked = 0
            for (s in all) {
                val id = s.text("id")!!
                val steps = s["sequence"]?.jsonArray?.size ?: 1
                if (steps > 1) continue
                val event = facts(s, byId)
                val engine =
                    DetectorEngine(
                        DetectorConfig.DEFAULT,
                        FakeDetectionHistory(),
                        KnownOriginatorMatcher.EMPTY,
                        FakePatternHistory(),
                        WallClock { event.receivedAtWallMs },
                    )
                val got = EventClassifier(engine, SeverityConfig.DEFAULT).classify(event).severity
                val want = PLAN_SEVERITY.getValue(s.getValue("expect").jsonObject.text("severity")!!)
                if (got != want) failures += "$id: got $got, expected $want"
                checked++
            }
            assertTrue(checked >= 140, "scenarios checked: $checked")
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }

    /**
     * `expect.reason` is the summary sentence of the pattern that fired. Each reason must be the
     * text of a pattern [TextKey], with a value in place of each placeholder, so a changed sentence fails here.
     */
    @Test
    fun `every expected reason is the summary of a pattern`() {
        val patterns =
            TextKey.entries
                .filter { it.name.startsWith("PAT_") }
                .map { key -> Regex(key.template.split(PLACEHOLDER).joinToString("\\S+") { Regex.escape(it) }) }
        val failures = mutableListOf<String>()
        var checked = 0
        for (s in scenarios()) {
            val reason = s.getValue("expect").jsonObject.text("reason") ?: continue
            if (patterns.none { it.matches(reason) }) failures += "${s.text("id")}: \"$reason\" is not a pattern summary"
            checked++
        }
        assertTrue(checked >= 11, "reasons checked: $checked")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `anomaly names equal the scenarios json wording`() {
        val file =
            listOf("../testdata/scenarios.json", "testdata/scenarios.json")
                .map(::File)
                .first { it.isFile }
        val names =
            Json
                .parseToJsonElement(file.readText())
                .jsonObject
                .getValue("anomalyNames")
                .jsonObject
        for ((code, name) in names) {
            assertEquals(name.jsonPrimitive.content, explainer.anomalyName(code), code)
        }
        assertEquals(AnomalyCode.values().size, names.size)
    }

    private companion object {
        const val SAMPLE_APP = "com.example.sender"

        /** A synthetic number that is not in Known senders and is not a carrier. */
        const val SENDER = "+15550100000"

        /** The severity names that `scenarios.json` uses. */
        val PLAN_SEVERITY =
            mapOf(
                "Info" to Severity.INFO,
                "Notice" to Severity.NOTABLE,
                "Suspicious" to Severity.SUSPICIOUS,
                "High" to Severity.HIGH,
            )

        /** A `{name}` placeholder of a [TextKey] template. */
        val PLACEHOLDER = Regex("\\{[a-zA-Z]+\\}")

        /** The destination port of a GSM SMS-DELIVER PDU (IE 0x05 or 0x04, the last one wins), or null. */
        fun destPort(hex: String): Int? {
            val b = hex.chunked(2).map { it.toInt(16) }
            return runCatching {
                var i = b[0] + 1
                val first = b[i]
                if (first and 0x03 != 0 || first and 0x40 == 0) return@runCatching null
                i += 1
                val oaDigits = b[i]
                i += 2 + (oaDigits + 1) / 2
                i += 2 + 7 + 1 // PID, DCS, SCTS, UDL
                val end = i + 1 + b[i]
                var j = i + 1
                var port: Int? = null
                while (j + 1 < end) {
                    val iei = b[j]
                    val len = b[j + 1]
                    if (iei == 0x05 && len == 4) port = (b[j + 2] shl 8) or b[j + 3]
                    if (iei == 0x04 && len == 2) port = b[j + 2]
                    j += 2 + len
                }
                port
            }.getOrNull()
        }
    }
}
