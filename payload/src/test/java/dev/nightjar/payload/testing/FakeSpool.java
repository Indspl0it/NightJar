package dev.nightjar.payload.testing;

import dev.nightjar.payload.capture.PendingUnlockStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory spool for the pending-unlock tests. */
public final class FakeSpool implements PendingUnlockStore {
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    public boolean failWrites;
    public boolean failReads;
    public int writes;
    public int removes;

    @Override
    public void put(Entry entry) {
        if (failWrites) throw new IllegalStateException("io-error");
        writes++;
        entries.put(entry.eventId() + ":" + entry.revision(), entry);
    }

    @Override
    public void remove(String eventId, int revision) {
        if (failWrites) throw new IllegalStateException("io-error");
        removes++;
        entries.remove(eventId + ":" + revision);
    }

    @Override
    public List<Entry> load() {
        if (failReads) throw new IllegalStateException("corrupt");
        return new ArrayList<>(entries.values());
    }

    public int size() {
        return entries.size();
    }

    /** Simulates a spool file written by a previous process. */
    public void seed(Entry entry) {
        entries.put(entry.eventId() + ":" + entry.revision(), entry);
    }
}
