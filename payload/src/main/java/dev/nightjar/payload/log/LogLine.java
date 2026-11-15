package dev.nightjar.payload.log;

/**
 * One L1 line. {@code t} is wall-clock milliseconds; the formatter renders it as
 * ISO8601 with milliseconds.
 *
 * @param seq monotonically increasing per process, starting at 1
 */
public record LogLine(long seq, long t, String level, String tag, String message) {}
