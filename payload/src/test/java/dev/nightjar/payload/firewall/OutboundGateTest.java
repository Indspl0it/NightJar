package dev.nightjar.payload.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.FateDetail;
import dev.nightjar.payload.capture.OutboundLedger;
import dev.nightjar.payload.inject.SentFailure;
import dev.nightjar.payload.outbound.OutboundKind;
import dev.nightjar.payload.outbound.OutboundPolicies;
import dev.nightjar.payload.outbound.OutboundPolicy;
import dev.nightjar.payload.outbound.OutboundRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Every send of a blocked package fails the caller's intent and never transmits. */
class OutboundGateTest {

    private static final String APP = "com.example.spy";
    private static final String OTHER = "com.example.other";

    private final List<Integer> codes = new ArrayList<>();

    @BeforeEach
    void defaultEmergencyList() {
        OutboundGate.setEmergencyNumbers(OutboundGate::isDefaultEmergency);
    }

    @AfterEach
    void reset() {
        OutboundGate.setSender(null);
        OutboundGate.setCallerPackages(null);
        OutboundGate.setEmergencyNumbers(null);
        OutboundPolicies.reset();
    }

    private OutboundGate gate(Set<String> blocked, boolean safeMode) {
        return new OutboundGate(() -> new OutboundGate.Policy(true, safeMode, blocked));
    }

    private static OutboundRequest request(
            OutboundKind kind, String pkg, Boolean persist, boolean stk, Object sentIntent) {
        return request(kind, pkg, persist, stk, sentIntent, "+15551234567");
    }

    private static OutboundRequest request(
            OutboundKind kind, String pkg, Boolean persist, boolean stk, Object sentIntent, String dest) {
        return new OutboundRequest("send" + kind, kind, pkg, null, 0, dest, null,
                kind == OutboundKind.DATA ? 2948 : -1, kind == OutboundKind.DATA ? -1 : 12,
                kind == OutboundKind.DATA ? 8 : -1, persist, stk, 10000, sentIntent, null);
    }

    private void recordingSender() {
        OutboundGate.setSender(
                (intent, code) -> {
                    codes.add(code);
                });
    }

    @Test
    void aHiddenSendFromABlockedAppIsStoppedAndTheIntentFails() {
        recordingSender();
        Object sent = new Object();

        OutboundPolicy.Decision decision =
                gate(Set.of(APP), false)
                        .decide(request(OutboundKind.TEXT, APP, Boolean.FALSE, false, sent));

        assertTrue(decision.block());
        assertEquals(-1L, decision.ruleId());
        assertEquals(FateDetail.OUTBOUND_BLOCK, decision.reason());
        assertEquals(APP, decision.pkg());
        assertEquals(ClassId.MO_UNPERSISTED, decision.classId());
        assertEquals(Boolean.TRUE, decision.sentIntentFailed());
        // The caller's sent intent is failed with RESULT_ERROR_GENERIC_FAILURE.
        assertEquals(List.of(SentFailure.RESULT_ERROR_GENERIC_FAILURE), codes);
        assertEquals(1, codes.get(0));
    }

    /** A visible persisted text (the sendTextMessage default) from a blocked package is blocked too. */
    @Test
    void aVisibleSendFromABlockedAppIsBlocked() {
        recordingSender();

        OutboundPolicy.Decision decision =
                gate(Set.of(APP), false)
                        .decide(request(OutboundKind.TEXT, APP, Boolean.TRUE, false, new Object()));

        assertTrue(decision.block(), "blocks every send class of a blocked package");
        assertEquals(ClassId.MO_VISIBLE, decision.classId());
        assertEquals(List.of(SentFailure.RESULT_ERROR_GENERIC_FAILURE), codes);
    }

    @Test
    void aStoredResendFromABlockedAppIsBlocked() {
        recordingSender();

        OutboundPolicy.Decision decision =
                gate(Set.of(APP), false)
                        .decide(request(OutboundKind.STORED, APP, null, false, new Object(), null));

        assertTrue(decision.block(), "a send without a destination argument has no emergency exception");
    }

