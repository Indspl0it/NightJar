package dev.nightjar.payload.hook;

import dev.nightjar.contract.HookState;
import dev.nightjar.contract.Tier;
import java.util.List;

/**
 * Tier computation and degradation (10).
 *
 * <p>Rules:
 * <ul>
 *   <li><b>capture</b> = HK-06 + HK-04 + (HK-01 or HK-01b). Any missing → unhook everything and
 *  report {@code UNSUPPORTED};
 *   <li><b>firewall</b> = HK-24 + HK-14 + HK-15 live, else {@code firewallAvailable = false} and
 *  only ALLOW/NOTIFY verdicts;
 *   <li><b>outbound</b> = the HK-19 family + HK-20 live, else outbound capture off; HK-19d (raw
 *       PDU) is absent on OnePlus and therefore not part of the tier;
 *   <li><b>meaning</b> = HK-07, HK-09…HK-12 and the HK-13 family ({@link #MEANING_HOOKS}); a missing one
 *       only makes its fates time-based ({@code FT-ACCEPTED}), so it never disables anything globally.
 * </ul>
 *
 * <p>Thread: worker.
 */
public final class TierManager {
    /** Capture-required hooks. */
    public static final List<String> CAPTURE_REQUIRED = List.of("HK-06", "HK-04");
    /** Alternative radio entries: at least one must be live. */
    public static final List<String> RADIO_ENTRIES = List.of("HK-01", "HK-01b");
    public static final List<String> FIREWALL_HOOKS = List.of("HK-24", "HK-14", "HK-15");
    public static final List<String> OUTBOUND_HOOKS = List.of("HK-19a", "HK-19b", "HK-19c", "HK-19e",
            "HK-19f", "HK-19g", "HK-19h", "HK-19i", "HK-20");
    public static final List<String> MEANING_HOOKS =
            List.of("HK-07", "HK-09", "HK-10", "HK-11", "HK-12", "HK-13a", "HK-13b", "HK-13c");

    /** Live/total count of one tier. */
    public record TierStatus(int live, int total) {
        public java.util.Map<String, Object> toJson() {
            return new dev.nightjar.payload.JsonBuilder().put("live", live).put("total", total).map();
        }
    }

    /** The computed tier state. */
    public record Health(
            boolean captureLive,
            boolean firewallAvailable,
            boolean meaningLive,
            boolean outboundLive,
            TierStatus capture,
            TierStatus captureOptional,
            TierStatus meaning,
            TierStatus firewall,
            TierStatus outbound) {

        /** {@code tiers} object of the hook health report. */
        public java.util.Map<String, Object> tiersJson() {
            return new dev.nightjar.payload.JsonBuilder()
                    .put("capture", capture.toJson())
                    .put("captureOptional", captureOptional.toJson())
                    .put("meaning", meaning.toJson())
                    .put("firewall", firewall.toJson())
                    .put("outbound", outbound.toJson())
                    .map();
        }

        /** True when capture failed: the session is UNSUPPORTED. */
        public boolean unsupported() {
            return !captureLive;
        }
    }

    private TierManager() {}

    public static Health compute(HookRegistry registry) {
        boolean radio = anyLive(registry, RADIO_ENTRIES);
        boolean captureLive = allLive(registry, CAPTURE_REQUIRED) && radio;
        boolean firewallAvailable = allLive(registry, FIREWALL_HOOKS);
        boolean outboundLive = allLive(registry, OUTBOUND_HOOKS);
        boolean meaningLive = allLive(registry, MEANING_HOOKS);
        return new Health(
                captureLive,
                firewallAvailable,
                meaningLive,
                outboundLive,
                count(registry, Tier.CAPTURE_REQUIRED),
                count(registry, Tier.CAPTURE_OPTIONAL),
                count(registry, Tier.MEANING),
                count(registry, Tier.FIREWALL_CORE),
                count(registry, Tier.OUTBOUND));
    }

    /**
     * Applies degradation: on a capture failure every hook is removed and the caller reports
     * {@code UNSUPPORTED}. Returns the health that was applied.
     */
    public static Health degrade(HookRegistry registry) {
        Health h = compute(registry);
        if (!h.captureLive()) registry.unhookAll();
        return h;
    }

    private static boolean live(HookRegistry registry, String id) {
        return registry.state(id) == HookState.LIVE;
    }

    private static boolean anyLive(HookRegistry registry, List<String> ids) {
        for (String id : ids) {
            if (live(registry, id)) return true;
        }
        return false;
    }

    private static boolean allLive(HookRegistry registry, List<String> ids) {
        for (String id : ids) {
            if (!live(registry, id)) return false;
        }
        return true;
    }

    private static TierStatus count(HookRegistry registry, Tier tier) {
        int live = 0;
        int total = 0;
        for (HookRegistry.HookRecord r : registry.all()) {
            if (r.spec.tier() != tier) continue;
            if (r.state == HookState.NOT_APPLICABLE) continue;
            total++;
            if (r.state == HookState.LIVE) live++;
        }
        return new TierStatus(live, total);
    }
}
