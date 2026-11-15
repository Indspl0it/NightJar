package dev.nightjar.contract;

/** Message direction. */
public enum Direction implements WireEnum {
    IN("IN"),
    OUT("OUT");

    private final String wire;

    Direction(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
