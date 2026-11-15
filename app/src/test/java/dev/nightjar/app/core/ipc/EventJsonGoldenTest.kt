package dev.nightjar.app.core.ipc

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Ipc
import dev.nightjar.contract.Wire
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * RULES-X2, the app parser reads every golden event of `:contract` (the files the payload tests write
 * against). A key that one side renames fails here and not on a phone.
 */
class EventJsonGoldenTest {
    private fun goldenEvents(): List<Pair<String, JSONObject>> {
        val files = File(GOLDEN_DIR).listFiles { f -> f.name.startsWith("event_") && f.name.endsWith(".json") }.orEmpty()
        assertTrue(files.isNotEmpty(), "no golden events in $GOLDEN_DIR")
        return files.sortedBy { it.name }.map { it.name to JSONObject(it.readText()) }
    }

    @Test
    fun `every golden event parses with the class, fate and verdict it carries`() {
        for ((name, o) in goldenEvents()) {
            val event = EventJson.parseEvent(o) { it.classId.wire() }
            assertNotNull(event, name)
            event!!
            assertEquals(o.getString("eventId"), event.eventId, name)
            val expectedClass = Wire.parse(ClassId::class.java, o.optString("finalClass"), null)
            if (expectedClass != null) assertEquals(expectedClass.wire(), event.classId, name)
            val fate = Wire.parse(Fate::class.java, o.optString("fate"), Fate.UNKNOWN)
            assertEquals(fate.wire(), event.fate, name)
            assertEquals(o.optString(Ipc.EventKey.SUBTYPE, "").ifEmpty { null }, event.subtype, name)
            assertEquals(o.optJSONArray("pdus")?.length() ?: 0, event.pdus.size, name)
            val env = o.optJSONObject("env")
            if (env?.has("roaming") == true) assertEquals(env.getBoolean("roaming"), event.roaming, name)
            assertEquals(o.has(Ipc.EventKey.SIM_OTA_HEADER), event.simOtaHeader != null, name)
            assertEquals(o.has("parts"), event.partsJson != null, name)
        }
    }

    @Test
    fun `the quarantined WAP push keeps its rule, port and hold state`() {
        val o = goldenEvents().first { it.first == "event_wap_quarantined.json" }.second
        val event = EventJson.parseEvent(o) { it.classId.wire() }!!
        assertEquals(ClassId.WAP_CP.wire(), event.classId)
        assertTrue(event.isQuarantined)
        assertEquals(7L, event.ruleId)
        assertEquals(o.getInt("destPort"), event.destPort)
        assertFalse(event.roaming!!)
        assertEquals(true, event.userUnlocked)
        assertNull(event.releaseOf)
    }

    @Test
    fun `the SIM OTA header is stored as the JSON object it was`() {
        val o = goldenEvents().first { it.first == "event_sim_ota_header.json" }.second
        val event = EventJson.parseEvent(o) { it.classId.wire() }!!
        val header = JSONObject(event.simOtaHeader!!)
        val sent = o.getJSONObject(Ipc.EventKey.SIM_OTA_HEADER)
        for (key in listOf(Ipc.SimOtaKey.SPI, Ipc.SimOtaKey.TAR, Ipc.SimOtaKey.CNTR)) {
            assertEquals(sent.getLong(key), header.getLong(key), key)
        }
    }

    private companion object {
        /** Gradle runs the tests of `:app` with the module directory as the working directory. */
        const val GOLDEN_DIR = "../contract/src/test/resources/golden"
    }
}
