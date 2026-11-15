package dev.nightjar.contract;

/**
 * Severity, assigned by the app only. The payload never sends it, so a new level is not a protocol change.
 *
 * <p>The constants are in ascending order: the app compares ordinals to raise a severity. Display names:
 * Info = {@link #INFO}, Notice = {@link #NOTABLE}, Suspicious = {@link #SUSPICIOUS}, High = {@link #HIGH}.
 */
public enum Severity implements WireEnum {
    INFO("INFO"),
    NOTABLE("NOTABLE"),
    SUSPICIOUS("SUSPICIOUS"),
    HIGH("HIGH");

    private final String wire;

    Severity(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
