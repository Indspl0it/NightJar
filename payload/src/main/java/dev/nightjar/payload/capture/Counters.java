package dev.nightjar.payload.capture;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normal-SMS counters. A {@code CL-NORMAL} + {@code FT-SHOWN} event is
 * replaced by a counter increment; its PDUs never enter the ring buffer and are dropped with the
 * event.
 *
 * <p>Counters are aggregated in RAM. The IPC worker sends them through the {@code counters} method at most every
 * {@code IpcWorker.COUNTERS_INTERVAL_MS}.
 *
 * <p><b>Deltas only.</b> The app adds the numbers it receives
 * ({@code ON CONFLICT … SET n = n + excluded.n}), so a cumulative total would inflate quadratically.
 * {@link #drainJson()} therefore returns only what has not been sent yet and clears the map;
 * {@link #unsent()} merges that snapshot back when the call failed. A drain that got neither {@link #delivered()} nor
 * {@link #unsent()} (the call threw) is merged back by the next {@link #drainJson()}, so no delta is lost.
 *
 * <p>Thread: worker only, except the IPC thread's snapshot reads; every method is synchronized.
 */
public final class Counters {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Aggregation key: day, subscription, direction, class. */
    public record Key(String day, int subId, Direction direction, ClassId classId) {}

    private final Map<Key, Long> counts = new LinkedHashMap<>();
    private Map<Key, Long> inFlight = Map.of();

    /** Increments the counter for the event's day (local time). */
    public synchronized void increment(long wallMs, int subId, Direction direction, ClassId classId) {
        if (classId == null) return;
        String day = Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault()).format(DAY);
        Key k = new Key(day, subId, direction, classId);
        counts.merge(k, 1L, Long::sum);
    }

    /** The unsent counters as the {@code counters} IPC payload, without draining them; for tests. */
    synchronized String countersJson() {
        return toJson(counts);
    }

    /**
     * Takes the unsent counters out of the map and returns their payload.
     *
     * <p>The caller sends them; a failed call must call {@link #unsent()} so the delta is not lost.
     */
    public synchronized String drainJson() {
        unsent(); // a previous drain that was never settled
        if (counts.isEmpty()) return "[]";
        String json = toJson(counts);
        inFlight = new LinkedHashMap<>(counts);
        counts.clear();
        return json;
    }

    /** Merges the last drained delta back after a failed call. */
    public synchronized void unsent() {
        if (inFlight.isEmpty()) return;
        for (Map.Entry<Key, Long> e : inFlight.entrySet()) {
            counts.merge(e.getKey(), e.getValue(), Long::sum);
        }
        inFlight = Map.of();
    }

    /** The last drained delta was delivered; drop the snapshot. */
    public synchronized void delivered() {
        inFlight = Map.of();
    }

    public synchronized int size() {
        return counts.size();
    }

    public synchronized long total() {
        long n = 0;
        for (long v : counts.values()) n += v;
        return n;
    }

    private static String toJson(Map<Key, Long> map) {
        java.util.List<Object> arr = new java.util.ArrayList<>();
        for (Map.Entry<Key, Long> e : map.entrySet()) {
            Key k = e.getKey();
            arr.add(new dev.nightjar.payload.JsonBuilder()
                    .put("day", k.day())
                    .put("subId", k.subId())
                    .put("direction", k.direction().wire())
                    .put("classId", k.classId().wire())
                    .put("n", e.getValue())
                    .map());
        }
        return dev.nightjar.payload.Json.write(arr);
    }
}
