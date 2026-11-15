package dev.nightjar.payload.ipc;

import dev.nightjar.contract.Ipc;
import dev.nightjar.payload.capture.Counters;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.env.Env;
import dev.nightjar.payload.log.PLog;
import dev.nightjar.payload.store.OutboundEvent;
import dev.nightjar.payload.store.RingBuffer;
import java.util.List;

/**
 * Payload side of the app channel (06).
 *
 * <p>One instance, driven by {@link IpcLoop} on its own {@code NightjarIpc} thread. Each {@link #tick()} performs at
 * most one call, in priority order: handshake, {@code events}, {@code counters}, {@code hookHealth}, {@code log}.
 * Everything is synchronous and allocation-light; no work happens on the phone main looper.
 *
 * <p>Behaviour when the app is absent, force-stopped or refuses: capture continues, events stay in the
 * {@link RingBuffer}, and calls back off 1 s → 60 s. {@code DeadObjectException}, a rejected caller
 * and an {@code UNKNOWN_SESSION} reply all force a new handshake. Any other error reply ({@code LOCKED},
 * {@code STAGING_UNAVAILABLE}) acknowledges nothing: the events and the counter deltas stay for the next try.
 *
 * <p>Thread: {@link #tick()} and {@link #nextDelayMs()} run on the IPC thread only. {@link #onEventSealed()},
 * {@link #onHookHealthChanged()}, {@link #invalidateSession(String)} and the setters are safe from any thread.
 */
public final class IpcWorker {

    /** Coalescing window for a burst of sealed events. */
    public static final long COALESCE_MS = 100L;

    /** Minimum spacing of {@code counters} calls. */
    public static final long COUNTERS_INTERVAL_MS = 60_000L;

    /** How long the loop sleeps when there is nothing to do. */
    public static final long IDLE_POLL_MS = 5_000L;

    /**
     * The app can be killed and restarted without the payload noticing (its session lived in the app process). An idle
     * health push this often makes the next call answer UNKNOWN_SESSION, which triggers the new handshake. Events and
     * counters do not wait for it. Each call can start the app process, so the interval is long.
     */
    public static final long KEEPALIVE_MS = 15L * 60 * 1000;

    /** Refusals in sequence of the same single event before it is dropped as poison. */
    static final int POISON_REFUSALS = 3;

    /** Most lines one {@code log} call carries; older ones are dropped, the ring is only 2,000 lines anyway. */
    static final int LOG_PUSH_MAX_LINES = 300;
    private long lastCallAtMs = Long.MIN_VALUE;

    private static final String TAG = "Ipc";

    private final Env env;
    private final AppTarget target;
    private final RingBuffer outbox;
    private final ConfigSink config;
    private final IpcTransport transport;
    private final Clock clock;
    private final PLog log;
    private final Counters counters;
    private final LogSource logs;
    private final HookHealthSource health;
    private final BatchBuilder batches = new BatchBuilder();
    private final Backoff backoff = new Backoff();
    private final CertPin pin;
    private final AppSigner signer;

    private volatile Object controlBinder;
    private volatile CallerGate gate;
    /** A session reset that another thread asked for; {@link #tick()} applies it on the IPC thread. */
    private final java.util.concurrent.atomic.AtomicReference<String> invalidateRequest =
            new java.util.concurrent.atomic.AtomicReference<>();

    private volatile String sessionId;
    private volatile int sendVersion = Ipc.VERSION;
    private volatile boolean connected;
    private volatile boolean contentAllowed;
    private long nextAttemptAtMs;
    /** The single event the app refused last ({@code eventId:revision}) and how many times in sequence. */
    private String refusedKey;
    private int refusedCount;
    /** Start of the current coalescing window, or −1 when nothing is waiting. */
    private final java.util.concurrent.atomic.AtomicLong dirtySinceMs =
            new java.util.concurrent.atomic.AtomicLong(-1L);
    private long lastCountersAtMs = Long.MIN_VALUE;
    private long lastLogSeq;
    private volatile boolean healthDirty = true;

