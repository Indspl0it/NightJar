package dev.nightjar.payload.config;

import dev.nightjar.payload.log.PLog;
import java.io.File;

/**
 * The stored copies of the app's {@code rules.json} and {@code settings.json} in the payload's device-protected
 * directory.
 *
 * <p>After a reboot the app cannot read its rules before the user unlocks the phone, so its first hello reply carries
 * none. {@link #start} applies the copies from the last session instead, so the firewall holds and blocks from the
 * start of {@code com.android.phone}. A document from the app always wins: one that arrived before {@link #start} is
 * stored and the copy is not applied.
 *
 * <p>Thread: the payload worker only (file I/O).
 */
public final class ConfigCopies {
    private final ConfigCopyFile rulesFile;
    private final ConfigCopyFile settingsFile;
    private final ConfigStore config;
    private final PLog log;

    public ConfigCopies(File dir, ConfigStore config, PLog log) {
        this.rulesFile = new ConfigCopyFile(dir, ConfigCopyFile.RULES);
        this.settingsFile = new ConfigCopyFile(dir, ConfigCopyFile.SETTINGS);
        this.config = config;
        this.log = log;
    }

    /** Stores the documents that came from the app before this call, and applies the stored copies of the others. */
    public void start() {
        String rules = config.rulesFromApp();
        String settings = config.settingsFromApp();
        if (rules != null) storeRules(rules);
        if (settings != null) storeSettings(settings);
        String storedRules = rules == null ? rulesFile.read() : null;
        String storedSettings = settings == null ? settingsFile.read() : null;
        config.applyStoredCopies(storedRules, storedSettings);
        // No content: only which source each document came from, the rules revision and the safe mode flag.
        log.i("config", "configuration at startup",
                "rulesFrom", source(rules, storedRules), "settingsFrom", source(settings, storedSettings),
                "revision", config.rules().revision(), "safeMode", config.settings().get().safeMode(),
                "rulesError", config.rules().lastError());
    }

    private static String source(String fromApp, String stored) {
        if (fromApp != null) return "app";
        return stored != null ? "stored" : "none";
    }

    /** Stores one rules document from the app. A failed write keeps the document active in memory. */
    public void storeRules(String json) {
        if (!rulesFile.write(json)) {
            log.w("config", "rules copy not stored: after a restart the module allows each message until the app"
                    + " sends the rules");
        }
    }

    /** Stores one settings document from the app. A failed write keeps the document active in memory. */
    public void storeSettings(String json) {
        if (!settingsFile.write(json)) {
            log.w("config", "settings copy not stored: after a restart the module uses the default settings until"
                    + " the app sends them");
        }
    }
}
