package dev.nightjar.app.core.ipc

import dev.nightjar.contract.AnomalyCode
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import dev.nightjar.contract.FateDetail
import dev.nightjar.contract.Ipc
import dev.nightjar.contract.Subtype
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The IPC version 2 event fields (subtype, other rule matches, anomaly codes) reach the stored row and the
 * title facts, and a version 1 event without them still parses.
 *
 * The events are built with the contract keys, so a renamed key fails here and not on a phone.
 */
class EventJsonFieldsTest {
    /** The fields the title lambda saw, so the test can check what the title renderer gets. */
    private var seen: EventJson.EventJsonFields? = null

    private fun parse(o: JSONObject) =
        EventJson.parseEvent(o) { fields ->
            seen = fields
            fields.classId.wire()
        }!!

    private fun base(
        id: String,
        classId: ClassId,
    ): JSONObject =
        JSONObject()
            .put("eventId", id)
            .put("finalClass", classId.wire())
            .put("direction", "IN")

    @Test
    fun `a version 1 event has no subtype and empty lists`() {
        val event = parse(base("v1", ClassId.SILENT))
        assertNull(event.subtype)
        assertTrue(event.extraSubtypes.isEmpty())
        assertTrue(event.anomalies.isEmpty())
        val facts = EventJson.factsFor(seen!!)
        assertNull(facts.subtype)
        assertTrue(facts.extraSubtypes.isEmpty())
        assertTrue(facts.anomalies.isEmpty())
    }

    @Test
    fun `a version 2 event keeps the subtype, the other matches and the anomalies in order`() {
        val extras = listOf(Subtype.SimOta.NOSEC, "${ClassId.DATA_PORT.wire()}:UNKNOWN", ClassId.BINARY.wire())
        val anomalies = listOf(AnomalyCode.IE_RSV.wire(), AnomalyCode.PORT_ZERO.wire())
        val o =
            base("v2", ClassId.SIM_DD)
                .put(Ipc.EventKey.SUBTYPE, Subtype.SimOta.SAT)
                .put(Ipc.EventKey.EXTRA_SUBTYPES, JSONArray(extras))
                .put(Ipc.EventKey.ANOMALIES, JSONArray(anomalies))
        val event = parse(o)
        assertEquals(Subtype.SimOta.SAT, event.subtype)
        assertEquals(extras, event.extraSubtypes)
        assertEquals(anomalies, event.anomalies)
    }

    @Test
    fun `every fateDetail key reaches the stored row and the title facts`() {
        // The gate writes the reason, the package and the class of a blocked send; the Explainer reads them.
        val detail =
            JSONObject()
                .put(FateDetail.REASON, FateDetail.OUTBOUND_BLOCK)
                .put(FateDetail.PACKAGE, "com.example.sender")
                .put(FateDetail.CLASS, ClassId.MO_VISIBLE.wire())
                .put(FateDetail.SENT_INTENT_FAILED, true)
                .put("ruleId", 5)
                .put("nested", JSONObject().put("x", 1))
                .put("gone", JSONObject.NULL)
        val event = parse(base("f1", ClassId.MO_VISIBLE).put("direction", "OUT").put("fateDetail", detail))

        val stored = JSONObject(event.fateDetail!!)
        for (key in listOf(FateDetail.REASON, FateDetail.PACKAGE, FateDetail.CLASS, FateDetail.SENT_INTENT_FAILED, "nested")) {
            assertTrue(stored.has(key), key)
        }
        assertEquals("com.example.sender", event.consumerPackage)
        val facts = EventJson.factsFor(seen!!).fateDetail
        assertEquals(
            mapOf(
                FateDetail.REASON to FateDetail.OUTBOUND_BLOCK,
                FateDetail.PACKAGE to "com.example.sender",
                FateDetail.CLASS to ClassId.MO_VISIBLE.wire(),
                FateDetail.SENT_INTENT_FAILED to "true",
                "ruleId" to "5",
            ),
            facts,
        )
    }

    @Test
    fun `an event with no fate or FT-UNKNOWN is stored with the true fate it shows`() {
        // An older payload can still send FT-UNKNOWN; the app never stores or shows it.
        assertEquals(Fate.NOT_DISPATCHED.wire(), parse(base("u1", ClassId.NORMAL)).fate)
        assertEquals(
            Fate.ACCEPTED.wire(),
            parse(base("u2", ClassId.NORMAL).put("fate", Fate.UNKNOWN.wire()).put("ackResult", 1)).fate,
        )
        assertEquals(
            Fate.REJECTED.wire(),
            parse(base("u3", ClassId.NORMAL).put("fate", Fate.UNKNOWN.wire()).put("ackResult", 5)).fate,
        )
        assertEquals(
            Fate.NOT_SENT.wire(),
            parse(
                base("u4", ClassId.MO_DATA)
                    .put("direction", "OUT")
                    .put("fate", Fate.UNKNOWN.wire())
                    .put("fateDetail", JSONObject().put(FateDetail.NO_DISPATCH, true)),
            ).fate,
        )
        assertEquals(Fate.SEND_UNCONFIRMED.wire(), parse(base("u5", ClassId.MO_DATA).put("direction", "OUT")).fate)
        // A fate that the payload gives is kept, and so is the dispatch result that explains it.
        val rejected = parse(base("u6", ClassId.NORMAL).put("fate", Fate.REJECTED.wire()).put("ackResult", 10))
        assertEquals(Fate.REJECTED.wire(), rejected.fate)
        assertEquals(10, rejected.ackResult)
    }

