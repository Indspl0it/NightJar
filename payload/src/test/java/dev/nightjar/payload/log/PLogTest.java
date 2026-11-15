package dev.nightjar.payload.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.payload.testing.FakeClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L1 log format, masking, level gating, logcat policy and rate limiting. */
class PLogTest {

    /** Records what reached logcat. */
    static final class Sink implements LogSink {
        final List<String> lines = new ArrayList<>();
        final List<String> levels = new ArrayList<>();

        @Override
        public void write(String level, String tag, String line) {
            levels.add(level);
            lines.add(line);
        }
    }

    @Test
    void formatMatchesLog01() {
        FakeClock clock = new FakeClock(0);
        Sink sink = new Sink();
        PLog log = new PLog(clock, sink);
        log.i("hook", "hooks installed", "live", 12);
        String line = log.lines(0).get(0).message();
        assertTrue(line.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2}  I  hook  hooks installed live=12"),
                line);
    }

    @Test
    void numbersAndTextAreMasked() {
        PLog log = new PLog(new FakeClock(0), LogSink.NONE);
        log.i("event", "captured", "from", PLog.num("+919000000123"), "body", PLog.text("UNDERTEXT-MARKER-TEXT")); // mask-ok: asserts that PLog.num/text mask these values
        String line = log.lines(0).get(0).message();
        assertFalse(line.contains("9000000123"), line);
        assertFalse(line.contains("UNDERTEXT-MARKER-TEXT"), line);
        assertTrue(line.contains("«21 chars»"), line);
    }

    @Test
    void onlyWarningsAndErrorsReachTheSink() {
        Sink sink = new Sink();
        PLog log = new PLog(new FakeClock(0), sink);
        log.d("a", "debug");
        log.i("a", "info");
        log.w("a", "warn");
        log.e("a", "error");
        assertEquals(List.of("W", "E"), sink.levels);
    }

    @Test
    void debugNeedsTheSettingWindow() {
        FakeClock clock = new FakeClock(0);
        PLog log = new PLog(clock, LogSink.NONE);
        log.d("a", "off");
        assertEquals(0, log.lines(0).size());
        log.setDebugUntil(clock.wallMs() + 1000);
        log.d("a", "on");
        assertEquals(1, log.lines(0).size());
        clock.advanceMs(2000);
        log.d("a", "expired");
        assertEquals(1, log.lines(0).size());
        assertFalse(log.debugEnabled());
    }

    @Test
    void hookErrorsAreRateLimitedToFirstAndEveryTenth() {
        PLog log = new PLog(new FakeClock(0), LogSink.NONE);
        int written = 0;
        for (int i = 0; i < 30; i++) if (log.wLimited("HK-06", "hook", "hook error")) written++;
        assertEquals(4, written, "occurrences 1, 10, 20, 30");
        assertEquals(4, log.lines(0).size());
    }

    @Test
    void ringKeepsTheLastTwoThousandLines() {
        PLog log = new PLog(new FakeClock(0), LogSink.NONE);
        for (int i = 0; i < PLog.CAPACITY + 50; i++) log.i("t", "line " + i);
        assertEquals(PLog.CAPACITY, log.lines(0).size());
        assertTrue(log.lines(0).get(0).message().endsWith("  line 50"), log.lines(0).get(0).message());
        assertEquals(PLog.CAPACITY + 50L, log.latestSeq());
    }

    @Test
    void linesSinceSeqReturnsOnlyNewerLines() {
        PLog log = new PLog(new FakeClock(0), LogSink.NONE);
        log.i("t", "one");
        log.i("t", "two");
        long seq = log.latestSeq();
        log.i("t", "three");
        assertEquals(1, log.lines(seq).size());
        assertTrue(log.linesJson(seq).contains("three"));
    }
}
