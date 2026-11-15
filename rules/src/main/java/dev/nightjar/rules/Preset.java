package dev.nightjar.rules;

import dev.nightjar.contract.WireEnum;

/** Firewall preset ({@code preset}, Appendix E). */
public enum Preset implements WireEnum {
    MONITOR("MONITOR"),
    RECOMMENDED("RECOMMENDED"),
    STRICT("STRICT"),
    /** Holds every type that can be held, except regular SMS and emergency alerts. */
    QUARANTINE("QUARANTINE"),
    CUSTOM("CUSTOM");

    private final String wire;

    Preset(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
