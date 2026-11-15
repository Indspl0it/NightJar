package dev.nightjar.payload.log;

import dev.nightjar.contract.Masker;
import dev.nightjar.payload.env.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * L1 payload log: a RAM ring of 2,000 lines with a monotonically increasing {@code seq},
 * ISO8601-ms lines, logcat only for W/E, D gated by
 * {@code settings.debugLogging.until} (S03), and "first + every 10th" rate limiting for hook
 * and IPC errors (S04).
 *
 * <p><b>Masking:</b> every value that could be a phone number or message text must
 * be passed through {@link #num(String)} or {@link #text(CharSequence)}. Those wrap the value so the
 * formatter masks it; a raw value is written verbatim and must therefore be an identifier, a count,
 * a class/fate name or a package name.
 *
 * <p>Thread: any. The ring is lock-free (an atomic sequence and an atomic slot array), so a hook thread that
 * writes a line never waits on a reader or on another writer. A write still allocates the
 * formatted line, so hook threads log only through the rate-limited {@code *Limited} helpers or on
 * an error path.
 */
public final class PLog implements dev.nightjar.payload.ipc.LogSource {
    /** Ring capacity (table: 2, 000 lines). */
    public static final int CAPACITY = 2000;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final Clock clock;
    private final LogSink sink;
    private final AtomicReferenceArray<LogLine> ring = new AtomicReferenceArray<>(CAPACITY);
    private final ConcurrentHashMap<String, Integer> rateCounts = new ConcurrentHashMap<>();
    /** Last sequence number handed to a writer. */
    private final AtomicLong seq = new AtomicLong();
    /** Last sequence number whose line, and every line before it, is in the ring. */
    private final AtomicLong published = new AtomicLong();
    private volatile long debugUntilMs;

    public PLog(Clock clock, LogSink sink) {
        this.clock = clock;
        this.sink = sink == null ? LogSink.NONE : sink;
    }

    /** Enables D lines until {@code untilWallMs}. */
    public void setDebugUntil(long untilWallMs) {
        this.debugUntilMs = untilWallMs;
    }

    public boolean debugEnabled() {
        return debugUntilMs > 0 && clock.wallMs() < debugUntilMs;
    }

    // ------------------------------------------------------------------ levels

    public void e(String tag, String msg, Object... kv) {
        write("E", tag, msg, kv);
    }

    public void w(String tag, String msg, Object... kv) {
        write("W", tag, msg, kv);
    }

    public void i(String tag, String msg, Object... kv) {
        write("I", tag, msg, kv);
    }

    public void d(String tag, String msg, Object... kv) {
        if (debugEnabled()) write("D", tag, msg, kv);
    }

    /**
     * W line rate-limited per {@code key} to the first and every 10th occurrence.
     * Returns true when the line was written.
     */
    public boolean wLimited(String key, String tag, String msg, Object... kv) {
        if (!shouldEmit(key)) return false;
        write("W", tag, msg, kv);
        return true;
    }

    /** E line rate-limited per {@code key} to the first and every 10th occurrence. */
    public boolean eLimited(String key, String tag, String msg, Object... kv) {
        if (!shouldEmit(key)) return false;
        write("E", tag, msg, kv);
        return true;
    }

    private boolean shouldEmit(String key) {
        Integer n = rateCounts.merge(key, 1, Integer::sum);
        return n == 1 || n % 10 == 0;
    }

    /** Clears rate-limit counters (new session). */
    public void resetRateLimits() {
        rateCounts.clear();
    }

    // ------------------------------------------------------------------ masked values

    /** A value that must be masked as a phone number. */
    public static Object num(String value) {
        return new Masked(Masker.maskNumber(value));
    }

    /** A value that must be masked as message text. */
    public static Object text(CharSequence value) {
        return new Masked(Masker.maskText(value));
    }

    private static final class Masked {
        final String rendered;

        Masked(String rendered) {
            this.rendered = rendered;
        }

        @Override
        public String toString() {
            return rendered;
        }
    }

    // ------------------------------------------------------------------ read

    public long latestSeq() {
        return published.get();
    }

    /** Lines with {@code seq > sinceSeq}, oldest first. */
    public List<LogLine> lines(long sinceSeq) {
        long hi = published.get();
        long lo = Math.max(sinceSeq + 1, hi - CAPACITY + 1);
        List<LogLine> out = new ArrayList<>((int) Math.max(0, Math.min(hi - lo + 1, 64)));
        for (long n = Math.max(1, lo); n <= hi; n++) {
            LogLine l = ring.get(index(n));
            // A slot that a newer writer already overwrote holds another seq: that line is gone.
            if (l != null && l.seq() == n) out.add(l);
        }
        return out;
    }

    /** {@code getLog} payload (C.10): JSON array of {@code {seq, t, level, tag, msg}}. */
    public String linesJson(long sinceSeq) {
        return toJson(lines(sinceSeq));
    }

    /** {@code getLog} pull: E-level lines with {@code since < seq <= upTo}. */
    @Override
    public String errorLinesJson(long sinceSeq, long upToSeq) {
        List<LogLine> picked = new ArrayList<>();
        for (LogLine l : lines(sinceSeq)) {
            if (l.seq() > upToSeq) break;
            if ("E".equals(l.level())) picked.add(l);
        }
        return toJson(picked);
    }

    /** Push batch: every level, the newest {@code max} lines with {@code since < seq <= upTo}. */
    @Override
    public String linesJson(long sinceSeq, long upToSeq, int max) {
        List<LogLine> picked = new ArrayList<>();
        for (LogLine l : lines(sinceSeq)) {
            if (l.seq() > upToSeq) break;
            picked.add(l);
        }
        if (picked.size() > max) picked = picked.subList(picked.size() - max, picked.size());
        return toJson(picked);
    }

    private static String toJson(List<LogLine> lines) {
        List<Object> arr = new ArrayList<>(lines.size());
        for (LogLine l : lines) {
            arr.add(new dev.nightjar.payload.JsonBuilder()
                    .put("seq", l.seq())
                    .put("t", l.t())
                    .put("level", l.level())
                    .put("tag", l.tag())
                    .put("msg", l.message())
                    .map());
        }
        return dev.nightjar.payload.Json.write(arr);
    }

    // ------------------------------------------------------------------ internals

    private void write(String level, String tag, String msg, Object... kv) {
        long t = clock.wallMs();
        String line = format(level, tag, msg, kv, t);
        long n = seq.incrementAndGet();
        ring.set(index(n), new LogLine(n, t, level, tag, line));
        publish();
        if ("W".equals(level) || "E".equals(level)) {
            try {
                sink.write(level, tag, line);
            } catch (Throwable ignored) {
                // Logcat is best effort; never break the caller.
            }
        }
    }

    /**
     * Moves {@link #published} over every line that is now in the ring. A writer that took a sequence number but has
     * not stored its line yet stops the move; that writer moves it on when it calls this method itself.
     */
    private void publish() {
        while (true) {
            long p = published.get();
            LogLine next = ring.get(index(p + 1));
            if (next == null || next.seq() != p + 1) return;
            published.compareAndSet(p, p + 1);
        }
    }

    private static int index(long n) {
        return (int) ((n - 1) % CAPACITY);
    }

    /** {@code ISO8601-ms LEVEL tag message} followed by {@code key=value} pairs. */
    static String format(String level, String tag, String msg, Object[] kv, long wallMs) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault()).format(TS))
                .append("  ")
                .append(level)
                .append("  ")
                .append(tag)
                .append("  ")
                .append(msg == null ? "" : msg);
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                sb.append(' ').append(kv[i]).append('=');
                Object v = kv[i + 1];
                sb.append(v == null ? "null" : v.toString());
            }
        }
        return sb.toString();
    }
}
