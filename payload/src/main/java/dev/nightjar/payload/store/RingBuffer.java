package dev.nightjar.payload.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Ring buffer of sealed events waiting for the app's acknowledgement.
 *
 * <p>Capacity: 1,000 events or 4 MB (the UTF-8 size of each event plus a 512-byte overhead estimate), whichever
 * comes first. On overflow the oldest event is dropped and {@code droppedCount} increments; the drop is
 * reported to the app with the next batch.
 *
 * <p>Order is FIFO so a reconnect resends unacknowledged events in their original order
 * . Thread: the payload worker adds, the IPC thread ({@code NightjarIpc}) peeks and removes, and the
 * health reader may call {@link #droppedCount()} and {@link #pendingCount()}. Every method is synchronized.
 */
public final class RingBuffer {
    public static final int MAX_EVENTS = 1000;
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    /** Per-event estimate added to the serialized size. */
    public static final int EVENT_OVERHEAD_BYTES = 512;

    private final ArrayDeque<OutboundEvent> events = new ArrayDeque<>();
    private final int maxEvents;
    private final int maxBytes;
    private long bytes;
    private long droppedCount;

    public RingBuffer() {
        this(MAX_EVENTS, MAX_BYTES);
    }

    public RingBuffer(int maxEvents, int maxBytes) {
        if (maxEvents <= 0 || maxBytes <= 0) throw new IllegalArgumentException("caps must be > 0");
        this.maxEvents = maxEvents;
        this.maxBytes = maxBytes;
    }

    /**
     * Adds a sealed event, dropping the oldest events until it fits. An event larger than the whole
     * byte cap is still kept (dropping the newest would lose the event being sealed) and the next
     * add drops it. Called on the payload worker.
     */
    public synchronized void add(OutboundEvent event) {
        if (event == null) return;
        int cost = event.bytes() + EVENT_OVERHEAD_BYTES;
        while (!events.isEmpty() && (events.size() >= maxEvents || bytes + cost > maxBytes)) {
            dropOldest();
        }
        events.addLast(event);
        bytes += cost;
    }

    private void dropOldest() {
        OutboundEvent old = events.pollFirst();
        if (old != null) {
            bytes -= old.bytes() + EVENT_OVERHEAD_BYTES;
            droppedCount++;
        }
    }

    /** Oldest unacknowledged events, at most {@code max}. Called on the IPC thread. */
    public synchronized List<OutboundEvent> peek(int max) {
        List<OutboundEvent> out = new ArrayList<>(Math.min(Math.max(max, 0), events.size()));
        int n = 0;
        for (OutboundEvent e : events) {
            if (n++ >= max) break;
            out.add(e);
        }
        return out;
    }

    /**
     * Removes the acknowledged revision. A newer revision of the same event stays queued:
     * the app may have received revision 1 and the payload already produced revision 2.
     *
     * @return true when an entry was removed
     */
    public synchronized boolean remove(String eventId, int revision) {
        if (eventId == null) return false;
        for (java.util.Iterator<OutboundEvent> it = events.iterator(); it.hasNext(); ) {
            OutboundEvent e = it.next();
            if (e.eventId().equals(eventId) && e.revision() == revision) {
                it.remove();
                bytes -= e.bytes() + EVENT_OVERHEAD_BYTES;
                return true;
            }
        }
        return false;
    }

    /** Removes every queued revision of {@code eventId} (e.g. a counter-only replacement). */
    public synchronized int removeAll(String eventId) {
        if (eventId == null) return 0;
        int removed = 0;
        for (java.util.Iterator<OutboundEvent> it = events.iterator(); it.hasNext(); ) {
            OutboundEvent e = it.next();
            if (e.eventId().equals(eventId)) {
                it.remove();
                bytes -= e.bytes() + EVENT_OVERHEAD_BYTES;
                removed++;
            }
        }
        return removed;
    }

    public synchronized int pendingCount() {
        return events.size();
    }

    public synchronized long bytes() {
        return bytes;
    }

    public synchronized long droppedCount() {
        return droppedCount;
    }

    public synchronized void clear() {
        events.clear();
        bytes = 0;
    }
}
