package dev.nightjar.app.domain.cb

import dev.nightjar.app.domain.text.MapStrings
import dev.nightjar.app.domain.text.Templates
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Each cell broadcast category has its own title, explanation and severity. */
class CbCategoryTest {
    private val strings = MapStrings()

    private fun title(
        classId: ClassId,
        subtype: String?,
        messageId: Int,
    ): String {
        val key = CbCategory.titleKey(classId, subtype, messageId) ?: error("no title for $classId $subtype")
        return Templates.format(strings.get(key), mapOf("channel" to messageId.toString()))
    }

    private fun explanation(
        classId: ClassId,
        subtype: String?,
        messageId: Int,
    ): String {
        val key = CbCategory.explanationKey(classId, subtype, messageId) ?: error("no explanation for $classId $subtype")
        return Templates.format(strings.get(key), mapOf("channel" to messageId.toString()))
    }

    @Test
    fun `presidential alert`() = assertEquals("Emergency alert: presidential", title(ClassId.CB, Subtype.Cb.PRESIDENTIAL, 4370))

    @Test
    fun `extreme alert`() = assertEquals("Emergency alert: extreme", title(ClassId.CB, Subtype.Cb.EXTREME, 4371))

    @Test
    fun `severe alert`() = assertEquals("Emergency alert: severe", title(ClassId.CB, Subtype.Cb.SEVERE, 4373))

    @Test
    fun `amber alert`() = assertEquals("Emergency alert: amber", title(ClassId.CB, Subtype.Cb.AMBER, 4379))

    @Test
    fun `test alert`() = assertEquals("Emergency alert: test", title(ClassId.CB, Subtype.Cb.TEST, 4380))

    @Test
    fun `exercise alert`() = assertEquals("Emergency alert: exercise", title(ClassId.CB, Subtype.Cb.EXERCISE, 4381))

    @Test
    fun `public safety`() = assertEquals("Emergency alert: public safety", title(ClassId.CB, Subtype.Cb.PUBLIC_SAFETY, 4396))

    @Test
    fun `operator defined`() = assertEquals("Emergency alert: network operator", title(ClassId.CB, Subtype.Cb.OPERATOR, 4382))

    @Test
    fun `etws earthquake`() = assertEquals("Earthquake warning", title(ClassId.CB, Subtype.Cb.ETWS_EQ, 4352))

    @Test
    fun `etws tsunami`() = assertEquals("Tsunami warning", title(ClassId.CB, Subtype.Cb.ETWS_TSUNAMI, 4353))

    @Test
    fun `etws both`() = assertEquals("Earthquake and tsunami warning", title(ClassId.CB, Subtype.Cb.ETWS_BOTH, 4354))

    @Test
    fun `etws test`() = assertEquals("Test warning", title(ClassId.CB, Subtype.Cb.ETWS_TEST, 4355))

    @Test
    fun `etws other`() = assertEquals("Emergency warning", title(ClassId.CB, Subtype.Cb.ETWS_OTHER, 4356))

    @Test
    fun `area information shows channel 50`() =
        assertEquals("Area information (channel 50)", title(ClassId.CB, Subtype.Cb.AREA_INFO, 50))

    @Test
    fun `other channel shows its number`() = assertEquals("Cell broadcast (channel 221)", title(ClassId.CB, Subtype.Cb.CHANNEL, 221))

    @Test
    fun `sim download has its own title and no channel`() {
        assertEquals("Cell broadcast for the SIM", title(ClassId.CB_SIM, null, 0x1000))
        assertEquals(TextKey.EXP_CB_SIM, CbCategory.explanationKey(ClassId.CB_SIM, null, 0x1000))
        assertNull(CbCategory.subtypeOf(ClassId.CB_SIM, null, 0x1000))
    }

    @Test
    fun `every subtype has a title that is not generic`() {
        for (code in Subtype.known(ClassId.CB)) {
            val key = CbCategory.titleKey(ClassId.CB, code, 221)
            assertNotEquals(TextKey.TITLE_CL_CB, key, code)
            if (code != Subtype.Cb.CHANNEL) assertNotEquals(TextKey.TITLE_CB_CHANNEL, key, code)
        }
    }

    @Test
    fun `every CL-CB explanation shows the channel number`() {
        for (code in Subtype.known(ClassId.CB)) {
            val text = explanation(ClassId.CB, code, 919)
            assertTrue(text.contains("channel 919"), "$code: $text")
            assertTrue(Templates.unfilled(text).isEmpty(), "$code: $text")
        }
        assertTrue(explanation(ClassId.CB_SIM, null, 4096).contains("4096"))
    }

    @Test
    fun `explanation follows the plan rows`() {
        assertEquals(TextKey.EXP_CB_CMAS, CbCategory.explanationKey(ClassId.CB, Subtype.Cb.SEVERE, 4373))
        assertEquals(TextKey.EXP_CB_ETWS, CbCategory.explanationKey(ClassId.CB, Subtype.Cb.ETWS_OTHER, 4356))
        assertEquals(TextKey.EXP_CB_AREA_INFO, CbCategory.explanationKey(ClassId.CB, Subtype.Cb.AREA_INFO, 50))
        assertEquals(TextKey.EXP_CB_CHANNEL, CbCategory.explanationKey(ClassId.CB, Subtype.Cb.CHANNEL, 221))
    }

