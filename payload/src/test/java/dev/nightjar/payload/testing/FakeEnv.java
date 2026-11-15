package dev.nightjar.payload.testing;

import dev.nightjar.payload.env.Env;
import java.util.HashMap;
import java.util.Map;

/** {@link Env} with settable facts (tests). */
public final class FakeEnv implements Env {
    private final Map<Integer, String> carrier = new HashMap<>();
    private final Map<Integer, Integer> slot = new HashMap<>();
    private final Map<Integer, Boolean> roaming = new HashMap<>();
    private final Map<Integer, Integer> subForPhone = new HashMap<>();
    public String fingerprint = "google/akita/akita:17/CP3A.261005.005/16271449";
    public String version = "1.0.0";
    public String apex = "{}";
    public int boot = 7;
    public boolean unlocked = true;

    @Override
    public String buildFingerprint() {
        return fingerprint;
    }

    @Override
    public String payloadVersion() {
        return version;
    }

    @Override
    public String apexVersionsJson() {
        return apex;
    }

    @Override
    public int bootCount() {
        return boot;
    }

    @Override
    public boolean userUnlocked() {
        return unlocked;
    }

    @Override
    public String carrierName(int subId) {
        return carrier.getOrDefault(subId, "");
    }

    @Override
    public int slot(int subId) {
        return slot.getOrDefault(subId, -1);
    }

    @Override
    public boolean roaming(int subId) {
        return roaming.getOrDefault(subId, false);
    }

    @Override
    public int subIdForPhone(int phoneId) {
        return subForPhone.getOrDefault(phoneId, -1);
    }

    public void setSubForPhone(int phoneId, int subId) {
        subForPhone.put(phoneId, subId);
    }

    public void setCarrier(int subId, String name) {
        carrier.put(subId, name);
    }

    public void setSlot(int subId, int s) {
        slot.put(subId, s);
    }

    public void setRoaming(int subId, boolean r) {
        roaming.put(subId, r);
    }
}
