package dev.nightjar.payload.config;

/**
 * Volatile, immutable {@code settings.json} snapshot.
 *
 * <p>Same discipline as {@link RulesHolder}: parse on the worker, swap with one volatile write,
 * keep the previous snapshot on error, and start from {@link Settings#DEFAULT}.
 *
 * <p>Thread: {@link #apply} worker only; readers any.
 */
public final class SettingsHolder {
    private volatile Settings settings = Settings.DEFAULT;
    private volatile String lastError;

    /** Applies {@code utf8}; returns false and keeps the previous snapshot on any error. */
    public boolean apply(byte[] utf8) {
        if (utf8 == null) return false;
        try {
            settings = Settings.parse(utf8);
            lastError = null;
            return true;
        } catch (Throwable t) {
            // The message can quote the document (appCertSha256, package names).
            lastError = t.getClass().getSimpleName();
            return false;
        }
    }

    public boolean apply(String json) {
        if (json == null) return false;
        return apply(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Replaces the snapshot with one whose {@code safeMode} flag is {@code on}. */
    public void setSafeMode(boolean on) {
        Settings current = settings;
        if (current.safeMode() == on) return;
        settings = current.withSafeMode(on);
    }

    public Settings get() {
        return settings;
    }

    public String lastError() {
        return lastError;
    }
}
