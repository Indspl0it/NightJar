package dev.nightjar.contract;

/** Hook tier (tier legend). */
public enum Tier implements WireEnum {
    CAPTURE_REQUIRED("capture-required"),
    CAPTURE_OPTIONAL("capture-optional"),
    MEANING("meaning"),
    FIREWALL_CORE("firewall-core"),
    FIREWALL_EXTRA("firewall-extra"),
    OUTBOUND("outbound");

    private final String wire;

    Tier(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
