package dev.nightjar.contract;

/** Overall protection state shown by the app (05). */
public enum ProtectionState implements WireEnum {
    PROTECTED("PROTECTED"),
    MONITOR_ONLY("MONITOR_ONLY"),
    SAFE_MODE("SAFE_MODE"),
    NOT_ACTIVE("NOT_ACTIVE"),
    UNSUPPORTED("UNSUPPORTED");

    private final String wire;

    ProtectionState(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
