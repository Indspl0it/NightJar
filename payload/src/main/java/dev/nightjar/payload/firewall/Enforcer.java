package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;
import dev.nightjar.payload.capture.PduFacts;
import dev.nightjar.payload.capture.WapContentType;
import dev.nightjar.payload.log.PLog;
import dev.nightjar.rules.Decision;
import dev.nightjar.rules.GuardState;
import dev.nightjar.rules.MatchInput;
import dev.nightjar.rules.RuleEngine;
import dev.nightjar.rules.TextProvider;

/**
 * The single evaluation path for every enforcement point.
 *
 * <p>It reads the volatile {@link RuleEngine} snapshot, fills a reused {@link MatchInput} and lets
 * {@link RuleEngine#evaluate} apply the five clamps in order. Any exception yields
 * {@link VerdictRecord#allow()}: the firewall fails open, never closed.
 *
 * <p>The engine's clamps cover every guard rail (emergency CB, SUPL grace, safe mode, action
 * matrix), so a crafted {@code rules.json} cannot block an emergency broadcast or quarantine a SIM update.
 *
 * <p>Thread: hook threads (main looper, CB handler looper, binder). One scratch buffer per thread; nothing blocks. The
 * verdict record is the only allocation per call. Clamps and regex aborts are counted with atomics on the hook thread
 * and logged by {@link #flushStats()} on the worker, so no log text is built on a hook thread.
 */
public final class Enforcer {

    private static final String TAG = "Firewall";

    private final RuleSource rules;
    private final GuardState guards;
    private final TextSource text;
    private final PLog log;
    private final ThreadLocal<Scratch> scratch;
    private final java.util.concurrent.atomic.AtomicLong clamps = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong regexAborts = new java.util.concurrent.atomic.AtomicLong();
    /** The last clamped verdict and its class, for the worker log line. */
    private volatile VerdictRecord lastClamped;
    private volatile ClassId lastClampedClass;

    /** Source of the current compiled snapshot. */
    @FunctionalInterface
    public interface RuleSource {
        /** The compiled engine, or {@code null} before the first snapshot. */
        RuleEngine engine();
    }

    public Enforcer(RuleSource rules, GuardState guards, TextSource text, PLog log) {
        this.rules = rules;
        this.guards = guards;
        this.text = text == null ? TextSource.NONE : text;
        this.log = log;
        this.scratch = ThreadLocal.withInitial(() -> new Scratch(this));
    }

    /**
     * Evaluates one message.
     *
     * @param predicted class at this enforcement point; {@code null} when unknown
     * @param facts parsed PDU facts, or {@code null} (cell broadcast, or a PDU we could not parse)
     * @param contentType WAP content type, or {@code null}
     * @param destPort destination port, −1 when none
     * @param slot SIM slot, −1 when unknown
     * @param roaming roaming state at arrival
     * @param cbMessageId cell-broadcast message identifier, -1 when not a broadcast
     * @param textPdu PDU to decode for {@code textContains} rules, or {@code null}
     * @param originator originating address, used only when {@code facts} carries none (the WAP and SIM-DD paths)
     */
    public VerdictRecord evaluate(ClassId predicted, PduFacts facts, String contentType, int destPort, int slot,
            boolean roaming, int cbMessageId, byte[] textPdu, String originator) {
        return evaluate(predicted, null, facts, contentType, destPort, slot, roaming, cbMessageId, textPdu, originator);
    }

    /**
     * As {@link #evaluate(ClassId, PduFacts, String, int, int, boolean, int, byte[], String)}, with the subtype of the
     * predicted class for rules with a {@code subtypes} condition. {@code null} = no subtype: those rules then
     * do not match and the class action applies (fail open).
     */
    public VerdictRecord evaluate(ClassId predicted, String subtype, PduFacts facts, String contentType, int destPort,
            int slot, boolean roaming, int cbMessageId, byte[] textPdu, String originator) {
        Scratch s = scratch.get();
        try {
            RuleEngine engine = rules.engine();
            if (engine == null) {
                // Defensive: RulesHolder always holds an engine (the DEFAULT one before the first snapshot).
                return VerdictRecord.allow();
            }
            MatchInput in = s.in.reset();
            in.classId(predicted);
            in.subtype(subtype);
            String from = facts != null && facts.originator() != null ? facts.originator() : originator;
            in.originator(from);
            if (facts != null) in.smsc(facts.smsc());
            in.contentType(contentType);
            in.destPort(destPort >= 0 ? destPort : (facts == null ? -1 : facts.destPort()));
            in.slot(slot);
            in.roaming(roaming);
            if (cbMessageId >= 0) in.cbMessageIdentifier(cbMessageId);
            if (textPdu != null) {
                s.textPdu = textPdu;
                s.textDone = false;
                s.textValue = null;
                in.textProvider(s.provider);
            }
            engine.evaluate(in, guards, s.out);
            VerdictRecord record = VerdictRecord.of(s.out);
            if (s.out.regexAborts() > 0) regexAborts.addAndGet(s.out.regexAborts());
            if (record.clamped()) {
                lastClamped = record;
                lastClampedClass = predicted;
                clamps.incrementAndGet();
            }
            return record;
        } catch (Throwable t) {
            // An exception inside the firewall never blocks a message.
            return VerdictRecord.allow();
        } finally {
            s.textPdu = null;
        }
    }

