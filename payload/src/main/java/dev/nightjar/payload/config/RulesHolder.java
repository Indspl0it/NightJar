package dev.nightjar.payload.config;

import dev.nightjar.rules.RuleEngine;
import dev.nightjar.rules.RulesConfig;
import dev.nightjar.rules.RulesJson;

/**
 * Volatile, immutable {@code rules.json} snapshot.
 *
 * <p>Parsing and compilation happen off the hook threads: on the worker (IPC reply) or on a control binder thread
 * ({@code updateRules}). {@link ConfigStore} serialises the calls. The swap is a single volatile write, so a hook
 * thread always sees either the old or the new engine, never a half-built one. A parse error keeps
 * the previous snapshot; before the first snapshot the engine is {@link RulesConfig#DEFAULT}
 * (everything ALLOW).
 *
 * <p>Thread: {@link #apply} on the worker or a binder thread, through the {@link ConfigStore} lock; readers any.
 */
public final class RulesHolder {
    /** One parsed and compiled document. */
    public record Snapshot(RulesConfig config, RuleEngine engine, long appliedAtWallMs) {}

    private static final Snapshot EMPTY =
            new Snapshot(RulesConfig.DEFAULT, RuleEngine.compile(RulesConfig.DEFAULT), 0L);

    private volatile Snapshot snapshot = EMPTY;
    private volatile String lastError;

    /** Applies {@code utf8}; returns false and keeps the previous snapshot on any error. */
    public boolean apply(byte[] utf8, long nowWallMs) {
        if (utf8 == null) return false;
        try {
            RulesConfig config = RulesJson.parse(utf8);
            RuleEngine engine = RuleEngine.compile(config);
            snapshot = new Snapshot(config, engine, nowWallMs);
            lastError = null;
            return true;
        } catch (Throwable t) {
            // Never echo the exception message. org.json quotes document fragments and a
            // reflection message can embed argument data (user rule patterns, appCertSha256).
            lastError = t.getClass().getSimpleName();
            return false;
        }
    }

    /** Applies a JSON string (control binder {@code updateRules}). */
    public boolean apply(String json, long nowWallMs) {
        if (json == null) return false;
        return apply(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), nowWallMs);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public RuleEngine engine() {
        return snapshot.engine();
    }

    public RulesConfig config() {
        return snapshot.config();
    }

    public long revision() {
        return snapshot.config().revision();
    }

    /** Reason of the last failed apply, or null. */
    public String lastError() {
        return lastError;
    }
}
