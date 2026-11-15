package dev.nightjar.rules;

import dev.nightjar.contract.WireEnum;

/**
 * Why a verdict was clamped (event {@code verdict.clampReason}, trace {@code fw.headFilter clamp=}).
 * Order of evaluation: (1) to (5) below. The payload adds its own {@code CANARY} value for self-test drops.
 */
public enum ClampReason implements WireEnum {
    /** (1) Action not allowed for the class. */
    ACTION_NOT_ALLOWED("ACTION_NOT_ALLOWED"),
    /** (1) A rule without sender/port/text condition tried to block or quarantine CL-NORMAL. */
    BLOCKS_ALL_NORMAL("G05_ALL_NORMAL"),
    /** (2) Emergency cell broadcast. */
    EMERGENCY_CB("G01_EMERGENCY_CB"),
    /** (3) CL-WAP-SUPL or CL-LOC-SUPL during an emergency call or its grace period. */
    SUPL_EMERGENCY("G04_SUPL_EMERGENCY"),
    /** (4) Safe mode. */
    SAFE_MODE("SAFE_MODE"),
    /** (5) Firewall not available on this build or not enabled. */
    FIREWALL_OFF("FIREWALL_OFF"),
    /** The engine threw; the verdict fell back to ALLOW. */
    ENGINE_ERROR("ENGINE_ERROR");

    private final String wire;

    ClampReason(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