    @Test
    fun `flags debug marks a debug injection and nothing else does`() {
        // The payload sets flags.debug on a debug injection.
        assertEquals(true, parse(base("d1", ClassId.SILENT).put("flags", JSONObject().put("debug", true))).debugInjected)
        assertNull(parse(base("d2", ClassId.SILENT).put("flags", JSONObject().put("debug", false))).debugInjected)
        assertNull(parse(base("d3", ClassId.SILENT).put("flags", JSONObject())).debugInjected)
        assertNull(parse(base("d4", ClassId.SILENT)).debugInjected)
    }

    @Test
    fun `the title facts get the version 2 fields`() {
        val o =
            base("t1", ClassId.MALFORMED_SMS)
                .put(Ipc.EventKey.SUBTYPE, AnomalyCode.UDL_LONG.wire())
                .put(Ipc.EventKey.ANOMALIES, JSONArray(listOf(AnomalyCode.UDL_LONG.wire())))
        parse(o)
        val facts = EventJson.factsFor(seen!!)
        assertEquals(ClassId.MALFORMED_SMS, facts.classId)
        assertEquals(AnomalyCode.UDL_LONG.wire(), facts.subtype)
        assertEquals(listOf(AnomalyCode.UDL_LONG.wire()), facts.anomalies)
    }

    @Test
    fun `JSON null, empty text and entries that are not strings are dropped`() {
        val o =
            base("n1", ClassId.SILENT)
                .put(Ipc.EventKey.SUBTYPE, JSONObject.NULL)
                .put(
                    Ipc.EventKey.EXTRA_SUBTYPES,
                    JSONArray()
                        .put(7)
                        .put("")
                        .put(JSONObject.NULL)
                        .put(Subtype.Silent.TYPE0_PORT),
                ).put(Ipc.EventKey.ANOMALIES, JSONArray().put(JSONObject()).put(AnomalyCode.MTI.wire()))
        val event = parse(o)
        assertNull(event.subtype)
        assertEquals(listOf(Subtype.Silent.TYPE0_PORT), event.extraSubtypes)
        assertEquals(listOf(AnomalyCode.MTI.wire()), event.anomalies)
    }

    @Test
    fun `a field of the wrong type gives the empty value and the event still parses`() {
        val o =
            base("w1", ClassId.SILENT)
                .put(Ipc.EventKey.SUBTYPE, 40)
                .put(Ipc.EventKey.EXTRA_SUBTYPES, "TYPE0")
                .put(Ipc.EventKey.ANOMALIES, JSONObject())
        val event = parse(o)
        assertEquals("w1", event.eventId)
        assertNull(event.subtype)
        assertTrue(event.extraSubtypes.isEmpty())
        assertTrue(event.anomalies.isEmpty())
    }

    @Test
    fun `an anomaly code that this app does not know is kept`() {
        val o = base("u1", ClassId.SILENT).put(Ipc.EventKey.ANOMALIES, JSONArray(listOf("AN-FUTURE")))
        assertEquals(listOf("AN-FUTURE"), parse(o).anomalies)
    }

    @Test
    fun `a long list is capped`() {
        val many = (0 until 100).map { AnomalyCode.IE_RSV.wire() }
        val o = base("c1", ClassId.SILENT).put(Ipc.EventKey.ANOMALIES, JSONArray(many))
        assertEquals(32, parse(o).anomalies.size)
    }

    @Test
    fun `a batch mixes version 1 and version 2 events`() {
        val batch =
            JSONArray()
                .put(base("b1", ClassId.SILENT))
                .put(base("b2", ClassId.SILENT).put(Ipc.EventKey.SUBTYPE, Subtype.Silent.TYPE0))
                .toString()
        val events = EventJson.parseBatch(batch) { fields -> fields.classId.wire() }
        assertEquals(listOf("b1", "b2"), events.map { it.eventId })
        assertNull(events[0].subtype)
        assertEquals(Subtype.Silent.TYPE0, events[1].subtype)
    }
}
