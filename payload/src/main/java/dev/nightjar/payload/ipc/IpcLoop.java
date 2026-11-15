package dev.nightjar.payload.ipc;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

/**
 * Drives {@link IpcWorker} on its own background {@code NightjarIpc} thread, apart from the payload worker, so a slow
 * app call never delays the capture.
 *
 * <p>The loop sleeps for {@link IpcWorker#nextDelayMs()} and wakes immediately on {@link #kick()}, which the sealer
 * calls after sealing an event. Never touches the phone main looper.
 *
 * <p>Device only (uses {@link HandlerThread}).
 */
public final class IpcLoop {

    private static final String THREAD_NAME = "NightjarIpc";

    private final IpcWorker worker;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                worker.tick();
            } catch (Throwable ignored) {
                // An exception out of the Runnable would kill the HandlerThread and com.android.phone with it.
            }
            schedule();
        }
    };

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;

    public IpcLoop(IpcWorker worker) {
        this.worker = worker;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(tick);
    }

    /** Wakes the loop now (a new event was sealed, the app died, or the control binder changed). */
    public void kick() {
        Handler h = handler;
        if (h == null || !running) return;
        h.removeCallbacks(tick);
        h.post(tick);
    }

    public void stop() {
        running = false;
        Handler h = handler;
        if (h != null) h.removeCallbacks(tick);
        HandlerThread t = thread;
        if (t != null) t.quitSafely();
        handler = null;
        thread = null;
    }

    private void schedule() {
        Handler h = handler;
        if (h == null || !running) return;
        long delay;
        try {
            delay = worker.nextDelayMs();
        } catch (Throwable t) {
            // An exception here would propagate out of the Runnable and kill the
            // HandlerThread — and with it com.android.phone. Fall back to the idle cadence.
            delay = 1_000L;
        }
        h.postDelayed(tick, Math.max(0L, delay));
    }
}
