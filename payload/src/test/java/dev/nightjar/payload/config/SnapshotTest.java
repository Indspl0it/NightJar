package dev.nightjar.payload.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.payload.testing.FakeClock;
import org.junit.jupiter.api.Test;

/** Snapshot holders, settings/profiles parsing and profile matching. */
class SnapshotTest {

    private static Settings settings(String json) {
        try {
            return Settings.parse(json);
        } catch (FormatException e) {
            throw new AssertionError(e);
        }
    }

    private static Profile.Document profiles(String json) {
        try {
            return Profile.Document.parse(json);
        } catch (FormatException e) {
            throw new AssertionError(e);
        }
    }


    private static final String RULES = """
            {
              "schema": 1, "revision": 42, "preset": "RECOMMENDED",
              "classActions": {"CL-WAP-CP": "QUARANTINE"},
              "rules": [{"id": 7, "priority": 1, "enabled": true, "origin": "USER",
                         "name": "n", "action": "BLOCK",
                         "when": {"classes": ["CL-WAP-DM"]}}],
              "knownOriginators": {"exact": ["+919000000000"], "prefix": []},
              "guards": {"suplGraceMin": 10},
              "unknownField": {"a": 1}
            }
            """;

    private static final String SETTINGS = """
            {
              "schema": 1, "appPackage": "dev.nightjar", "appCertSha256": "AB:CD",
              "safeMode": false,
              "outgoing": {"enabled": true, "logVisible": true},
              "cb": {"log": true, "allChannels": false},
              "debugLogging": {"until": 12345},
              "detect": {"suplGraceMin": 5},
              "test": {"faultInject": {"HK-06": "throw-before"}},
              "unknownField": true
            }
            """;

    private static final String PROFILES = """
            {
              "schema": 1,
              "profiles": [
                {"id": "akita", "fingerprint": "google/akita/akita:17/CP3A",
                 "match": "PREFIX", "oem": "AOSP", "apex": {"x": "1"},
                 "firewall": true, "orphanPurgeDays": 7,
                 "hooks": {"HK-01": "REQUIRED", "HK-30": "NA"}},
                {"id": "oplus", "fingerprint": "oplus/CPH2649:16", "match": "EXACT",
                 "oem": "OPLUS", "firewall": false, "orphanPurgeDays": 1,
                 "hooks": {"HK-30": "REQUIRED"}}
              ]
            }
            """;

    @Test
    void rulesSnapshotAppliesAndReportsRevision() {
        RulesHolder holder = new RulesHolder();
        assertTrue(holder.apply(RULES, 0));
        assertEquals(42, holder.revision());
        assertNotNull(holder.engine());
        assertEquals(dev.nightjar.contract.Verdict.QUARANTINE,
                holder.config().classAction(dev.nightjar.contract.ClassId.WAP_CP));
        assertEquals(1, holder.config().rules().size());
    }

    @Test
    void malformedRulesKeepThePreviousSnapshot() {
        RulesHolder holder = new RulesHolder();
        holder.apply(RULES, 0);
        long before = holder.revision();
        assertFalse(holder.apply("{bad", 0));
        assertEquals(before, holder.revision());
        assertNotNull(holder.lastError());
    }

    @Test
    void noSnapshotYetMeansAllAllow() {
        RulesHolder holder = new RulesHolder();
        assertEquals(0, holder.revision());
        assertNotNull(holder.engine());
        assertEquals(dev.nightjar.contract.Verdict.ALLOW,
                holder.config().classAction(dev.nightjar.contract.ClassId.WAP_CP));
    }

    @Test
    void settingsParseWithDefaultsAndUnknownFieldsIgnored() {
        Settings s = settings(SETTINGS);
        assertEquals("dev.nightjar", s.appPackage());
        assertEquals("AB:CD", s.appCertSha256());
        assertTrue(s.outgoingLogVisible());
        assertEquals(12345, s.debugUntil());
        assertEquals("throw-before", s.faultMode("HK-06"));
        assertTrue(s.debugEnabledAt(12344));
        assertFalse(s.debugEnabledAt(12345));
    }

    @Test
    void settingsDefaultsWhenFieldsAreAbsent() {
        Settings s = settings("{}");
        assertEquals("dev.nightjar", s.appPackage());
        assertFalse(s.safeMode());
        assertTrue(s.outgoingEnabled());
        assertTrue(s.cbLog());
        assertEquals(0, s.debugUntil());
        assertTrue(s.faultInject().isEmpty());
    }

    @Test
    void malformedSettingsKeepThePreviousSnapshot() {
        SettingsHolder holder = new SettingsHolder();
        assertTrue(holder.apply(SETTINGS));
        assertFalse(holder.apply("["));
        assertEquals(12345, holder.get().debugUntil());
    }

    @Test
    void profilesMatchExactAndPrefix() {
        Profile.Document doc = profiles(PROFILES);
        ProfileMatcher m = new ProfileMatcher(doc);
        assertNotNull(m.profile("google/akita/akita:17/CP3A.261005.005/16271449"));
        assertNotNull(m.profile("oplus/CPH2649:16"));
        assertEquals(null, m.profile("samsung/x:16"));
        assertEquals("akita", m.profile("google/akita/akita:17/CP3A.261005.005/16271449").id());
    }

