package dev.nightjar.rules;

import dev.nightjar.contract.WireEnum;

/** Who owns a rule ({@code origin}). Preset rules are replaced when the preset changes. */
public enum RuleOrigin implements WireEnum {
    USER("USER"),
    PRESET("PRESET");

    private final String wire;

    RuleOrigin(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