    @Test
    fun `severity follows the plan`() {
        assertEquals(Severity.INFO, CbCategory.defaultSeverity(ClassId.CB, Subtype.Cb.PRESIDENTIAL, 4370))
        assertEquals(Severity.INFO, CbCategory.defaultSeverity(ClassId.CB, Subtype.Cb.ETWS_TEST, 4355))
        assertEquals(Severity.INFO, CbCategory.defaultSeverity(ClassId.CB, Subtype.Cb.AREA_INFO, 50))
        assertEquals(Severity.NOTABLE, CbCategory.defaultSeverity(ClassId.CB, Subtype.Cb.CHANNEL, 221))
        assertEquals(Severity.SUSPICIOUS, CbCategory.defaultSeverity(ClassId.CB_SIM, null, 0x1000))
        assertNull(CbCategory.defaultSeverity(ClassId.SILENT, null, null))
    }

    @Test
    fun `row without subtype gets the category from the message identifier`() {
        assertEquals(Subtype.Cb.PRESIDENTIAL, CbCategory.subtypeOf(ClassId.CB, null, 4383))
        assertEquals(Subtype.Cb.AREA_INFO, CbCategory.subtypeOf(ClassId.CB, null, 50))
        assertEquals(ClassId.CB_SIM, CbCategory.classOf(ClassId.CB, 0x1010))
        assertEquals(TextKey.TITLE_CB_SIM, CbCategory.titleKey(ClassId.CB, null, 0x1010))
        assertEquals(TextKey.TITLE_CB_CHANNEL, CbCategory.titleKey(ClassId.CB, null, null))
    }

    @Test
    fun `unknown subtype falls back to the message identifier`() {
        assertEquals(Subtype.Cb.EXTREME, CbCategory.subtypeOf(ClassId.CB, "NOT-A-CODE", 4372))
    }

    @Test
    fun `other classes are not cell broadcasts`() {
        assertFalse(CbCategory.isCb(ClassId.SILENT))
        assertNull(CbCategory.titleKey(ClassId.SILENT, null, 0x1000))
        assertNull(CbCategory.explanationKey(ClassId.WAP_CP, null, 50))
        assertEquals(ClassId.SILENT, CbCategory.classOf(ClassId.SILENT, 0x1000))
    }

    @Test
    fun `a CL-CB-SIM row stays CL-CB-SIM outside the download range`() {
        assertEquals(ClassId.CB_SIM, CbCategory.classOf(ClassId.CB_SIM, 4370))
        assertEquals(TextKey.TITLE_CB_SIM, CbCategory.titleKey(ClassId.CB_SIM, null, 4370))
    }

    @Test
    fun `strings_cb resources equal the TextKey templates`() {
        val xml = File("src/main/res/values/strings_cb.xml").readText()
        val byName =
            Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .associate { it.groupValues[1] to it.groupValues[2] }
        val keys = TextKey.entries.filter { it.name.startsWith("TITLE_CB_") || it.name.startsWith("EXP_CB_") }
        assertEquals(byName.keys, keys.map { it.resource }.toSet())
        for (key in keys) assertEquals(key.template, byName[key.resource], key.resource)
    }

    @Test
    fun `cb scenarios get the expected class, subtype, title and severity`() {
        val file =
            listOf("../testdata/scenarios.json", "testdata/scenarios.json")
                .map(::File)
                .firstOrNull { it.isFile } ?: error("scenarios.json not found from ${File("").absolutePath}")
        val scenarios =
            Json
                .parseToJsonElement(file.readText())
                .jsonObject
                .getValue("scenarios")
                .jsonArray
        val failures = mutableListOf<String>()
        var checked = 0
        for (element in scenarios) {
            val s = element.jsonObject
            if (s["method"]?.jsonPrimitive?.content != "cb-pdu") continue
            val pdu = s.getValue("pdu").jsonPrimitive.content
            // GSM CB page header: serial(2) message identifier(2), 3GPP TS 23.041 §9.4.1.2.
            val messageId = pdu.substring(4, 8).toInt(16)
            val expect = s.getValue("expect").jsonObject
            val wantClass = expect.getValue("class").jsonPrimitive.content
            val wantSub = expect["subtype"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            val wantSeverity = PLAN_SEVERITY.getValue(expect.getValue("severity").jsonPrimitive.content)
            val classId = CbCategory.classOf(ClassId.CB, messageId)
            val sub = CbCategory.subtypeOf(classId, null, messageId)
            val got =
                listOf(classId.wire(), sub, title(classId, sub, messageId), CbCategory.defaultSeverity(classId, sub, messageId))
            val want = listOf(wantClass, wantSub, expect.getValue("title").jsonPrimitive.content, wantSeverity)
            if (got != want) failures += "${s.getValue("id").jsonPrimitive.content}: got $got, expected $want"
            checked++
        }
        assertTrue(checked >= 13, "cb scenarios checked: $checked")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private companion object {
        /** The severity names that `scenarios.json` uses. */
        val PLAN_SEVERITY =
            mapOf(
                "Info" to Severity.INFO,
                "Notice" to Severity.NOTABLE,
                "Suspicious" to Severity.SUSPICIOUS,
                "High" to Severity.HIGH,
            )
    }
}
