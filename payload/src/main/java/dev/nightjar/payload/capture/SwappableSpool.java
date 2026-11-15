package dev.nightjar.payload.capture;

import java.util.List;

/**
 * A {@link PendingUnlockStore} whose target can be installed later. The IPC layer owns the
 * real spool ({@code ipc.PendingUnlockSpool}) and installs it after the handshake; until then — and
 * when the spool is disabled for this boot — everything goes to {@link PendingUnlockStore#NONE}.
 *
 * <p>Thread: {@link #set} on the worker; the other methods delegate.
 */
public final class SwappableSpool implements PendingUnlockStore {
    private volatile PendingUnlockStore target = PendingUnlockStore.NONE;

    public void set(PendingUnlockStore store) {
        target = store == null ? PendingUnlockStore.NONE : store;
    }

    @Override
    public void put(Entry entry) {
        target.put(entry);
    }

    @Override
    public void remove(String eventId, int revision) {
        target.remove(eventId, revision);
    }

    @Override
    public List<Entry> load() {
        return target.load();
    }
}
