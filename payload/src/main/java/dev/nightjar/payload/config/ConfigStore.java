package dev.nightjar.payload.config;

import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.ipc.ConfigSink;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.json.JSONObject;

/**
 * The three configuration snapshots plus the profile matcher (T06).
 *
 * <p>This is the seam the control binder uses: {@code updateRules}/{@code updateSettings} arrive on
 * a binder thread, are parsed here (never on the phone main looper), and become visible through one
 * volatile swap each.
 *
 * <p>Each rules and settings document from the app goes to a persister, which stores a copy in device-protected
 * storage ({@link ConfigCopyFile}). After a reboot the payload applies those copies with {@link #applyStoredCopies}
 * before the app can send anything, also before the user unlocks the phone. A document from the app always wins
 * over a stored copy.
 *
 * <p>Thread: any; a concurrent apply is serialised on this object so two binder calls cannot
 * interleave a parse.
 */
public final class ConfigStore implements ConfigSink {
    private final Clock clock;
    private final RulesHolder rules = new RulesHolder();
    private final SettingsHolder settings = new SettingsHolder();
    private volatile Profile.Document profiles = Profile.Document.EMPTY;
    private volatile ProfileMatcher matcher = new ProfileMatcher(Profile.Document.EMPTY);
    /**
     * Explicit firewall switch from the app ({@code ENABLE_FIREWALL}, or {@code firewallEnabled} in a hello reply)
     * received in this process: {@code null} means "no switch received yet". A hello reply without the key keeps the
     * current value.
     */
    private volatile Boolean firewallEnabledOverride;
    /**
     * The last explicit switch read from device-protected storage at boot ({@link FirewallFlagFile}), so a
     * user who turned blocking off keeps it off after a reboot or a {@code com.android.phone} restart, also before
     * the first hello and before unlock. {@code null} until the read finishes, or when no switch was ever stored.
     */
    private volatile Boolean firewallEnabledPersisted;
    /** Stores each explicit switch. It must only post the write to the worker and never block. */
    private volatile Consumer<Boolean> firewallPersister;
    private volatile Runnable firewallListener;
    /** Stores each rules document from the app. It must only post the write to the worker and never block. */
    private volatile Consumer<String> rulesPersister;
    /** Stores each settings document from the app, also after a safe mode change. Same contract as above. */
    private volatile Consumer<String> settingsPersister;
    /** The last rules document from the app in this process, or null. Guarded by {@code this}. */
    private String rulesFromApp;
    /** The settings document behind the current snapshot when it came from the app, or null. Guarded by {@code this}. */
    private String settingsFromApp;
    /** The stored settings copy applied at startup, or null. Guarded by {@code this}. */
    private String settingsStored;
    /** True once a settings document from the app applied in this process. Guarded by {@code this}. */
    private boolean settingsAppliedFromApp;

    public ConfigStore(Clock clock) {
        this.clock = clock;
    }

    public RulesHolder rules() {
        return rules;
    }

    public SettingsHolder settings() {
        return settings;
    }

    public Profile.Document profiles() {
        return profiles;
    }

    public ProfileMatcher matcher() {
        return matcher;
    }

    /** Applies a {@code rules.json} document; false keeps the previous snapshot. */
    public synchronized boolean applyRules(byte[] utf8) {
        return utf8 != null && acceptRules(new String(utf8, StandardCharsets.UTF_8));
    }

    /** Applies a {@code settings.json} document. */
    public synchronized boolean applySettings(byte[] utf8) {
        boolean ok = utf8 != null && acceptSettings(new String(utf8, StandardCharsets.UTF_8));
        fireFirewallListener();
        return ok;
    }

    /**
     * Applies the copies read from device-protected storage at startup. A copy applies only while no document of
     * its kind arrived from the app in this process; a bad copy keeps the defaults. Neither copy is stored again.
     *
     * @param rulesJson the stored {@code rules.json}, or null when there is no copy
     * @param settingsJson the stored {@code settings.json}, or null when there is no copy
     */
    public synchronized void applyStoredCopies(String rulesJson, String settingsJson) {
        if (rulesFromApp == null && rulesJson != null) {
            rules.apply(rulesJson, clock.wallMs());
        }
        if (settingsFromApp == null && settingsJson != null && settings.apply(settingsJson)) {
            settingsStored = settingsJson;
        }
        fireFirewallListener();
    }

    /** The last rules document from the app in this process, or null. */
    public synchronized String rulesFromApp() {
        return rulesFromApp;
    }

    /** The settings document from the app behind the current snapshot, with the current safe mode, or null. */
    public synchronized String settingsFromApp() {
        return settingsFromApp;
    }

    /** Sets the callback that stores each rules document from the app; it must only post the write. */
    public void setRulesPersister(Consumer<String> persister) {
        this.rulesPersister = persister;
    }

    /** Sets the callback that stores each settings document from the app; it must only post the write. */
    public void setSettingsPersister(Consumer<String> persister) {
        this.settingsPersister = persister;
    }

    /** Applies a rules document from the app and stores it when it parsed. Caller holds the lock. */
    private boolean acceptRules(String json) {
        if (!rules.apply(json, clock.wallMs())) return false;
        rulesFromApp = json;
        persist(rulesPersister, json);
        return true;
    }