    /**
     * @param counters optional: {@code null} disables the {@code counters} call
     * @param logs optional: {@code null} disables the {@code log} call
     * @param health optional: {@code null} disables the {@code hookHealth} call and the handshake extras
     */
    public IpcWorker(
            Env env,
            AppTarget target,
            RingBuffer outbox,
            ConfigSink config,
            IpcTransport transport,
            AppSigner signer,
            Clock clock,
            PLog log,
            Counters counters,
            LogSource logs,
            HookHealthSource health) {
        this.env = env;
        this.target = target;
        this.outbox = outbox;
        this.config = config;
        this.transport = transport;
        this.clock = clock;
        this.log = log;
        this.counters = counters;
        this.logs = logs;
        this.health = health;
        this.signer = signer;
        this.pin = new CertPin(signer, log);
    }

    /** The binder handed to the app in {@code hello}; may be set before the first tick. */
    public void setControlBinder(Object binder) {
        this.controlBinder = binder;
    }

    /**
     * The caller gate for the control binder. The handshake records the app uid here before
     * the binder is handed over, otherwise every control call is rejected.
     */
    public void setCallerGate(CallerGate gate) {
        this.gate = gate;
    }

    /** Called on the worker after an event was sealed: starts the coalescing window. */
    public void onEventSealed() {
        // CAS, not a read-then-write: a seal racing with the end of a send must not be lost.
        dirtySinceMs.compareAndSet(-1L, clock.elapsedMs());
    }

    /** Called when the hook-health map changed. */
    public void onHookHealthChanged() {
        healthDirty = true;
    }

    /**
     * Control binder died, the app restarted or the user unlocked: handshake again. Safe from any thread: the
     * request is stored and the next {@link #tick()} applies it on the IPC thread. The caller kicks the loop.
     */
    public void invalidateSession(String reason) {
        invalidateRequest.set(reason == null ? "unknown" : reason);
    }

    private void applyInvalidateRequest() {
        String reason = invalidateRequest.getAndSet(null);
        if (reason != null) resetSession(reason);
    }

    /** IPC thread only. */
    private void resetSession(String reason) {
        connected = false;
        sessionId = null;
        contentAllowed = false;
        nextAttemptAtMs = 0;
        backoff.reset();
        pin.reset();
        CallerGate g = gate;
        if (g != null) g.reset();
        log.i(TAG, "Session reset: " + reason + ".");
    }

    /** One step of the worker. */
    public void tick() {
        long now = clock.elapsedMs();
        try {
            applyInvalidateRequest();
            if (!connected) {
                if (now < nextAttemptAtMs) return;
                handshake(now);
                return;
            }
            if (now < nextAttemptAtMs) return;
            if (eventsReady(now)) {
                sendEvents(now);
                return;
            }
            if (countersDue(now)) {
                sendCounters(now);
                return;
            }
            if (healthDirty) {
                sendHealth(now);
                return;
            }
            if (lastCallAtMs != Long.MIN_VALUE && now - lastCallAtMs >= KEEPALIVE_MS) {
                sendHealth(now);
                return;
            }
            sendLog(now);
        } catch (Throwable t) {
            failed(IpcException.Kind.FAILURE, "unexpected", t, now);
        }
    }

    /** Delay until the next {@link #tick()} can do something; the loop also wakes on every kick. */
    public long nextDelayMs() {
        long now = clock.elapsedMs();
        long wait;
        if (invalidateRequest.get() != null) {
            wait = 0;
        } else if (!connected || nextAttemptAtMs > now) {
            wait = nextAttemptAtMs - now;
        } else if (eventsReady(now) || countersDue(now) || healthDirty) {
            wait = 0;
        } else {
            wait = IDLE_POLL_MS;
        }
        return Math.max(50L, Math.min(Backoff.MAX_MS, wait));
    }

    /** False also while a session reset waits for the next tick. */
    public boolean connected() {
        return connected && invalidateRequest.get() == null;
    }

