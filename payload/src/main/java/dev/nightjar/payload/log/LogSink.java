package dev.nightjar.payload.log;

/**
 * Destination for L1 lines that also go to logcat. The payload only forwards W and E lines;
 * the sink never receives I or D. Thread: any.
 */
public interface LogSink {
    /** Writes one already-formatted line. {@code level} is {@code "W"} or {@code "E"}. */
    void write(String level, String tag, String line);

    /** Sink that discards everything (tests, and the release build before logcat is wanted). */
    LogSink NONE = (level, tag, line) -> {};
}
