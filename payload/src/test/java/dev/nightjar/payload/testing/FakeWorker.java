package dev.nightjar.payload.testing;

import dev.nightjar.payload.env.Worker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Deterministic single-thread worker: {@link #runAll()} executes what was posted. */
public final class FakeWorker implements Worker {
    private final ArrayDeque<Runnable> immediate = new ArrayDeque<>();
    private final List<Runnable> delayed = new ArrayList<>();

    @Override
    public void post(Runnable r) {
        if (r != null) immediate.add(r);
    }

    @Override
    public void postDelayed(Runnable r, long delayMs) {
        if (r != null) delayed.add(r);
    }

    @Override
    public void cancel(Runnable r) {
        delayed.remove(r);
        immediate.remove(r);
    }

    @Override
    public boolean isWorkerThread() {
        return true;
    }

    /** Runs every queued task, including tasks queued while running. */
    public int runAll() {
        int n = 0;
        while (!immediate.isEmpty() && n < 10_000) {
            Runnable r = immediate.poll();
            if (r != null) {
                r.run();
                n++;
            }
        }
        return n;
    }

    public int pending() {
        return immediate.size();
    }
}
