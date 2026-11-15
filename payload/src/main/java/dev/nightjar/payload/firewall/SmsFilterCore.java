package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Hashes;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.payload.capture.ClassPredictor;
import dev.nightjar.payload.capture.InjectionTokens;
import dev.nightjar.payload.capture.PduFacts;
import dev.nightjar.payload.capture.ProvisionalClassifier;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.log.PLog;

/**
 * HK-08 logic: the head {@code SmsFilter}.
 *
 * <p>Order of work: identify the message by TPDU key, recognise our own injections, predict the class from the PDU alone
 * , ask the {@link Enforcer}, then either deliver or drop through HK-R3.
 *
 * <p>Only the first PDU of a multipart message is classified and given to {@code textContains} rules: a text rule sees
 * the text of part 1 only (the framework hands all parts at once, but the rule text provider decodes one PDU).
 *
 * <p>The ACK is not on this path at all: {@code dropFilteredSms} finishes a message the radio already acknowledged, so no
 * verdict can change it.
 *
 * <p>Deliberate non-enforcement:
 *
 * <ul>
 *   <li>before the first unlock no message is filtered: the framework keeps it in the raw table and offers it again
 *  after the unlock, when the rules run;
 *   <li>port 2948 is left to HK-14, which knows the content type;
 *  <li>classes with no enforcement point (silent ping, SIM-stored, status report) are never touched.
 * </ul>
 *
 * <p>Every failure returns {@code false}: the message is delivered, never held back.
 *
 * <p>Thread: main looper. No I/O, no binder, no JSON.
 */
public final class SmsFilterCore {

    private final Enforcer enforcer;
    private final InjectionLookup tokens;
    private final Clock clock;
    private final PLog log;

    public SmsFilterCore(Enforcer enforcer, InjectionLookup tokens, Clock clock, PLog log) {
        this.enforcer = enforcer;
        this.tokens = tokens == null ? InjectionLookup.NONE : tokens;
        this.clock = clock;
        this.log = log;
    }

    /**
     * Parses the message. The framework hands the head filter the PDU as it came from the radio, SMSC prefix included,
     * but some builds pass the bare TPDU; the two forms are told apart by which one parses as a valid SMS-DELIVER.
     */
    static PduFacts facts(byte[] raw) {
        PduFacts radio = PduFacts.parseRadio(raw);
        if (radio.valid()) return radio;
        PduFacts bare = PduFacts.parse(raw);
        return bare.valid() ? bare : radio;
    }

    /** One filter invocation (HK-08 signature, minus the framework objects). */
    public record Input(byte[][] pdus, int destPort, boolean userUnlocked, int slot, boolean roaming) {}

    /**
     * @return true when the message was consumed (blocked, quarantined or intercepted as a canary), false to continue the
     *     filter chain
     */
    public boolean filter(Input in, SmsFilterHost host) {
        if (in == null || in.pdus() == null || in.pdus().length == 0 || host == null) return false;
        byte[] raw = in.pdus()[0];
        if (raw == null || raw.length == 0) return false;
        long tNs = clock.elapsedNs();
        try {
            String key = Hashes.tpduKey(Hashes.stripSmsc(raw));
            String altKey = Hashes.tpduKey(raw);

            InjectionTokens.Kind kind = tokens.peek(key, altKey);
            if (kind == InjectionTokens.Kind.CANARY) {
                // Interception is not a verdict, so it works with the firewall off or unavailable.
                host.dropFilteredSms();
                host.publishVerdict(VerdictRecord.canaryBlock(), tNs);
                return true;
            }
            if (kind == InjectionTokens.Kind.RELEASE || kind == InjectionTokens.Kind.REPLAY) {
                // Our own release or replay is delivered without rules; the correlator flags the event
                // from the same token. A DEBUG injection is not in this list: it runs the rules like a real
                // message, so a test sees the real verdict.
                host.publishStage(TraceStage.FW_HEAD_FILTER, "verdict=ALLOW reason=" + kind.name().toLowerCase(
                        java.util.Locale.ROOT), tNs);
                return false;
            }

            if (!in.userUnlocked()) return false;

            PduFacts facts = facts(raw);
            int port = in.destPort() >= 0 ? in.destPort() : (facts.valid() ? facts.destPort() : -1);
            if (port == WapPush.PORT) return false;

            // One classification gives the class and its subtype (subtype rules); the enforcer does not
            // classify again on this thread. A PDU that does not parse is classified too: known damage is
            // CL-MALFORMED-SMS, the wrong message type or a parser error is CL-UNREADABLE-SMS.
            ProvisionalClassifier.Result prediction = ClassPredictor.predictResult(facts, false, false, null);
            ClassId predicted = prediction != null ? prediction.classId() : ClassId.NORMAL;
            String subtype = prediction != null ? prediction.subtype() : null;
            if (!EnforcementPoints.atHeadFilter(predicted)) return false;

            VerdictRecord record = enforcer.headFilter(predicted, subtype, facts, in.slot(), in.roaming(), raw);
            if (!record.blocked()) {
                host.publishVerdict(record, tNs);
                return false;
            }

            // The verdict is published only once the drop has happened. If dropFilteredSms throws, the message
            // is delivered (the catch below returns false), so a trace must not claim it was blocked.
            host.dropFilteredSms();
            host.publishVerdict(record, tNs);
            return true;
        } catch (Throwable t) {
            // Any failure delivers the message.
            warn("Firewall", "head filter error, message delivered: " + t.getClass().getSimpleName());
            host.onError(t);
            return false;
        }
    }

    private void warn(String tag, String message) {
        if (log != null) log.w(tag, message);
    }
}
