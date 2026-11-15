package dev.nightjar.app.core.ipc

import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.app.domain.text.MapStrings
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A sent message keeps its TP-MR and normalised destination so a delivery report can link to it. */
class EventJsonDeliveryTest {
    private val explainer = Explainer(MapStrings())

    private fun parse(json: String) =
        EventJson.parseEvent(JSONObject(json)) { fields -> explainer.title(EventJson.factsFor(fields)) }!!

    @Test
    fun `an outbound event keeps the message reference and the normalised destination`() {
        val event =
            parse("""{"eventId":"o1","finalClass":"CL-MO-DATA","direction":"OUT","messageRef":42,"destination":"+91 90000 00002"}""")
        assertEquals(42, event.tpMr)
        assertEquals("919000000002", event.destinationNorm)
    }

    @Test
    fun `an outbound event without a message reference has no TP-MR`() {
        val event = parse("""{"eventId":"o2","finalClass":"CL-MO-DATA","direction":"OUT","destination":"9000000002"}""")
        assertNull(event.tpMr)
        assertEquals("9000000002", event.destinationNorm)
    }

    @Test
    fun `an inbound event never gets a TP-MR or a destination`() {
        val event = parse("""{"eventId":"i1","finalClass":"CL-SILENT","direction":"IN","messageRef":7,"destination":"9000000002"}""")
        assertNull(event.tpMr)
        assertNull(event.destinationNorm)
    }
}
