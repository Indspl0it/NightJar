package dev.nightjar.contract;

/** Firewall decision. */
public enum Verdict implements WireEnum {
    ALLOW("ALLOW"),
    NOTIFY("NOTIFY"),
    QUARANTINE("QUARANTINE"),
    BLOCK("BLOCK");

    private final String wire;

    Verdict(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
