package dev.nightjar.payload.ipc;

import dev.nightjar.contract.Ipc;
import dev.nightjar.payload.store.OutboundEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits sealed events into {@code events} batches: at most {@link Ipc#MAX_BATCH_EVENTS} events and
 * {@link Ipc#MAX_BATCH_BYTES} bytes of serialized JSON.
 *
 * <p>The event JSON is produced once by the sealer, so a batch is built by joining the strings — no re-serialisation
 * and no per-field allocation. Sizes are measured in UTF-8 bytes without creating an intermediate array.
 *
 * <p>An event that alone exceeds the byte limit is still sent, alone: dropping it would lose the message, and the app
 * rejects only a malformed batch. The caller logs a W when {@link Batch#oversized()} is true.
 *
 * <p>Thread: IPC worker only.
 */
public final class BatchBuilder {

    private final int maxEvents;
    private final int maxBytes;

    public BatchBuilder() {
        this(Ipc.MAX_BATCH_EVENTS, Ipc.MAX_BATCH_BYTES);
    }

    public BatchBuilder(int maxEvents, int maxBytes) {
        if (maxEvents <= 0) throw new IllegalArgumentException("maxEvents <= 0");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes <= 0");
        this.maxEvents = maxEvents;
        this.maxBytes = maxBytes;
    }

    /**
     * Builds the largest batch that fits, from the front of {@code candidates}.
     *
     * @param candidates events in queue order; {@code null} entries are skipped
     */
    public Batch build(List<OutboundEvent> candidates) {
        if (candidates == null || candidates.isEmpty()) return Batch.EMPTY;
        List<OutboundEvent> chosen = new ArrayList<>(Math.min(maxEvents, candidates.size()));
        StringBuilder json = new StringBuilder(256);
        json.append('[');
        int bytes = 2; // [ and ]
        boolean oversized = false;
        for (OutboundEvent event : candidates) {
            if (event == null) continue;
            if (chosen.size() >= maxEvents) break;
            String body = event.json();
            if (body == null || body.isEmpty()) continue;
            int bodyBytes = utf8Length(body);
            boolean first = chosen.isEmpty();
            int added = bodyBytes + (first ? 0 : 1);
            if (!first && bytes + added > maxBytes) break;
            if (first && bytes + added > maxBytes) oversized = true;
            if (!first) json.append(',');
            json.append(body);
            bytes += added;
            chosen.add(event);
        }
        json.append(']');
        if (chosen.isEmpty()) return Batch.EMPTY;
        return new Batch(json.toString(), chosen, bytes, oversized);
    }

    /** UTF-8 length of {@code s} without allocating the encoded array. */
    static int utf8Length(String s) {
        return OutboundEvent.utf8Length(s);
    }

    public int maxEvents() {
        return maxEvents;
    }

    public int maxBytes() {
        return maxBytes;
    }

    /** One built batch. */
    public static final class Batch {
        static final Batch EMPTY = new Batch("[]", List.of(), 2, false);

        private final String json;
        private final List<OutboundEvent> events;
        private final int bytes;
        private final boolean oversized;

        Batch(String json, List<OutboundEvent> events, int bytes, boolean oversized) {
            this.json = json;
            this.events = Collections.unmodifiableList(events);
            this.bytes = bytes;
            this.oversized = oversized;
        }

        /** JSON array for the {@code batch} extra. */
        public String json() {
            return json;
        }

        /** The events included, in order. */
        public List<OutboundEvent> events() {
            return events;
        }

        /** Serialized size in UTF-8 bytes, including the brackets. */
        public int bytes() {
            return bytes;
        }

        /** True when the only event in the batch alone exceeds {@link Ipc#MAX_BATCH_BYTES}. */
        public boolean oversized() {
            return oversized;
        }

        public boolean isEmpty() {
            return events.isEmpty();
        }

        public int size() {
            return events.size();
        }
    }
}