    /**
     * True when a verdict may block or quarantine at all: the firewall is available and enabled and safe mode is off
     * . For decisions that do not go through the rule engine (HK-14 unreadable push).
     */
    public boolean blockingAllowed() {
        try {
            return guards != null && guards.firewallAvailable() && guards.firewallEnabled() && !guards.safeMode();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Logs the clamps and regex aborts counted since the last call (RULES-X6), rate limited. Worker only: the log text
     * is built here, never on a hook thread.
     */
    public void flushStats() {
        if (log == null) return;
        try {
            long c = clamps.getAndSet(0L);
            VerdictRecord last = lastClamped;
            if (c > 0 && last != null) {
                ClassId cls = lastClampedClass;
                log.wLimited("clamp", TAG, "verdicts clamped", "count", c,
                        "last", last.clampedFrom() + "->" + last.action(),
                        "reason", last.clampReason() == null ? "none" : last.clampReason().wire(),
                        "class", cls == null ? "unknown" : cls.wire());
            }
            long r = regexAborts.getAndSet(0L);
            if (r > 0) {
                log.wLimited("regex-abort", TAG, "text rule stopped at the regex step limit", "count", r);
            }
        } catch (Throwable t) {
            // Statistics only.
        }
    }

    /** Head filter (HK-08) without a subtype: subtype rules do not match and the class action applies (fail open). */
    public VerdictRecord headFilter(ClassId predicted, PduFacts facts, int slot, boolean roaming, byte[] tpdu) {
        return headFilter(predicted, null, facts, slot, roaming, tpdu);
    }

    /**
     * Head filter (HK-08): the class predicted from the PDU alone and its subtype from the same classification
     * ({@code SmsFilterCore} classifies once, on the main looper). {@code null} subtype = the class action applies.
     */
    public VerdictRecord headFilter(ClassId predicted, String subtype, PduFacts facts, int slot, boolean roaming,
            byte[] tpdu) {
        return evaluate(predicted, subtype, facts, null, facts == null ? -1 : facts.destPort(), slot, roaming, -1, tpdu,
                null);
    }

    /** WAP push (HK-14): class from the content type, destination port 2948. */
    public VerdictRecord wap(ClassId predicted, String contentType, String originator, int slot, boolean roaming) {
        return evaluate(predicted, wapSubtype(predicted, contentType), null, contentType, WapPush.PORT, slot, roaming,
                -1, null, originator);
    }

    /**
     * The subtype of the HK-14 class from the content type alone (SL, the DM kind, vCard or vCalendar, the
     * content type of CL-WAP-OTHER). A subtype that needs the push body is {@code null} here (fail open).
     */
    static String wapSubtype(ClassId predicted, String contentType) {
        if (predicted == null) return null;
        try {
            return WapContentType.subtype(predicted, contentType, null, null, -1);
        } catch (Throwable t) {
            return null;
        }
    }

    /** SIM data download (HK-15). */
    public VerdictRecord simDataDownload(String originator, PduFacts facts, int slot, boolean roaming) {
        return evaluate(ClassId.SIM_DD, facts, null, -1, slot, roaming, -1, facts == null ? null : facts.tpdu(),
                originator);
    }

    /** Cell broadcast (HK-17): emergency detection happens inside the engine's emergency CB clamp. */
    public VerdictRecord cellBroadcast(int messageIdentifier, int slot, boolean roaming) {
        return evaluate(ClassId.CB, null, null, -1, slot, roaming, messageIdentifier, null, null);
    }

    /** Per-thread scratch: one {@link MatchInput}, one {@link Decision}, one reusable text provider. */
    private static final class Scratch {
        final Enforcer owner;
        final MatchInput in = new MatchInput();
        final Decision out = new Decision();
        final TextProvider provider = this::resolveText;
        byte[] textPdu;
        CharSequence textValue;
        boolean textDone;

        Scratch(Enforcer owner) {
            this.owner = owner;
        }

        private CharSequence resolveText() {
            if (!textDone) {
                textDone = true;
                textValue = owner.text.decode(textPdu);
            }
            return textValue;
        }
    }
}
