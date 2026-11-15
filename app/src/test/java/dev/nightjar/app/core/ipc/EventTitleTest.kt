package dev.nightjar.app.core.ipc

import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.app.domain.text.MapStrings
import dev.nightjar.app.domain.text.Templates
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Defect: titles stored at ingest kept their raw `{port}` / `{channel}` / `{contentType}` placeholders. */
class EventTitleTest {
    private val explainer = Explainer(MapStrings())

    private fun title(json: String): String {
        val event = EventJson.parseEvent(JSONObject(json)) { fields -> explainer.title(EventJson.factsFor(fields)) }
        return event!!.title
    }

    @Test
    fun `a known port title names the type of the port`() {
        // The title is the type name alone. The Detail screen shows the port.
        val t = title("""{"eventId":"e1","finalClass":"CL-DATA-PORT","destPort":9200,"originator":"+15550000000"}""")
        assertEquals("WAP connectionless session", t)
    }

    @Test
    fun `an unknown port title gives the port number`() {
        val t = title("""{"eventId":"e1","finalClass":"CL-DATA-PORT","destPort":16001,"originator":"+15550000000"}""")
        assertEquals("Data message to port 16001", t)
    }

    @Test
    fun `wap other title carries the content type`() {
        val t = title("""{"eventId":"e2","finalClass":"CL-WAP-OTHER","contentType":"application/x-test"}""")
        assertEquals("Push message (application/x-test)", t)
    }

    @Test
    fun `cell broadcast titles carry the category or the channel`() {
        val area = title("""{"eventId":"e3","finalClass":"CL-CB","cb":{"messageId":50,"emergency":false}}""")
        val other = title("""{"eventId":"e5","finalClass":"CL-CB","cb":{"messageId":221,"emergency":false}}""")
        val emergency = title("""{"eventId":"e4","finalClass":"CL-CB","cb":{"messageId":4370,"emergency":true}}""")
        assertEquals("Area information (channel 50)", area)
        assertEquals("Cell broadcast (channel 221)", other)
        assertEquals("Emergency alert: presidential", emergency)
    }

    @Test
    fun `no class leaves a placeholder in its ingest title`() {
        for (classId in dev.nightjar.contract.ClassId.entries) {
            val t = title("""{"eventId":"x","finalClass":"${classId.wire()}","direction":"OUT"}""")
            assertTrue(Templates.unfilled(t).isEmpty(), "$classId: $t")
        }
    }
}
