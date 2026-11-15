package dev.nightjar.payload.store;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded lock-free MPSC queue for hook observations.
 *
 * <p>Any number of hook threads {@link #offer} without blocking; the single worker thread
 * {@link #poll}s. Overflow is not an error: the observation is dropped and {@link #dropped()}
 * increments, which the health report exposes as {@code droppedObservations}.
 *
 * <p>Thread: producers any, consumer one.
 *
 * @param <T> observation type
 */
public final class EventQueue<T> {
    /** Default capacity. */
    public static final int DEFAULT_CAPACITY = 4096;

    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final int capacity;

    public EventQueue() {
        this(DEFAULT_CAPACITY);
    }

    public EventQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity <= 0");
        this.capacity = capacity;
    }

    /** Enqueues; returns false (and counts a drop) when the queue is full. Never blocks. */
    public boolean offer(T value) {
        if (value == null) return false;
        while (true) {
            int c = count.get();
            if (c >= capacity) {
                dropped.incrementAndGet();
                return false;
            }
            if (count.compareAndSet(c, c + 1)) break;
        }
        queue.offer(value);
        return true;
    }

    /** Removes the head, or returns null when empty. Worker thread only. */
    public T poll() {
        T v = queue.poll();
        if (v != null) count.decrementAndGet();
        return v;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Approximate: includes slots reserved by producers that have not enqueued yet. */
    public int size() {
        return count.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public int capacity() {
        return capacity;
    }
}
