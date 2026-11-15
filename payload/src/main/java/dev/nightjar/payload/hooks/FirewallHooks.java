package dev.nightjar.payload.hooks;

import com.android.internal.telephony.InboundSmsHandler;
import com.android.internal.telephony.gsm.SmsMessage;
import dev.nightjar.contract.Hashes;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.capture.PduFacts;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.firewall.FilterInstaller;
import dev.nightjar.payload.firewall.FirewallPorts;
import dev.nightjar.payload.firewall.HookDecision;
import dev.nightjar.payload.firewall.MwiEnforcer;
import dev.nightjar.payload.firewall.NjSmsFilter;
import dev.nightjar.payload.firewall.UsimDdEnforcer;
import dev.nightjar.payload.firewall.VerdictRecord;
import dev.nightjar.payload.hook.Param;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Hook bodies for the enforcement points that the capture package does not own (T03, T05, T07):
 * HK-24 + HK-R2 (head filter installation), HK-15 (SIM data download), HK-23 (MWI suppression). HK-14 and HK-17 are
 * wired through {@link WapHooks#setPort} and {@link CbHooks#setPort} by {@code boot.PayloadRuntime}, using
 * {@link FirewallPorts}.
 *
 * <p>Every body is a thin adapter: the decision is made by the {@code firewall} classes, the framework call is made here.
 * No I/O, no JSON, no binder.
 *
 * <p>Threads: HK-24 runs on the creating thread and posts to the handler's looper; HK-15 and HK-23 run on the phone main
 * looper.
 */
public final class FirewallHooks {

    /** HK-15 returns 1 for a blocked or intercepted message; never −1 ("¬−1"). */
    public static final int USIM_DD_BLOCK_RESULT = 1;

    private final Observations out;
    private final Clock clock;
    private final FilterInstaller installer;
    private final Function<InboundSmsHandler, NjSmsFilter> filterFactory;
    private final UsimDdEnforcer usimDd;
    private final MwiEnforcer mwi;
    private final IntUnaryOperator slotOf;
    private final IntPredicate roamingOf;

    private static int unknownSlot(int subId) {
        return -1;
    }

    private static boolean notRoaming(int subId) {
        return false;
    }

    /** HK-15 and HK-23 receive no subscription of their own: they ask for the "no subscription" answer. */
    private static final int NO_SUB_ID = -1;

    /**
     * @param filterFactory builds the per-handler filter (it captures the handler); may be {@code null} when the
     *     firewall tier is unavailable, in which case HK-24 does nothing
     * @param slotOf slot of a subscription id ({@code Env#slot}), −1 when unknown
     * @param roamingOf roaming state of a subscription id ({@code Env#roaming})
     */
    public FirewallHooks(Observations out, Clock clock, FilterInstaller installer,
            Function<InboundSmsHandler, NjSmsFilter> filterFactory, UsimDdEnforcer usimDd, MwiEnforcer mwi,
            IntUnaryOperator slotOf, IntPredicate roamingOf) {
        this.out = out;
        this.clock = clock;
        this.installer = installer;
        this.filterFactory = filterFactory;
        this.usimDd = usimDd;
        this.mwi = mwi;
        this.slotOf = slotOf == null ? FirewallHooks::unknownSlot : slotOf;
        this.roamingOf = roamingOf == null ? FirewallHooks::notRoaming : roamingOf;
    }

    // ------------------------------------------------------------------ HK-24 / HK-R2

    /**
     * HK-24 after-hook on every {@code InboundSmsHandler} constructor: post the installation to that handler's looper so
     * the filter chain is replaced on the thread that owns it.
     */
    public void afterHandlerCreated(Param p) {
        Object self = p.thisObject();
        if (!(self instanceof InboundSmsHandler handler) || installer == null || filterFactory == null) return;
        try {
            NjSmsFilter filter = filterFactory.apply(handler);
            if (filter == null) return;
            handler.getHandler().post(() -> installer.install(handler, filter));
        } catch (Throwable t) {
            // Fail open: capture continues and this handler runs without the head filter.
        }
    }

    /**
     * Startup sweep: install on a handler that existed before the hooks were placed. Like HK-24, the
     * installation is posted to the handler's own looper, so the filter list is replaced on the thread that owns it
     * .
     */
    public void installNow(InboundSmsHandler handler) {
        if (handler == null || installer == null || filterFactory == null) return;
        try {
            NjSmsFilter filter = filterFactory.apply(handler);
            if (filter == null) return;
            handler.getHandler().post(() -> installer.install(handler, filter));
        } catch (Throwable t) {
            // Fail open.
        }
    }

    // ------------------------------------------------------------------ HK-15

    /**
     * HK-15 before-hook {@code handleUsimDataDownload(UsimServiceTable, SmsMessage, int, int)}: a block skips the
     * original with result {@code 1}, which sends a plain RP-ACK and no envelope to the SIM.
     *
     * <p>The framework type is {@code com.android.internal.telephony.gsm.SmsMessage} — the GSM implementation, not the
     * public {@code android.telephony.SmsMessage} — so the cast must name that class or it never matches and the rules
     * (and the canary token) would see nothing.
     */
    public void beforeUsimDataDownload(Param p) {
        if (usimDd == null) return;
        try {
            String originator = null;
            byte[] pdu = null;
            PduFacts facts = null;
            if (p.arg(1) instanceof SmsMessage message) {
                originator = message.getOriginatingAddress();
                pdu = message.getPdu();
                facts = facts(pdu);
            }
            HookDecision decision = usimDd.decide(originator, facts, pdu, slotOf.applyAsInt(NO_SUB_ID),
                    roamingOf.test(NO_SUB_ID));
            publish("usim", decision);
            if (pdu != null && decision.verdict() != null && decision.classId() != null) {
                // Keyed, so it joins the event (the keyless branch stage above cannot): the canary's usim.download
                // stage and its block verdict are what the self-test and the Appendix F trace read.
                String key = CaptureHooks.keyFor(pdu);
                long now = clock.elapsedNs();
                out.offer(Observation.stage(key, Hashes.tpduKey(pdu), -1, TraceStage.USIM_DOWNLOAD, now,
                        decision.verdict().traceDetail()));
                if (decision.skip()) {
                    out.offer(Observation.verdict(key, Hashes.tpduKey(pdu), -1, decision.verdict().action(),
                            decision.verdict().ruleId(), decision.verdict().reasonWire(), now));
                }
            }
            if (decision.skip()) {
                p.setResult(decision.skipResult() == 0 ? USIM_DD_BLOCK_RESULT : decision.skipResult());
            }
        } catch (Throwable t) {
            // Fail open: the original runs and the SIM exchange proceeds unchanged.
        }
    }

    /** Parses the radio PDU, falling back to the bare TPDU form (some builds hand over the stripped bytes). */
    private static PduFacts facts(byte[] pdu) {
        if (pdu == null) return null;
        PduFacts radio = PduFacts.parseRadio(pdu);
        if (radio.valid()) return radio;
        PduFacts bare = PduFacts.parse(pdu);
        return bare.valid() ? bare : radio;
    }

    // ------------------------------------------------------------------ HK-23

    /**
     * HK-23 before-hook {@code updateMessageWaitingIndicator(int)}: suppressing the call is the only way to block an MWI
     *  and the only way to keep the voicemail icon unchanged during a self-test.
     */
    public void beforeMwiIndicator(Param p) {
        if (mwi == null) return;
        try {
            HookDecision decision = mwi.decide(null, null, slotOf.applyAsInt(NO_SUB_ID),
                    roamingOf.test(NO_SUB_ID));
            publish("mwi", decision);
            if (decision.skip()) {
                p.skip();
                out.offer(suppressed(decision.verdict()));
            } else {
                out.offer(Observation.stage(null, null, -1, TraceStage.MWI_INDICATOR, clock.elapsedNs(), "applied"));
            }
        } catch (Throwable t) {
            // Fail open: the indicator update runs as stock.
        }
    }

    /**
     * The HK-23 "suppressed" stage. A hold carries its verdict: a discard MWI never reaches HK-08, so this is
     * the only verdict its event gets, and the event then seals as held, not blocked. A block or a canary keeps the
     * plain stage, so the canary still seals as FT-SYSTEM (Appendix F).
     */
    private Observation suppressed(VerdictRecord record) {
        long now = clock.elapsedNs();
        if (record != null && record.action() == Verdict.QUARANTINE) {
            return Observation.stageWithVerdict(null, TraceStage.MWI_INDICATOR, now, "suppressed", record.action(),
                    record.ruleId(), record.reasonWire());
        }
        return Observation.stage(null, null, -1, TraceStage.MWI_INDICATOR, now, "suppressed");
    }

    private void publish(String what, HookDecision decision) {
        VerdictRecord record = decision.verdict();
        if (record == null || decision.classId() == null) return;
        out.offer(Observation.stage(null, null, -1, TraceStage.HANDLER_RADIO_SPECIFIC, clock.elapsedNs(),
                "branch=" + ("usim".equals(what) ? "usimdd" : "mwi") + " " + record.traceDetail()));
    }
}