    /** True when the pinned certificate matched, so message content may leave the payload. */
    public boolean contentAllowed() {
        return contentAllowed;
    }

    public String sessionId() {
        return sessionId;
    }

    /** IPC version used on the wire: the lower of ours and the app's. */
    public int sendVersion() {
        return sendVersion;
    }

    /** Consecutive failures since the last success, for diagnostics. */
    public int failureCount() {
        return backoff.failures();
    }

    // ------------------------------------------------------------------ steps

    private void handshake(long now) {
        String appPackage = target.appPackage();
        if (appPackage == null || appPackage.isEmpty()) {
            failed(IpcException.Kind.APP_ABSENT, "no app package in settings", null, now);
            return;
        }
        // Establish the pin before anything from the reply is trusted and before the control
        // binder is handed over. A squatter that answers on the authority can no longer push rules
        // or a settings object pointing at itself.
        contentAllowed = pin.verify(appPackage, target.appCertSha256());
        int uid = signer.uidOf(appPackage);
        CallerGate g = gate;
        if (g != null) {
            if (uid >= 0) g.setAppUid(uid);
            else g.reset();
        }
        IpcRequest request = Handshake.hello(Ipc.authority(appPackage), env, health,
                contentAllowed ? controlBinder : null);
        IpcReply reply;
        try {
            reply = call(request);
        } catch (IpcException e) {
            failed(e.kind(), e.getMessage(), e, now);
            return;
        }
        Handshake.Session session = Handshake.parse(reply);
        if (!session.ok()) {
            failed(IpcException.Kind.FAILURE, "handshake refused: " + session.error(), null, now);
            return;
        }
        sessionId = session.sessionId();
        sendVersion = Math.min(session.ipcVersion(), Ipc.VERSION);
        connected = true;
        nextAttemptAtMs = 0;
        backoff.onSuccess();
        if (contentAllowed) apply(session);
        healthDirty = true;
        if (outbox.pendingCount() > 0) dirtySinceMs.compareAndSet(-1L, now);
        log.i(TAG, "handshake ok version=" + sendVersion + " content=" + contentAllowed);
    }

    private void apply(Handshake.Session session) {
        try {
            if (session.rulesJson() != null) config.applyRulesJson(session.rulesJson());
        } catch (Throwable t) {
            log.w(TAG, "The worker cannot apply the rules snapshot: " + t.getClass().getSimpleName());
        }
        try {
            if (session.settingsJson() != null) config.applySettingsJson(session.settingsJson());
        } catch (Throwable t) {
            log.w(TAG, "The worker cannot apply the settings snapshot: " + t.getClass().getSimpleName());
        }
        try {
            // No key means the app cannot know (locked); the current value stays.
            if (session.hasFirewallEnabled()) config.applyFirewallEnabled(session.firewallEnabled());
        } catch (Throwable t) {
            log.w(TAG, "The worker cannot apply the firewall flag: " + t.getClass().getSimpleName());
        }
    }

    private boolean eventsReady(long now) {
        if (!contentAllowed || outbox.pendingCount() == 0) return false;
        long since = dirtySinceMs.get();
        if (since < 0) {
            // Queued by something other than a seal (a restored spool, a re-handshake).
            dirtySinceMs.compareAndSet(-1L, now);
            since = dirtySinceMs.get();
        }
        return now - since >= COALESCE_MS || outbox.pendingCount() >= Ipc.MAX_BATCH_EVENTS;
    }