    /** A send to an emergency number is never blocked, also from a blocked package. */
    @Test
    void anEmergencyDestinationIsAllowed() {
        recordingSender();
        OutboundGate gate = gate(Set.of(APP), false);

        assertFalse(gate.decide(request(OutboundKind.TEXT, APP, Boolean.TRUE, false, new Object(), "112")).block());
        assertFalse(gate.decide(request(OutboundKind.TEXT, APP, Boolean.FALSE, false, new Object(), "911")).block());
        assertFalse(gate.decide(request(OutboundKind.DATA, APP, null, false, new Object(), "+112")).block());
        OutboundGate.setEmergencyNumbers(n -> n.equals("100"));
        assertFalse(gate.decide(request(OutboundKind.TEXT, APP, Boolean.TRUE, false, new Object(), "100")).block(),
                "the framework list (SIM and network numbers) applies as well");
        assertTrue(codes.isEmpty(), "an allowed send does not fail the intent");
    }

    /** Fail open: an emergency check that throws allows the send. */
    @Test
    void anEmergencyCheckThatThrowsAllowsTheSend() {
        OutboundGate.setEmergencyNumbers(n -> {
            throw new IllegalStateException("synthetic");
        });

        assertFalse(gate(Set.of(APP), false)
                .decide(request(OutboundKind.TEXT, APP, Boolean.TRUE, false, new Object())).block());
    }

    /** The phone process itself is never blocked, also when its package is on the list. */
    @Test
    void thePhoneProcessIsNeverBlocked() {
        recordingSender();
        String phone = OutboundLedger.PHONE_PACKAGE;
        String internal = OutboundLedger.INTERNAL_PACKAGE;
        OutboundGate gate = gate(Set.of(phone, internal), false);

        assertFalse(gate.decide(request(OutboundKind.DATA, phone, null, false, new Object())).block());
        assertFalse(gate.decide(request(OutboundKind.TEXT, internal, Boolean.TRUE, false, new Object())).block());
        assertTrue(codes.isEmpty());
    }

    @Test
    void theDefaultEmergencyListIgnoresSeparatorsOnly() {
        assertTrue(OutboundGate.isDefaultEmergency("112"));
        assertTrue(OutboundGate.isDefaultEmergency("9-1-1"));
        assertTrue(OutboundGate.isDefaultEmergency("+112"));
        assertFalse(OutboundGate.isDefaultEmergency("1120"));
        assertFalse(OutboundGate.isDefaultEmergency("112#"));
        assertFalse(OutboundGate.isDefaultEmergency(""));
        assertFalse(OutboundGate.isDefaultEmergency(null));
    }

    @Test
    void dataRawStkAndVvmSendsAreBlocked() {
        recordingSender();
        OutboundGate gate = gate(Set.of(APP), false);

        assertTrue(gate.decide(request(OutboundKind.DATA, APP, null, false, new Object())).block());
        assertTrue(gate.decide(request(OutboundKind.RAW, APP, null, false, new Object(), null)).block());
        assertTrue(gate.decide(request(OutboundKind.TEXT, APP, null, true, new Object())).block());
        assertTrue(gate.decide(request(OutboundKind.VVM, APP, null, false, new Object())).block());
        assertEquals(4, codes.size());
    }

    @Test
    void anAppThatIsNotOnTheListIsAllowed() {
        recordingSender();

        OutboundPolicy.Decision decision =
                gate(Set.of(APP), false)
                        .decide(request(OutboundKind.DATA, OTHER, null, false, new Object()));

        assertFalse(decision.block());
        assertTrue(codes.isEmpty());
    }

    @Test
    void safeModeAllowsEverything() {
        recordingSender();

        OutboundPolicy.Decision decision =
                gate(Set.of(APP), true)
                        .decide(request(OutboundKind.DATA, APP, null, false, new Object()));

        assertFalse(decision.block(), "safe mode turns every block into an allow");
        assertTrue(codes.isEmpty());
    }

    @Test
    void aDisabledOutgoingMonitorHasNoOpinion() {
        recordingSender();
        OutboundGate disabled = new OutboundGate(() -> new OutboundGate.Policy(false, false, Set.of(APP)));

        assertFalse(disabled.decide(request(OutboundKind.DATA, APP, null, false, new Object())).block());
    }

