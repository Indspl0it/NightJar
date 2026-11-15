package dev.nightjar.payload.config;

import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.ipc.ConfigSink;
import java.util.function.Consumer;

/**
 * The three configuration snapshots plus the profile matcher (T06).
 *
 * <p>This is the seam the control binder uses: {@code updateRules}/{@code updateSettings} arrive on
 * a binder thread, are parsed here (never on the phone main looper), and become visible through one
 * volatile swap each.
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
        return rules.apply(utf8, clock.wallMs());
    }

    /** Applies a {@code settings.json} document. */
    public synchronized boolean applySettings(byte[] utf8) {
        boolean ok = settings.apply(utf8);
        fireFirewallListener();
        return ok;
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
        fireFirewallListener();
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
        rules.apply(json, clock.wallMs());
    }

    /** Applies a {@code settings.json} string from the control binder or the IPC reply; a bad one is ignored. */
    @Override
    public synchronized void applySettingsJson(String json) {
        settings.apply(json);
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
