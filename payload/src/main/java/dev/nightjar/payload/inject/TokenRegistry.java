package dev.nightjar.payload.inject;

import dev.nightjar.payload.capture.InjectionTokens;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.firewall.InjectionLookup;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time injection tokens.
 *
 * <p>A release registers the TPDU key of every part before the first injection; a canary registers its key before
 * injection. The correlator consumes the token once, which is what makes a re-injected message neither
 * {@code CL-INJECTED} nor re-quarantined. The head filter only peeks ({@link InjectionLookup}), so a canary can
 * be dropped without stealing the token the correlator needs.
 *
 * <p>Tokens expire: a release token is valid for 60 s, a canary for the run plus 60 s, a debug injection for
 * 60 s. Expired tokens are pruned on every write, so the map stays small.
 *
 * <p>Thread: registered from the control-binder thread, peeked from the main looper, consumed on the worker.
 */
public final class TokenRegistry implements InjectionTokens, InjectionLookup {

    /** A release token is valid for 60 s. */
    public static final long RELEASE_TTL_NS = 60_000_000_000L;

    /** Canaries live for a run plus this grace. */
    public static final long CANARY_TTL_NS = 60_000_000_000L;

    /** Debug injections. */
    public static final long DEBUG_TTL_NS = 60_000_000_000L;

    /** Upper bound on live tokens; older ones are pruned first. */
    public static final int MAX_TOKENS = 256;

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    public TokenRegistry(Clock clock) {
        this.clock = clock;
    }

    /** Registers a release of {@code originalEventId} for this part. */
    public void registerRelease(String key, String altKey, String originalEventId) {
        registerRelease(key, altKey, originalEventId, null);
    }

    /**
     * Registers a release of {@code originalEventId} of class {@code classWire} for this part. The class lets HK-23,
     * which never sees the message, recognise a released MWI ({@link #hasPendingRelease(String)}).
     */
    public void registerRelease(String key, String altKey, String originalEventId, String classWire) {
        register(key, altKey, InjectionTokens.Kind.RELEASE, originalEventId, null, classWire, RELEASE_TTL_NS);
    }

    /** Registers a canary of {@code canaryClass} belonging to {@code runId}. */
    public void registerCanary(String key, String altKey, String runId, String canaryClass) {
        register(key, altKey, InjectionTokens.Kind.CANARY, null, runId, canaryClass, CANARY_TTL_NS);
    }

    /** Registers a debug injection. No production code calls it; tests use it. */
    public void registerDebug(String key, String altKey) {
        register(key, altKey, InjectionTokens.Kind.DEBUG, null, null, null, DEBUG_TTL_NS);
    }

    /**
     * Registers a replay of {@code originalEventId}. Same TTL as a release: the token only has to
     * survive the injection round trip.
     */
    public void registerReplay(String key, String altKey, String originalEventId) {
        register(key, altKey, InjectionTokens.Kind.REPLAY, originalEventId, null, null, RELEASE_TTL_NS);
    }

    private void register(String key, String altKey, InjectionTokens.Kind kind, String releaseOf, String runId,
            String classWire, long ttlNs) {
        if (key == null || key.isEmpty()) return;
        long now = clock.elapsedNs();
        prune(now);
        Token token = new Token(kind, releaseOf, runId, classWire, now + ttlNs);
        tokens.put(key, token);
        if (altKey != null && !altKey.isEmpty() && !altKey.equals(key)) tokens.put(altKey, token);
    }

    @Override
    public InjectionTokens.Kind peek(String key, String altKey) {
        Token t = live(key);
        if (t == null) t = live(altKey);
        return t == null ? null : t.kind;
    }

    /**
     * Marks the token as used by the correlator without removing it.
     *
     * <p>The head filter runs on the handler thread and peeks the same key after the worker has
     * already drained the HK-06 observation. Removing the token here made that peek miss, so a
     * canary was delivered and a released message was re-ruled — timing-dependent, and wrong in the
     * direction that matters. The token now stays until its TTL (60 s), which is also what makes a
     * re-injected part idempotent; {@code consumed} only stops a second correlator match.
     */
    @Override
    public Match consume(String tpduKey) {
        Token t = live(tpduKey);
        if (t == null) return null;
        if (!t.consumed.compareAndSet(false, true)) return null;
        switch (t.kind) {
            case RELEASE:
                return new Match(InjectionTokens.Kind.RELEASE, t.releaseOf, null, null);
            case REPLAY:
                return new Match(InjectionTokens.Kind.REPLAY, t.releaseOf, null, null);
            case CANARY:
                return new Match(InjectionTokens.Kind.CANARY, null, t.runId, t.classWire);
            case DEBUG:
                return new Match(InjectionTokens.Kind.DEBUG, null, null, null);
            default:
                return null;
        }
    }

