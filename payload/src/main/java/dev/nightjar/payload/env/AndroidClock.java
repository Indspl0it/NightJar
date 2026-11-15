package dev.nightjar.payload.env;

import android.os.SystemClock;

/** {@link Clock} over the framework clocks. Thread: any. */
public final class AndroidClock implements Clock {
    public static final AndroidClock INSTANCE = new AndroidClock();

    private AndroidClock() {}

    @Override
    public long elapsedNs() {
        return SystemClock.elapsedRealtimeNanos();
    }

    @Override
    public long wallMs() {
        return System.currentTimeMillis();
    }
}
