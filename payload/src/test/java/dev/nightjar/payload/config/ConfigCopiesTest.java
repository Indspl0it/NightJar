package dev.nightjar.payload.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.log.PLog;
import dev.nightjar.payload.testing.FakeClock;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * After a restart the module applies the rules and settings of the last session before the app sends any, so it
 * holds and blocks before the first unlock. A document from the app always wins over a stored copy.
 */
class ConfigCopiesTest {
    @TempDir
    File dir;

    private static final String RULES_A = """
            {"schema": 1, "revision": 7, "preset": "RECOMMENDED",
             "classActions": {"CL-WAP-CP": "BLOCK"}, "rules": []}
            """;

    private static final String RULES_B = """
            {"schema": 1, "revision": 8, "preset": "MONITOR",
             "classActions": {"CL-WAP-CP": "ALLOW"}, "rules": []}
            """;

    private static final String SETTINGS = """
            {"schema": 1, "appPackage": "dev.nightjar", "appCertSha256": "AB:CD", "safeMode": false}
            """;

    private static ConfigStore store() {
        return new ConfigStore(new FakeClock(0));
    }

    /** One process session: a store whose persisters write the copies at once, as the worker would. */
    private ConfigCopies session(ConfigStore store) {
        ConfigCopies copies = new ConfigCopies(dir, store, new PLog(new FakeClock(0), null));
        store.setRulesPersister(copies::storeRules);
        store.setSettingsPersister(copies::storeSettings);
        return copies;
    }

    @Test
    void rulesFromTheLastSessionApplyBeforeTheAppSendsAny() {
        ConfigStore first = store();
        session(first).start();
        first.applyRulesJson(RULES_A);
        first.applySettingsJson(SETTINGS);

        ConfigStore second = store();
        assertEquals(Verdict.ALLOW, second.rules().config().classAction(ClassId.WAP_CP), "no rules before start");
        session(second).start();
        assertEquals(7, second.rules().revision());
        assertEquals(Verdict.BLOCK, second.rules().config().classAction(ClassId.WAP_CP),
                "the stored rules block after a restart");
        assertEquals("AB:CD", second.settings().get().appCertSha256());
    }

    @Test
    void aDocumentFromTheAppWinsOverTheStoredCopy() {
        ConfigStore first = store();
        session(first).start();
        first.applyRulesJson(RULES_A);

        ConfigStore second = store();
        ConfigCopies copies = session(second);
        second.applyRulesJson(RULES_B);
        copies.start();
        assertEquals(8, second.rules().revision(), "the app document that arrived first stays");

        ConfigStore third = store();
        session(third).start();
        assertEquals(8, third.rules().revision(), "start stored the app document of the second session");
    }

    @Test
    void anAppDocumentAfterStartReplacesTheStoredCopy() {
        ConfigStore first = store();
        session(first).start();
        first.applyRulesJson(RULES_A);

        ConfigStore second = store();
        session(second).start();
        assertEquals(7, second.rules().revision());
        second.applyRulesJson(RULES_B);
        assertEquals(8, second.rules().revision());
        second.applyStoredCopies(RULES_A, null);
        assertEquals(8, second.rules().revision(), "a late stored copy never replaces an app document");
    }

    @Test
    void withoutACopyTheDefaultsAllowEverything() {
        ConfigStore store = store();
        session(store).start();
        assertEquals(0, store.rules().revision());
        assertEquals(Verdict.ALLOW, store.rules().config().classAction(ClassId.WAP_CP));
        assertFalse(store.settings().get().safeMode());
    }

    @Test
    void aBadCopyKeepsTheDefaults() throws Exception {
        Files.writeString(new File(dir, ConfigCopyFile.RULES).toPath(), "{bad");
        Files.writeString(new File(dir, ConfigCopyFile.SETTINGS).toPath(), "not json");
        ConfigStore store = store();
        session(store).start();
        assertEquals(0, store.rules().revision());
        assertEquals(Verdict.ALLOW, store.rules().config().classAction(ClassId.WAP_CP));
        assertEquals("dev.nightjar", store.settings().get().appPackage());
    }