    /** Applies a settings document from the app and stores it when it parsed. Caller holds the lock. */
    private boolean acceptSettings(String json) {
        if (!settings.apply(json)) return false;
        settingsFromApp = json;
        settingsAppliedFromApp = true;
        persist(settingsPersister, json);
        return true;
    }

    /**
     * The pinned app certificate ({@code appCertSha256}) of a settings document that the app sent in this process, or
     * an empty string while the settings come from a stored copy or the defaults.
     *
     * <p>A stored pin can be stale: the app can be installed again with a different signing key (a debug build and a
     * release build share the package name). A stale pin would withhold content for ever, and the app's new settings
     * with the new pin are only applied after a pin match. So the handshake does not use a stored pin.
     */
    public synchronized String appCertPinFromApp() {
        return settingsAppliedFromApp ? settings.get().appCertSha256() : "";
    }

    private static void persist(Consumer<String> persister, String json) {
        if (persister == null) return;
        try {
            persister.accept(json);
        } catch (Throwable ignored) {
            // Fail open: the document stays active in memory.
        }
    }

    /** Applies a {@code profiles.json} document; false keeps the previous document. */
    public synchronized boolean applyProfiles(byte[] utf8) {
        try {
            Profile.Document doc = Profile.Document.parse(utf8);
            profiles = doc;
            matcher = new ProfileMatcher(doc);
            fireFirewallListener();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when the firewall runs: the explicit switch received in this process, else the switch stored in
     * device-protected storage, else on. Neither the profile nor a self-test result gates it; only
     * {@code ENABLE_FIREWALL(false)} or a hello {@code firewallEnabled=false} turns it off, and the stored value keeps
     * it off across reboots and {@code com.android.phone} restarts.
     */
    public boolean firewallEnabled() {
        Boolean override = firewallEnabledOverride;
        if (override != null) return override;
        Boolean persisted = firewallEnabledPersisted;
        return persisted == null || persisted;
    }

    /** The explicit switch received in this process, or {@code null} when none arrived yet. */
    public Boolean firewallEnabledOverride() {
        return firewallEnabledOverride;
    }

    /**
     * Sets the switch read from device-protected storage at boot; {@code null} when no switch is stored. An
     * explicit switch received in this process has priority. Fires the firewall listener.
     */
    public void setPersistedFirewallEnabled(Boolean enabled) {
        firewallEnabledPersisted = enabled;
        fireFirewallListener();
    }

    /**
     * Sets the callback that stores each explicit switch. The callback runs on the caller's thread (binder
     * or IPC), so it must only post the write to the worker.
     */
    public void setFirewallPersister(Consumer<Boolean> persister) {
        this.firewallPersister = persister;
    }

    /**
     * Sets safe mode without a full settings document.
     *
     * <p>Safe mode is a user-visible switch, so the settings snapshot has to carry it: writing only
     * the {@code FirewallState} would be reverted by the next push from the app.
     */
    public synchronized void setSafeMode(boolean on) {
        settings.setSafeMode(on);
        // The stored copy must carry the switch too, or a reboot before the next push would apply a stale value.
        String json = withSafeMode(settingsFromApp != null ? settingsFromApp : settingsStored, on);
        if (json != null) {
            settingsFromApp = json;
            persist(settingsPersister, json);
        }
        fireFirewallListener();
    }

    /** {@code base} with its {@code safeMode} key set to {@code on}; a null base gives a document with only that key. */
    static String withSafeMode(String base, boolean on) {
        try {
            JSONObject o = base == null ? new JSONObject() : new JSONObject(base);
            o.put("safeMode", on);
            return o.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called when the firewall enablement changes (the runtime recomputes the firewall state). */
    public void setFirewallListener(Runnable r) {
        this.firewallListener = r;
    }

    private void fireFirewallListener() {
        Runnable r = firewallListener;
        if (r == null) return;
        try {
            r.run();
        } catch (Throwable ignored) {
            // Fail open: a listener must never break the handshake.
        }
    }

    // ------------------------------------------------------------------ ipc.ConfigSink

    /** Applies a {@code rules.json} string from the control binder or the IPC reply; a bad one keeps the snapshot. */
    @Override
    public synchronized void applyRulesJson(String json) {
        if (json != null) acceptRules(json);
    }

    /** Applies a {@code settings.json} string from the control binder or the IPC reply; a bad one is ignored. */
    @Override
    public synchronized void applySettingsJson(String json) {
        if (json != null) acceptSettings(json);
        fireFirewallListener();
    }

    /** Explicit switch from the app: active at once and stored for the next boot. */
    @Override
    public void applyFirewallEnabled(boolean enabled) {
        firewallEnabledOverride = Boolean.valueOf(enabled);
        Consumer<Boolean> persister = firewallPersister;
        if (persister != null) {
            try {
                persister.accept(Boolean.valueOf(enabled));
            } catch (Throwable ignored) {
                // Fail open: the value stays active in memory.
            }
        }
        fireFirewallListener();
    }

    /** Current fingerprint classification. */
    public ProfileMatcher.Status status(String fingerprint,
            java.util.function.Predicate<String> requiredHookFailed) {
        return matcher.status(fingerprint, requiredHookFailed);
    }

    public int orphanPurgeDays(String fingerprint) {
        return matcher.orphanPurgeDays(fingerprint);
    }
}
