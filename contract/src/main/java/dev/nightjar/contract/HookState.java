package dev.nightjar.contract;

/** Per-hook resolution state. */
public enum HookState implements WireEnum {
    LIVE("LIVE"),
    NOT_FOUND("NOT_FOUND"),
    FAILED("FAILED"),
    DISABLED("DISABLED"),
    NOT_APPLICABLE("NOT_APPLICABLE");

    private final String wire;

    HookState(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
