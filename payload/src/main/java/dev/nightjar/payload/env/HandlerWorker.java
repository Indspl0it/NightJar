package dev.nightjar.payload.env;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * {@link Worker} over a {@code HandlerThread}, {@code "Nightjar"} by default. The
 * thread starts in the constructor; the looper lives for the process lifetime. Thread: any.
 */
public final class HandlerWorker implements Worker {
    private final HandlerThread thread;
    private final Handler handler;

    public HandlerWorker() {
        this("Nightjar");
    }

    /** A worker on its own thread named {@code name} (for example the self-test thread). */
    public HandlerWorker(String name) {
        thread = new HandlerThread(name);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public void post(Runnable r) {
        handler.post(r);
    }

    @Override
    public void postDelayed(Runnable r, long delayMs) {
        handler.postDelayed(r, Math.max(0L, delayMs));
    }

    @Override
    public void cancel(Runnable r) {
        handler.removeCallbacks(r);
    }

    @Override
    public boolean isWorkerThread() {
        return Thread.currentThread() == thread;
    }

    /** Stops the looper. Used when the payload gives up (capture tier failure). */
    public void quit() {
        thread.quitSafely();
    }
}
