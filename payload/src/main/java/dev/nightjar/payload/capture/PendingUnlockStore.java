package dev.nightjar.payload.capture;

import java.util.List;

/**
 * Payload-private pending-unlock spool.
 *
 * <p>The implementation lives in {@code dev.nightjar.payload.ipc.PendingUnlockSpool}; the
 * correlator only sees this seam so it can be unit-tested with an in-memory fake and so there is
 * exactly one spool writer.
 *
 * <p>All methods are called on the worker thread. Any exception means "spool unavailable": the
 * caller disables the spool for this boot and continues RAM-only.
 */
public interface PendingUnlockStore {
    /** One spool entry. */
    record Entry(
            String key,
            String eventId,
            int revision,
            long createdAtWall,
            String predictedClass,
            String eventJson) {}

    /** Adds or replaces the entry for {@code entry.eventId()}. */
    void put(Entry entry);

    /** Removes the entry for this event and revision. */
    void remove(String eventId, int revision);

    /** All readable entries, oldest first; a corrupt file yields an empty list. */
    List<Entry> load();

    /** Store that keeps nothing (RAM-only mode, tests). */
    PendingUnlockStore NONE = new PendingUnlockStore() {
        @Override
        public void put(Entry entry) {
            // RAM-only mode.
        }

        @Override
        public void remove(String eventId, int revision) {
            // RAM-only mode.
        }

        @Override
        public List<Entry> load() {
            return List.of();
        }
    };
}