    /** Drops a token that will not be used (a part that was never injected). */
    public void discard(String key, String altKey) {
        Token t = live(key);
        if (t == null) t = live(altKey);
        if (t != null) remove(t);
    }

    /** True while a canary token is still waiting to be consumed (keeps the MWI/SIM-DD gate armed). */
    public boolean hasPendingCanary() {
        long now = clock.elapsedNs();
        for (Token t : tokens.values()) {
            if (t.kind == InjectionTokens.Kind.CANARY && !t.consumed.get() && t.expiresAtNs > now) return true;
        }
        return false;
    }

    /**
     * True while a canary of {@code classWire} is still waiting to be consumed.
     *
     * <p>HK-23 never sees the message, so it cannot compute a TPDU key; the class-scoped question is the narrowest one
     * that still keeps the MWI canary from leaking to the app.
     */
    public boolean hasPendingCanary(String classWire) {
        if (classWire == null || classWire.isEmpty()) return false;
        long now = clock.elapsedNs();
        for (Token t : tokens.values()) {
            if (t.kind == InjectionTokens.Kind.CANARY && !t.consumed.get() && t.expiresAtNs > now
                    && classWire.equals(t.classWire)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True while a release of {@code classWire} is live.
     *
     * <p>HK-23 never sees the message, so it asks the class-scoped question, as for canaries. The token counts until its
     * TTL even when the correlator has consumed it: the correlator can run before HK-23 on the main looper. For
     * the rest of the TTL, HK-23 lets every MWI indicator pass. That is the fail-open direction.
     */
    public boolean hasPendingRelease(String classWire) {
        if (classWire == null || classWire.isEmpty()) return false;
        long now = clock.elapsedNs();
        for (Token t : tokens.values()) {
            if (t.kind == InjectionTokens.Kind.RELEASE && t.expiresAtNs > now && classWire.equals(t.classWire)) {
                return true;
            }
        }
        return false;
    }

    /** Drops every token (new session). */
    public void clear() {
        tokens.clear();
    }

    /** Live tokens, for diagnostics and tests. */
    public int size() {
        long now = clock.elapsedNs();
        int n = 0;
        for (Token t : tokens.values()) if (t.expiresAtNs > now && !t.consumed.get()) n++;
        return n;
    }

    /**
     * The token stored under {@code key}, or null when it expired.
     *
     * <p>A consumed token is still live: the head filter must see it after the correlator has
     * . Expiry is the only removal condition besides {@link #discard}.
     */
    private Token live(String key) {
        if (key == null || key.isEmpty()) return null;
        Token t = tokens.get(key);
        if (t == null) return null;
        if (t.expiresAtNs <= clock.elapsedNs()) {
            remove(t);
            return null;
        }
        return t;
    }

    private void remove(Token token) {
        tokens.values().removeIf(t -> t == token);
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Token>> it = tokens.entrySet().iterator();
        while (it.hasNext()) {
            Token t = it.next().getValue();
            if (t.expiresAtNs <= now) it.remove();
        }
        // Never wipe live release/canary tokens — that would make the in-flight message
        // re-ruled and re-quarantined. Drop expired entries first, then the oldest live ones.
        if (tokens.size() > MAX_TOKENS) pruneOldest(now);
    }

    /** Drops expired tokens, then the oldest live ones until the map is back under the cap. */
    private void pruneOldest(long now) {
        java.util.List<Token> live = new java.util.ArrayList<>();
        for (Token t : tokens.values()) {
            if (t.expiresAtNs <= now) remove(t);
            else if (!live.contains(t)) live.add(t);
        }
        if (tokens.size() <= MAX_TOKENS) return;
        // Oldest expiry first: a token that is about to die is the least valuable to keep.
        live.sort((a, b) -> Long.compare(a.expiresAtNs, b.expiresAtNs));
        for (Token t : live) {
            if (tokens.size() <= MAX_TOKENS) break;
            remove(t);
        }
    }

    /** One registered token; the same object is stored under both key spellings. */
    private static final class Token {
        final InjectionTokens.Kind kind;
        final String releaseOf;
        final String runId;
        /** The canary class, or the class of a released message; {@code null} when not known. */
        final String classWire;
        final long expiresAtNs;
        final java.util.concurrent.atomic.AtomicBoolean consumed =
                new java.util.concurrent.atomic.AtomicBoolean();

        Token(InjectionTokens.Kind kind, String releaseOf, String runId, String classWire, long expiresAtNs) {
            this.kind = kind;
            this.releaseOf = releaseOf;
            this.runId = runId;
            this.classWire = classWire;
            this.expiresAtNs = expiresAtNs;
        }
    }
}