    private void sendEvents(long now) {
        final long windowStart = dirtySinceMs.get();
        List<OutboundEvent> candidates = outbox.peek(Ipc.MAX_BATCH_EVENTS);
        BatchBuilder.Batch batch = batches.build(candidates);
        if (batch.isEmpty()) {
            dirtySinceMs.compareAndSet(dirtySinceMs.get(), -1L);
            return;
        }
        if (batch.oversized()) {
            log.wLimited("batch-oversize", TAG,
                    "Single event is larger than the batch limit. The worker sends it alone.");
        }
        IpcRequest request = sessionRequest(Ipc.Method.EVENTS).put(Ipc.Key.BATCH, batch.json());
        IpcReply reply;
        try {
            reply = call(request);
        } catch (IpcException e) {
            failed(e.kind(), e.getMessage(), e, now);
            return;
        }
        if (isUnknownSession(reply)) {
            resetSession(Ipc.Error.UNKNOWN_SESSION);
            return;
        }
        String error = reply.getString(Ipc.Key.ERROR);
        if (error != null) {
            // LOCKED, STAGING_UNAVAILABLE or a newer code: the app kept nothing. Keep the events and retry.
            failed(IpcException.Kind.FAILURE, "events refused with " + error, null, now);
            return;
        }
        AckSet acked = AckSet.parse(reply.getString(Ipc.Key.ACKED));
        if (acked.isEmpty()) {
            if (batch.events().size() == 1 && refusedAgain(batch.events().get(0))) {
                // A single event the app refuses each time is poison; retrying it forever would block
                // every later message until the ring evicts it. Drop it and account for the loss.
                OutboundEvent poison = batch.events().get(0);
                outbox.remove(poison.eventId(), poison.revision());
                log.wLimited("batch-poison", TAG, "The app refused one event " + POISON_REFUSALS
                        + " times. The worker deletes it.", "eventId", poison.eventId());
                refusedKey = null;
                refusedCount = 0;
                endWindow(windowStart, now);
                nextAttemptAtMs = 0;
                return;
            }
            failed(IpcException.Kind.FAILURE, "no events acknowledged", null, now);
            return;
        }
        refusedKey = null;
        refusedCount = 0;
        for (OutboundEvent event : batch.events()) {
            if (acked.acked(event.eventId(), event.revision())) {
                outbox.remove(event.eventId(), event.revision());
            }
        }
        nextAttemptAtMs = 0;
        backoff.onSuccess();
        endWindow(windowStart, now);
    }

    /** Counts one more refusal of {@code event}; true when it reached {@link #POISON_REFUSALS}. */
    private boolean refusedAgain(OutboundEvent event) {
        String key = Ipc.ackKey(event.eventId(), event.revision());
        if (key.equals(refusedKey)) {
            refusedCount++;
        } else {
            refusedKey = key;
            refusedCount = 1;
        }
        return refusedCount >= POISON_REFUSALS;
    }

    /**
     * Closes a coalescing window without losing a seal that landed while the send was in flight:
     * only the window we started from is cleared.
     */
    private void endWindow(long windowStart, long now) {
        if (outbox.pendingCount() > 0) dirtySinceMs.set(now);
        else dirtySinceMs.compareAndSet(windowStart, -1L);
    }

    private boolean countersDue(long now) {
        if (counters == null) return false;
        return lastCountersAtMs == Long.MIN_VALUE || now - lastCountersAtMs >= COUNTERS_INTERVAL_MS;
    }

