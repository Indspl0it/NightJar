package dev.nightjar.payload.hook;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Ring of the last 1,000 hook durations with p50/p99. Enabled in debug and perf
 * builds; the probe is cheap enough to leave on (one {@code nanoTime} pair per invocation).
 *
 * <p>The ring is lock-free: {@link #record} runs on the phone main looper and on HAL binder threads,
 * where the payload must never take a lock (a sort under the same monitor would block them). Sorting
 * happens in the reader on a copy. Without a lock the percentiles are approximate: a reader can see a
 * slot that a writer has claimed but not yet written. The count never passes {@link #CAPACITY}.
 *
 * <p>Thread: any.
 */
public final class LatencyProbe {
    public static final int CAPACITY = 1000;

    private final AtomicLongArray ring = new AtomicLongArray(CAPACITY);
    private final AtomicInteger next = new AtomicInteger();
    private final AtomicInteger size = new AtomicInteger();

    /** Records one duration in nanoseconds. */
    public void record(long ns) {
        int slot = next.getAndUpdate(i -> (i + 1) % CAPACITY);
        ring.set(slot, ns);
        size.getAndUpdate(n -> n < CAPACITY ? n + 1 : n);
    }

    public int count() {
        return size.get();
    }

    /** Median in ns, 0 when empty. */
    public long p50() {
        return percentile(50);
    }

    /** 99th percentile in ns, 0 when empty. */
    public long p99() {
        return percentile(99);
    }

    private long percentile(int p) {
        int n = Math.min(size.get(), CAPACITY);
        if (n == 0) return 0L;
        long[] copy = new long[n];
        for (int i = 0; i < n; i++) copy[i] = ring.get(i);
        Arrays.sort(copy);
        int idx = (int) Math.ceil(p / 100.0 * n) - 1;
        if (idx < 0) idx = 0;
        if (idx >= n) idx = n - 1;
        return copy[idx];
    }

    public void clear() {
        next.set(0);
        size.set(0);
        for (int i = 0; i < CAPACITY; i++) ring.set(i, 0L);
    }
}
