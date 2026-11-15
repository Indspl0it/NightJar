package dev.nightjar.rules;

/** Destination port condition, inclusive on both ends (: exact = {@code from == to}). */
public record PortRange(int from, int to) {
    public static PortRange exact(int port) {
        return new PortRange(port, port);
    }

    /** True when both ends are within 0..65535 and {@code from <= to}. */
    public boolean isValid() {
        return from >= 0 && to <= 65535 && from <= to;
    }
}
