package dev.nightjar.payload.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.nightjar.payload.Json;
import dev.nightjar.payload.JsonBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The payload's own RFC 8259 escaper gives the same bytes on the device and in the JVM tests. */
class JsonWriterTest {

    @Test
    void slashIsNotEscaped() {
        assertEquals("\"application/vnd.wap.sic\"", Json.write("application/vnd.wap.sic"));
    }

    @Test
    void quoteBackslashAndControlCharactersAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001\"", Json.write("a\"b\\c\n\t\u0001"));
        assertEquals("\"\\u2028\"", Json.write("\u2028"));
        assertEquals("\"é€\"", Json.write("é€"));
    }

    @Test
    void mapsListsAndBytesKeepTheirOrderAndForm() {
        String json = Json.write(new JsonBuilder().put("b", List.of(1, "x")).put("a", new byte[] {1, 2}).map());
        assertEquals("{\"a\":\"AQI=\",\"b\":[1,\"x\"]}", json);
        assertEquals("null", Json.write(null));
        assertEquals("null", Json.write(Double.NaN));
    }
}
