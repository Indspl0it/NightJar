package dev.nightjar.payload.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.Ipc;
import dev.nightjar.payload.store.OutboundEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Batch splitting, ack parsing and backoff. */
class BatchBuilderTest {

    private static OutboundEvent event(String id, int revision, int jsonBytes) {
        StringBuilder b = new StringBuilder(jsonBytes);
        b.append('{');
        while (b.length() < jsonBytes - 1) b.append('x');
        b.append('}');
        return new OutboundEvent(id, revision, b.toString(), jsonBytes);
    }

    @Test
    void IPC_03_splitsAtFiftyEvents() {
        BatchBuilder builder = new BatchBuilder();
        List<OutboundEvent> events = new ArrayList<>();
        for (int i = 0; i < 120; i++) events.add(event("e" + i, 1, 20));
        BatchBuilder.Batch batch = builder.build(events);
        assertEquals(Ipc.MAX_BATCH_EVENTS, batch.size());
        assertEquals("e0", batch.events().get(0).eventId());
        assertEquals("e49", batch.events().get(49).eventId());
        assertTrue(batch.json().startsWith("[{"));
        assertTrue(batch.json().endsWith("}]"));
        assertFalse(batch.oversized());
    }

    @Test
    void IPC_03_splitsAtTheByteLimit() {
        BatchBuilder builder = new BatchBuilder(Ipc.MAX_BATCH_EVENTS, 1_000);
        List<OutboundEvent> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) events.add(event("e" + i, 1, 100));
        BatchBuilder.Batch batch = builder.build(events);
        assertTrue(batch.bytes() <= 1_000);
        assertTrue(batch.size() < 20);
        assertTrue(batch.size() >= 9);
    }

    @Test
    void IPC_03_aSingleOversizeEventIsStillSentAlone() {
        BatchBuilder builder = new BatchBuilder(Ipc.MAX_BATCH_EVENTS, 100);
        List<OutboundEvent> events = List.of(event("big", 1, 500), event("small", 1, 10));
        BatchBuilder.Batch batch = builder.build(events);
        assertEquals(1, batch.size());
        assertEquals("big", batch.events().get(0).eventId());
        assertTrue(batch.oversized());
    }

    @Test
    void IPC_03_emptyInputYieldsAnEmptyBatch() {
        assertTrue(new BatchBuilder().build(List.of()).isEmpty());
        assertTrue(new BatchBuilder().build(null).isEmpty());
        assertEquals("[]", new BatchBuilder().build(List.of()).json());
    }

    @Test
    void IPC_03_utf8LengthCountsBytesNotChars() {
        assertEquals(3, BatchBuilder.utf8Length("abc"));
        assertEquals(2, BatchBuilder.utf8Length("é"));
        assertEquals(3, BatchBuilder.utf8Length("€"));
        assertEquals(4, BatchBuilder.utf8Length("😀"));
    }

    @Test
    void IPC_03_ackSetAcceptsOnlyWellFormedEntries() {
        AckSet acked = AckSet.parse("[\"e1:1\",\"e2:3\",\"bad\",\"e3:x\",\"e4:2\"]");
        assertTrue(acked.acked("e1", 1));
        assertTrue(acked.acked("e2", 3));
        assertFalse(acked.acked("e1", 2));
        assertFalse(acked.acked("e3", 1));
        assertEquals(3, acked.size());
    }

    @Test
    void IPC_03_ackSetIsEmptyForMalformedInput() {
        assertTrue(AckSet.parse(null).isEmpty());
        assertTrue(AckSet.parse("").isEmpty());
        assertTrue(AckSet.parse("not json").isEmpty());
        assertTrue(AckSet.parse("{}").isEmpty());
        assertTrue(AckSet.parse("[\"no-revision\"]").isEmpty());
    }

    @Test
    void IPC_06_backoffDoublesAndCaps() {
        Backoff backoff = new Backoff();
        assertEquals(1_000, backoff.onFailure());
        assertEquals(2_000, backoff.onFailure());
        assertEquals(4_000, backoff.onFailure());
        assertEquals(8_000, backoff.onFailure());
        assertEquals(16_000, backoff.onFailure());
        assertEquals(32_000, backoff.onFailure());
        assertEquals(60_000, backoff.onFailure());
        assertEquals(60_000, backoff.onFailure());
        assertEquals(8, backoff.failures());
        backoff.onSuccess();
        assertEquals(0, backoff.failures());
        assertEquals(1_000, backoff.onFailure());
    }
}
