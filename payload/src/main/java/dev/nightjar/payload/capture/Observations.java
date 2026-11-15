package dev.nightjar.payload.capture;

import dev.nightjar.payload.env.Worker;
import dev.nightjar.payload.store.EventQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The only way a hook thread reaches the pipeline: every call allocates one
 * {@link Observation}, offers it to the bounded MPSC queue and, when no drain is already scheduled,
 * posts one. No locks, no I/O, no JSON on the hook thread.
 *
 * <p>Thread: any.
 */
public final class Observations {
    private final EventQueue<Observation> queue;
    private final Worker worker;
    private final Runnable drain;
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public Observations(EventQueue<Observation> queue, Worker worker, Runnable drain) {
        this.queue = queue;
        this.worker = worker;
        this.drain = drain;
    }

    /** Enqueues {@code o}; a full queue drops it and counts {@code droppedObservations}. */
    public void offer(Observation o) {
        if (o == null) return;
        queue.offer(o);
        schedule();
    }

    /** Called by the worker when it starts draining; allows the next offer to post again. */
    public void drainStarted() {
        scheduled.set(false);
    }

    /** Called by the worker at the end of a drain; re-posts if producers raced the drain. */
    public void drainFinished() {
        if (!queue.isEmpty() && scheduled.compareAndSet(false, true)) {
            worker.post(drain);
        }
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) {
            worker.post(drain);
        }
    }

    public long dropped() {
        return queue.dropped();
    }
}
