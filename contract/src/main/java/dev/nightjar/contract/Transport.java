package dev.nightjar.contract;

/** How a message reached or left the phone. */
public enum Transport implements WireEnum {
    CS("CS"),
    IMS("IMS"),
    CB("CB"),
    INJECTED("INJECTED"),
    MO_CS("MO_CS"),
    MO_IMS("MO_IMS");

    private final String wire;

    Transport(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