    private void sendCounters(long now) {
        String json;
        try {
            // Deltas only — the app adds what it receives, so a cumulative total would be
            // counted again on every flush.
            json = counters.drainJson();
        } catch (Throwable t) {
            log.w(TAG, "counters unavailable: " + t.getClass().getSimpleName());
            lastCountersAtMs = now;
            return;
        }
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            lastCountersAtMs = now;
            return;
        }
        IpcReply reply;
        try {
            reply = call(sessionRequest(Ipc.Method.COUNTERS).put(Ipc.Key.COUNTERS, json));
        } catch (IpcException e) {
            counters.unsent();
            failed(e.kind(), e.getMessage(), e, now);
            return;
        }
        if (isUnknownSession(reply)) {
            // The app restarted and lost our session: send the deltas again after the next hello.
            counters.unsent();
            resetSession(Ipc.Error.UNKNOWN_SESSION);
            return;
        }
        String error = reply.getString(Ipc.Key.ERROR);
        if (Ipc.Error.LOCKED.equals(error)) {
            // Before the first unlock the app cannot store counters. Keep the deltas and try again after
            // the normal interval, without a backoff that would also delay the events.
            counters.unsent();
            lastCountersAtMs = now;
            return;
        }
        if (error != null) {
            counters.unsent();
            failed(IpcException.Kind.FAILURE, "counters refused with " + error, null, now);
            return;
        }
        counters.delivered();
        lastCountersAtMs = now;
        nextAttemptAtMs = 0;
        backoff.onSuccess();
    }

    private void sendHealth(long now) {
        if (health == null) {
            healthDirty = false;
            return;
        }
        String json = health.hookHealthJson();
        if (json == null) {
            healthDirty = false;
            return;
        }
        IpcReply reply;
        try {
            reply = call(sessionRequest(Ipc.Method.HOOK_HEALTH)
                    .put(Ipc.Key.HOOK_HEALTH, json)
                    .put(Ipc.Key.DROPPED_COUNT, health.droppedCount()));
        } catch (IpcException e) {
            failed(e.kind(), e.getMessage(), e, now);
            return;
        }
        if (isUnknownSession(reply)) {
            resetSession(Ipc.Error.UNKNOWN_SESSION);
            return;
        }
        String error = reply.getString(Ipc.Key.ERROR);
        if (error != null) {
            // An error reply delivered nothing; the document stays dirty and goes again after the backoff.
            failed(IpcException.Kind.FAILURE, "health refused with " + error, null, now);
            return;
        }
        healthDirty = false;
        nextAttemptAtMs = 0;
        backoff.onSuccess();
    }

    private void sendLog(long now) {
        if (logs == null) return;
        long latest = logs.latestSeq();
        if (latest <= lastLogSeq) return;
        String json = logs.linesJson(lastLogSeq, latest, LOG_PUSH_MAX_LINES);
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            lastLogSeq = latest;
            return;
        }
        IpcReply reply;
        try {
            reply = call(sessionRequest(Ipc.Method.LOG).put(Ipc.Key.LINES, json));
        } catch (IpcException e) {
            failed(e.kind(), e.getMessage(), e, now);
            return;
        }
        if (isUnknownSession(reply)) {
            resetSession(Ipc.Error.UNKNOWN_SESSION);
            return;
        }
        String error = reply.getString(Ipc.Key.ERROR);
        if (error != null) {
            // The sequence moves only after a delivered push.
            failed(IpcException.Kind.FAILURE, "log refused with " + error, null, now);
            return;
        }
        lastLogSeq = latest;
        nextAttemptAtMs = 0;
        backoff.onSuccess();
    }

    // ----------------------------------------------------------------- plumbing

    private IpcRequest sessionRequest(String method) {
        String appPackage = target.appPackage();
        IpcRequest request = new IpcRequest(method, Ipc.authority(appPackage == null ? "" : appPackage))
                .put(Ipc.Key.IPC_VERSION, sendVersion);
        if (sessionId != null) request.put(Ipc.Key.SESSION_ID, sessionId);
        return request;
    }

    private IpcReply call(IpcRequest request) throws IpcException {
        IpcReply reply = transport.call(request);
        lastCallAtMs = clock.elapsedMs();
        if (reply == null) throw new IpcException(IpcException.Kind.REJECTED, "null reply");
        return reply;
    }

    private static boolean isUnknownSession(IpcReply reply) {
        return Ipc.Error.UNKNOWN_SESSION.equals(reply.getString(Ipc.Key.ERROR));
    }

    private void failed(IpcException.Kind kind, String message, Throwable cause, long now) {
        long delay = backoff.onFailure();
        nextAttemptAtMs = now + delay;
        String suffix = cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")";
        log.wLimited("ipc-" + kind, TAG,
                "Call failed " + kind + ": " + message + suffix + ". Next try in " + delay + " ms.");
        if (kind == IpcException.Kind.APP_ABSENT
                || kind == IpcException.Kind.DEAD_OBJECT
                || kind == IpcException.Kind.REJECTED) {
            connected = false;
            sessionId = null;
            contentAllowed = false;
            pin.reset();
        }
    }
}