    /** With the user firewall switch off, NightJar blocks nothing, also no outbound send. */
    @Test
    void theFirewallSwitchOffAllowsEverySend() {
        recordingSender();
        OutboundGate off = new OutboundGate(() -> new OutboundGate.Policy(true, false, Set.of(APP), false));

        assertFalse(off.decide(request(OutboundKind.DATA, APP, null, false, new Object())).block());
        assertTrue(codes.isEmpty(), "an allowed send does not fail the intent");
        OutboundGate on = new OutboundGate(() -> new OutboundGate.Policy(true, false, Set.of(APP), true));
        assertTrue(on.decide(request(OutboundKind.DATA, APP, null, false, new Object())).block());
    }

    @Test
    void theDecisionIsAllowWhenNoPolicyIsConfigured() {
        OutboundGate unconfigured = new OutboundGate(() -> OutboundGate.Policy.NONE);

        assertFalse(unconfigured.decide(request(OutboundKind.DATA, APP, null, false, new Object())).block());
    }

    @Test
    void aMissingIntentStillBlocksButIsReported() {
        OutboundGate gate = gate(Set.of(APP), false);

        OutboundPolicy.Decision decision =
                gate.decide(request(OutboundKind.DATA, APP, null, false, null));

        assertTrue(decision.block());
        assertEquals(Boolean.FALSE, decision.sentIntentFailed(), "fateDetail.sentIntentFailed reports the missing intent");
    }

    @Test
    void aCallerThatCannotBeResolvedIsNotBlocked() {
        OutboundGate gate = gate(Set.of(APP), false);
        OutboundGate.setCallerPackages(() -> null);

        assertFalse(gate.decide(request(OutboundKind.DATA, null, null, false, new Object())).block());
        assertFalse(gate.decide(request(OutboundKind.DATA, "", null, false, new Object())).block());

        OutboundGate.setCallerPackages(() -> {
            throw new IllegalStateException("no binder");
        });
        assertFalse(gate.decide(request(OutboundKind.DATA, null, null, false, new Object())).block());
    }

    @Test
    void aMissingPackageIsResolvedFromTheBinderCaller() {
        recordingSender();
        OutboundGate gate = gate(Set.of(APP), false);

        OutboundGate.setCallerPackages(() -> new String[] {OTHER, APP});
        assertTrue(gate.decide(request(OutboundKind.DATA, null, null, false, new Object())).block());

        OutboundGate.setCallerPackages(() -> new String[] {OTHER});
        assertFalse(gate.decide(request(OutboundKind.DATA, "", null, false, new Object())).block());
    }

    @Test
    void aNullRequestIsAllowed() {
        assertFalse(gate(Set.of(APP), false).decide(null).block());
    }

    @Test
    void sendClassFollowsTheE10T05Table() {
        assertEquals(ClassId.MO_DATA,
                OutboundGate.sendClass(request(OutboundKind.DATA, APP, null, false, null)));
        assertEquals(ClassId.MO_RAW,
                OutboundGate.sendClass(request(OutboundKind.RAW, APP, null, false, null)));
        assertEquals(ClassId.MO_UNPERSISTED,
                OutboundGate.sendClass(request(OutboundKind.TEXT, APP, Boolean.FALSE, false, null)));
        assertEquals(ClassId.MO_STK,
                OutboundGate.sendClass(request(OutboundKind.TEXT, APP, Boolean.TRUE, true, null)));
        assertEquals(ClassId.MO_VVM,
                OutboundGate.sendClass(request(OutboundKind.VVM, APP, null, false, null)));
        assertEquals(ClassId.MO_VISIBLE,
                OutboundGate.sendClass(request(OutboundKind.TEXT, APP, Boolean.TRUE, false, null)));
        assertEquals(ClassId.MO_VISIBLE,
                OutboundGate.sendClass(request(OutboundKind.STORED, APP, null, false, null)));
    }

    @Test
    void installReplacesTheProcessWidePolicyAndResetRestoresAllow() {
        OutboundPolicies.install(gate(Set.of(APP), false));
        assertTrue(OutboundPolicies.current().decide(request(OutboundKind.DATA, APP, null, false, new Object())).block());

        OutboundPolicies.reset();
        assertEquals(OutboundPolicy.ALLOW, OutboundPolicies.current());
        OutboundPolicies.install(null);
        assertEquals(OutboundPolicy.ALLOW, OutboundPolicies.current());
    }
}