    @Test
    void aRejectedAppDocumentIsNotStored() {
        ConfigStore first = store();
        session(first).start();
        first.applyRulesJson(RULES_A);
        first.applyRulesJson("{bad");
        assertEquals(RULES_A, first.rulesFromApp(), "the good document stays the app document");

        ConfigStore second = store();
        session(second).start();
        assertEquals(7, second.rules().revision(), "the copy is still the last good document");
    }

    @Test
    void safeModeIsStoredWithTheSettings() {
        ConfigStore first = store();
        session(first).start();
        first.applySettingsJson(SETTINGS);
        first.setSafeMode(true);

        ConfigStore second = store();
        session(second).start();
        assertTrue(second.settings().get().safeMode(), "safe mode stays on after a restart");
        assertEquals("AB:CD", second.settings().get().appCertSha256(), "the other settings stay");

        second.setSafeMode(false);
        ConfigStore third = store();
        session(third).start();
        assertFalse(third.settings().get().safeMode(), "turning safe mode off is stored on the stored copy");
        assertEquals("AB:CD", third.settings().get().appCertSha256());
    }

    @Test
    void aStoredPinIsNotUsedForTheHandshake() {
        ConfigStore first = store();
        session(first).start();
        first.applySettingsJson(SETTINGS);
        assertEquals("AB:CD", first.appCertPinFromApp(), "a pin that the app sent counts");

        ConfigStore second = store();
        session(second).start();
        assertEquals("AB:CD", second.settings().get().appCertSha256(), "the stored settings apply");
        assertEquals("", second.appCertPinFromApp(),
                "a stored pin can be stale after the app is installed again with another key");
        second.setSafeMode(true);
        assertEquals("", second.appCertPinFromApp(), "safe mode on the stored copy does not make its pin count");

        second.applySettingsJson(SETTINGS.replace("AB:CD", "EF:01"));
        assertEquals("EF:01", second.appCertPinFromApp(), "the pin of the new app document counts at once");
    }

    @Test
    void storedSettingsFireTheFirewallListener() {
        ConfigStore first = store();
        session(first).start();
        first.applySettingsJson(SETTINGS);
        first.setSafeMode(true);

        ConfigStore second = store();
        List<Boolean> seen = new ArrayList<>();
        second.setFirewallListener(() -> seen.add(second.settings().get().safeMode()));
        session(second).start();
        assertTrue(seen.contains(Boolean.TRUE), "the firewall learns about safe mode from the stored copy");
    }

    @Test
    void aThrowingPersisterKeepsTheDocumentActive() {
        ConfigStore store = store();
        store.setRulesPersister(json -> {
            throw new IllegalStateException("boom");
        });
        store.setSettingsPersister(json -> {
            throw new IllegalStateException("boom");
        });
        store.applyRulesJson(RULES_A);
        store.applySettingsJson(SETTINGS);
        store.setSafeMode(true);
        assertEquals(7, store.rules().revision());
        assertTrue(store.settings().get().safeMode());
    }

    @Test
    void aFailedWriteKeepsTheDocumentActive() throws Exception {
        File notADir = new File(dir, "file");
        Files.writeString(notADir.toPath(), "x");
        ConfigStore store = store();
        ConfigCopies copies = new ConfigCopies(notADir, store, new PLog(new FakeClock(0), null));
        store.setRulesPersister(copies::storeRules);
        copies.start();
        store.applyRulesJson(RULES_A);
        assertEquals(7, store.rules().revision());
    }

    @Test
    void safeModeWithoutSettingsStoresADocumentWithOnlyThatKey() {
        String json = ConfigStore.withSafeMode(null, true);
        assertNotNull(json);
        assertTrue(json.contains("\"safeMode\":true"));
        assertNull(ConfigStore.withSafeMode("not json", true), "a bad base is not stored");
    }
}
