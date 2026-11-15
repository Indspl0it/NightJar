package dev.nightjar.payload.hooks;

import dev.nightjar.contract.Fate;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.config.SettingsHolder;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.env.Env;
import dev.nightjar.payload.hook.Param;

/**
 * HK-17 {@code CellBroadcastServiceManager$N#handleMessage(Message)} (capture, enforcement).
 *
 * <p>The GSM CB PDU arrives as {@code msg.obj}, an {@code AsyncResult} whose {@code result} is the raw
 * {@code byte[]} (firmware: {@code CellBroadcastServiceManager.java:114-116} on both reference builds).
 * The before-hook copies the bytes, reads serial/message id/page from the TS 23.041 header (GSM §9.4.1.2 or
 * UMTS §9.4.2.2; a header that is not clear counts as emergency),
 * resolves the phone id from the enclosing manager's {@code mPhone}, and pushes one CB observation.
 * The app reassembles pages; the payload never joins them.
 *
 * <p>{@code cb.log=false} (settings.json) suppresses the event but never the enforcement
 * decision: a user turning CB logging off must not silently disable their firewall rules.
 *
 * <p>Thread: CB handler looper. No I/O, no binder, no JSON, one observation allocation per message.
 */
public final class CbHooks {
    /**
     * Enforcement seam, implemented by {@code firewall.CbEnforcer}. Emergency channels
     * are never offered to it.
     */
    public interface Port {
        /**
         * @param subId subscription the broadcast arrived on, −1 when unknown
         * @return true to drop the broadcast
         */
        boolean block(int messageId, boolean emergency, int subId);

        Port ALLOW_ALL = (messageId, emergency, subId) -> false;
    }

    /** {@code what} for a GSM CB PDU; 1 = CDMA (out of scope), 2 = CDMA SCP. */
    private static final int WHAT_NEW_CB_SMS = 0;

    /** A CB PDU shorter than the serial and message identifier cannot be reported (TS 23.041). */
    private static final int MIN_PDU = 4;

    private final Observations observations;
    private final Clock clock;
    private final Env env;
    private final SettingsHolder settings;
    private volatile Port port = Port.ALLOW_ALL;

    public CbHooks(Observations observations, Clock clock, Env env, SettingsHolder settings) {
        this.observations = observations;
        this.clock = clock;
        this.env = env == null ? EMPTY_ENV : env;
        this.settings = settings == null ? new SettingsHolder() : settings;
    }

    public void setPort(Port port) {
        this.port = port == null ? Port.ALLOW_ALL : port;
    }

    /** Before: capture the PDU, then apply the enforcement decision. */
    public void before(Param p) {
        Object msg = p.arg(0);
        if (msg == null) return;
        if (Reflect.getInt(msg, "what", -1) != WHAT_NEW_CB_SMS) return;
        byte[] pdu = pduOf(Reflect.get(msg, "obj", null));
        if (pdu == null || pdu.length < MIN_PDU) return;

        CaptureHooks.CbFields f = CaptureHooks.CbFields.of(pdu);
        int phoneId = phoneIdOf(p.thisObject());
        int subId = phoneId >= 0 ? env.subIdForPhone(phoneId) : -1;
        // The slot is the phone id; without a phone id the subscription is unknown too.
        int slot = phoneId;
        long tNs = clock.elapsedNs();
        boolean log = settings.get().cbLog();
        boolean blocked = !f.emergency && port.block(f.messageId, false, subId);
        if (log) {
            observations.offer(Observation.cb(pdu, f.serial, f.messageId, f.page, f.pages,
                    f.emergency, phoneId, subId, slot, blocked ? Fate.BLOCKED : null, tNs,
                    clock.wallMs()));
        }
        if (blocked) p.skip();
    }

    /**
     * The PDU inside {@code msg.obj}: an {@code AsyncResult} on both reference builds, a bare
     * {@code byte[]} on builds that post it directly.
     */
    private static byte[] pduOf(Object obj) {
        if (obj instanceof byte[] b) return b.clone();
        if (obj == null) return null;
        Object result = Reflect.get(obj, "result", null);
        if (result instanceof byte[] b) return b.clone();
        for (String name : new String[] {"payload", "mPdu", "pdu"}) {
            Object inner = Reflect.get(obj, name, null);
            if (inner instanceof byte[] b) return b.clone();
        }
        return null;
    }

    /** {@code CellBroadcastServiceManager.this.mPhone.getPhoneId()} through the anonymous class's {@code this$0}. */
    private static int phoneIdOf(Object handler) {
        Object manager = Reflect.get(handler, "this$0", null);
        if (manager == null) return -1;
        Object phone = Reflect.get(manager, "mPhone", null);
        if (phone == null) return -1;
        int id = Reflect.callInt(phone, "getPhoneId", Integer.MIN_VALUE);
        return id == Integer.MIN_VALUE ? Reflect.getInt(phone, "mPhoneId", -1) : id;
    }

    /** No environment: every fact unknown, used by tests and by a runtime without an {@link Env}. */
    private static final Env EMPTY_ENV = new Env() {
        @Override
        public String buildFingerprint() {
            return "";
        }

        @Override
        public String payloadVersion() {
            return "";
        }

        @Override
        public String apexVersionsJson() {
            return "{}";
        }

        @Override
        public int bootCount() {
            return -1;
        }

        @Override
        public boolean userUnlocked() {
            return true;
        }

        @Override
        public String carrierName(int subId) {
            return "";
        }

        @Override
        public int slot(int subId) {
            return -1;
        }

        @Override
        public boolean roaming(int subId) {
            return false;
        }
    };
}
