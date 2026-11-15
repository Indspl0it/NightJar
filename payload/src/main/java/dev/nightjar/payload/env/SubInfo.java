package dev.nightjar.payload.env;

/**
 * Immutable facts about one subscription. Replaced whole on refresh, so readers on
 * hook threads never see a half-updated value.
 *
 * @param subId subscription id, −1 when unknown
 * @param slot SIM slot index, −1 when unknown
 * @param carrierName carrier name, empty when unknown
 * @param roaming last known roaming state
 */
public record SubInfo(int subId, int slot, String carrierName, boolean roaming) {
    public static final SubInfo UNKNOWN = new SubInfo(-1, -1, "", false);

    public SubInfo {
        if (carrierName == null) carrierName = "";
    }
}
