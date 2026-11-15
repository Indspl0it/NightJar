package dev.nightjar.payload.testing;

import dev.nightjar.payload.env.Clock;

/** Controllable clock for the JVM tests. */
public final class FakeClock implements Clock {
    private long elapsedNs;
    private long wallMs = 1_762_330_931_204L;

    public FakeClock(long startElapsedNs) {
        this.elapsedNs = startElapsedNs;
    }

    @Override
    public long elapsedNs() {
        return elapsedNs;
    }

    @Override
    public long wallMs() {
        return wallMs;
    }

    public void advanceMs(long ms) {
        elapsedNs += ms * 1_000_000L;
        wallMs += ms;
    }

    public void advanceNs(long ns) {
        elapsedNs += ns;
        wallMs += ns / 1_000_000L;
    }

    public void setWall(long ms) {
        wallMs = ms;
    }
}