    @Test
    void statusIsKnownUnknownOrMismatch() {
        ProfileMatcher m = new ProfileMatcher(profiles(PROFILES));
        assertEquals(ProfileMatcher.Status.KNOWN, m.status("oplus/CPH2649:16", id -> false));
        assertEquals(ProfileMatcher.Status.UNKNOWN, m.status("nothing", id -> false));
        assertEquals(ProfileMatcher.Status.MISMATCH,
                m.status("oplus/CPH2649:16", "HK-30"::equals));
    }

    /** No profile flag gates the firewall; only the explicit switch turns it off. */
    @Test
    void firewallIsOnUntilTheExplicitSwitchTurnsItOff() {
        ConfigStore store = new ConfigStore(new FakeClock(0));
        store.applySettingsJson(SETTINGS);
        assertTrue(store.applyProfiles(PROFILES.getBytes()));
        assertTrue(store.firewallEnabled(), "a profile with firewall:false does not gate the firewall");
        store.applyFirewallEnabled(false);
        assertFalse(store.firewallEnabled());
        store.applyFirewallEnabled(true);
        assertTrue(store.firewallEnabled());
    }

    /** A stored "off" read at start keeps the firewall off before the first hello. */
    @Test
    void aPersistedOffIsReadAtStart() {
        ConfigStore store = new ConfigStore(new FakeClock(0));
        int[] fired = {0};
        store.setFirewallListener(() -> fired[0]++);
        store.setPersistedFirewallEnabled(Boolean.FALSE);
        assertFalse(store.firewallEnabled());
        assertEquals(1, fired[0], "the stored value recomputes the firewall state");
        store.setPersistedFirewallEnabled(null);
        assertTrue(store.firewallEnabled(), "no stored switch means on");
    }

    /** A hello reply without the key (only settings and rules) keeps the stored value. */
    @Test
    void aHelloWithoutTheKeyKeepsThePersistedValue() {
        ConfigStore store = new ConfigStore(new FakeClock(0));
        java.util.List<Boolean> stored = new java.util.ArrayList<>();
        store.setFirewallPersister(stored::add);
        store.setPersistedFirewallEnabled(Boolean.FALSE);
        store.applySettingsJson(SETTINGS);
        store.applyRulesJson(RULES);
        assertFalse(store.firewallEnabled());
        assertTrue(stored.isEmpty(), "nothing explicit arrived, so nothing is written");
    }

    /** ENABLE_FIREWALL(true) overrides the stored "off" and is stored for the next boot. */
    @Test
    void anExplicitSwitchOverwritesThePersistedValue() {
        ConfigStore store = new ConfigStore(new FakeClock(0));
        java.util.List<Boolean> stored = new java.util.ArrayList<>();
        store.setFirewallPersister(stored::add);
        store.setPersistedFirewallEnabled(Boolean.FALSE);
        store.applyFirewallEnabled(true);
        assertTrue(store.firewallEnabled());
        assertEquals(java.util.List.of(Boolean.TRUE), stored);
        store.setPersistedFirewallEnabled(Boolean.FALSE);
        assertTrue(store.firewallEnabled(), "the switch received in this process has priority");
    }

    /** A persister that throws keeps the switch active in memory (fail open). */
    @Test
    void aThrowingPersisterKeepsTheSwitchInMemory() {
        ConfigStore store = new ConfigStore(new FakeClock(0));
        store.setFirewallPersister(on -> {
            throw new IllegalStateException("synthetic");
        });
        store.applyFirewallEnabled(false);
        assertFalse(store.firewallEnabled());
    }

    @Test
    void orphanPurgeDaysFollowsTheProfile() {
        ProfileMatcher m = new ProfileMatcher(profiles(PROFILES));
        assertEquals(7, m.orphanPurgeDays("google/akita/akita:17/x"));
        assertEquals(1, m.orphanPurgeDays("oplus/CPH2649:16"));
        assertEquals(7, m.orphanPurgeDays("unknown"));
        assertEquals(Profile.Oem.OPLUS, m.oem("oplus/CPH2649:16"));
    }

    @Test
    void configStoreSwapsAllThreeSnapshots() {
        ConfigStore store = new ConfigStore(new FakeClock(0));
        store.applyRulesJson(RULES);
        store.applySettingsJson(SETTINGS);
        assertTrue(store.applyProfiles(PROFILES.getBytes()));
        assertEquals(42, store.rules().revision());
        assertEquals(12345, store.settings().get().debugUntil());
        assertEquals(2, store.profiles().profiles().size());
        assertEquals(1, store.orphanPurgeDays("oplus/CPH2649:16"));
    }

    @Test
    void settingsFailOpenOnABadUpdate() {
        ConfigStore store = new ConfigStore(new FakeClock(0));
        store.applySettingsJson(SETTINGS);
        store.applySettingsJson("not json");
        assertEquals("dev.nightjar", store.settings().get().appPackage());
    }
}
