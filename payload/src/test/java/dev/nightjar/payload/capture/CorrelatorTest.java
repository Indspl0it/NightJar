package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Ipc;
import dev.nightjar.contract.Subtype;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Transport;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.store.OutboundEvent;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeEnv;
import dev.nightjar.payload.testing.FakeSpool;
import dev.nightjar.payload.testing.FakeTokens;
import dev.nightjar.payload.testing.Pdu;
import dev.nightjar.pduparse.Anomalies;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Correlation, sealing, multipart groups and pending unlock. */
class CorrelatorTest {

    private FakeClock clock;
    private FakeEnv env;
    private FakeSpool spool;
    private FakeTokens tokens;
    private Counters counters;
    private Correlator correlator;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(1_000_000_000L);
        env = new FakeEnv();
        spool = new FakeSpool();
        tokens = new FakeTokens();
        counters = new Counters();
        correlator = new Correlator(clock, new Sealer(env::userUnlocked), counters, env, spool, tokens,
                uid -> new String[] {"com.example.injector"}, "session-1", "1.0.0", 7);
    }

    private static String key(byte[] pdu) {
        return CaptureHooksKey.of(pdu);
    }

    /** Mirrors {@code hooks.CaptureHooks.keyFor} without touching android classes. */
    static final class CaptureHooksKey {
        static String of(byte[] pdu) {
            return dev.nightjar.contract.Hashes.tpduKey(dev.nightjar.contract.Hashes.stripSmsc(pdu));
        }
    }

    private Observation raw(byte[] pdu, int phoneId) {
        return Observation.raw(key(pdu), dev.nightjar.contract.Hashes.tpduKey(pdu), pdu, "3gpp",
                Transport.CS, phoneId, -1, -1, -1, false, clock.elapsedNs(), clock.wallMs());
    }

    private Observation dispatch(byte[] pdu, int phoneId, int source, int result) {
        return Observation.dispatch(key(pdu), dev.nightjar.contract.Hashes.tpduKey(pdu), phoneId,
                source, result, -1, clock.elapsedNs());
    }

    private List<OutboundEvent> tick() {
        clock.advanceMs(30_000);
        return correlator.tick(clock.elapsedNs());
    }

    @Test
    void rawAndDispatchJoinOnTheTpduKey() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 0, 1));
        assertTrue(correlator.drainOutbox().isEmpty(), "no fate yet");
        List<OutboundEvent> out = tick();
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"ackResult\":1"), json);
        // Android accepted the message and no consumer reported it.
        assertTrue(json.contains("\"fate\":\"FT-ACCEPTED\""), json);
        assertTrue(json.contains("\"transport\":\"CS\""), json);
    }

    @Test
    void identicalPdusBackToBackAreTwoEvents() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(raw(pdu, 0));
        List<OutboundEvent> out = tick();
        assertEquals(2, out.size());
        assertFalse(out.get(0).eventId().equals(out.get(1).eventId()));
    }

    @Test
    void differingHashesFallBackToArrivalOrderAndFlagIt() {
        byte[] radio = Pdu.deliver().build();
        // A different TPDU (different DCS) so the hashes differ, still the same message by arrival order.
        byte[] ims = Pdu.deliver().dcs(0x00).ud(new byte[] {9, 9, 9}).build();
        correlator.accept(raw(radio, 0));
        correlator.accept(dispatch(ims, 0, 0, 1));
        List<OutboundEvent> out = tick();
        assertEquals(1, out.size(), "joined by phoneId + arrival order");
        assertTrue(out.get(0).json().contains("\"corrFallback\":true"), out.get(0).json());
    }

    @Test
    void statusReportSealsImmediatelyAsSystem() {
        byte[] pdu = Pdu.deliver().statusReport().build();
        correlator.accept(Observation.raw(key(pdu), null, pdu, "3gpp", Transport.CS, 0, -1, -1, -1,
                true, clock.elapsedNs(), clock.wallMs()));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-SYSTEM\""), out.get(0).json());
        assertTrue(out.get(0).json().contains("\"provisionalClass\":\"CL-STATUS\""));
    }

    @Test
    void typeZeroBranchSealsSystem() {
        byte[] pdu = Pdu.deliver().pid(0x40).build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(Observation.stage(key(pdu), TraceStage.HANDLER_RADIO_SPECIFIC,
                clock.elapsedNs(), "branch=type0"));
        // The radio-specific stage precedes the dispatch observation; the event seals once that has arrived, so an
        // injected canary is flagged instead of being emitted bare.
        assertEquals(0, correlator.drainOutbox().size());
        correlator.accept(Observation.dispatch(key(pdu), null, 0, 0, 0, -1, clock.elapsedNs()));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-SYSTEM\""), json);
        assertTrue(json.contains("\"provisionalClass\":\"CL-SILENT\""), json);
    }

    @Test
    void blockedVerdictSealsBlocked() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(Observation.verdict(key(pdu), Verdict.BLOCK, 7L, null, clock.elapsedNs()));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-BLOCKED\""), out.get(0).json());
        assertTrue(out.get(0).json().contains("\"ruleId\":7"));
    }

    @Test
    void quarantineSealsQuarantined() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(Observation.verdict(key(pdu), Verdict.QUARANTINE, -1L, null,
                clock.elapsedNs()));
        assertTrue(correlator.drainOutbox().get(0).json().contains("\"fate\":\"FT-QUARANTINED\""));
    }

    @Test
    void unmatchedSourceTwoIsInjectedWithTheCallingPackage() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(Observation.inject(key(pdu), 10_123, clock.elapsedNs()));
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 2, 1));
        String json = tick().get(0).json();
        assertTrue(json.contains("\"provisionalClass\":\"CL-INJECTED\""), json);
        assertTrue(json.contains("\"callingPackage\":\"com.example.injector\""), json);
    }

    @Test
    void sourceTwoWithoutAttributionIsInjectedUnknown() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 2, 1));
        String json = tick().get(0).json();
        assertTrue(json.contains("\"callingPackage\":\"unknown\""), json);
        assertTrue(json.contains("\"transport\":\"INJECTED\""), json);
    }

    @Test
    void releaseTokenMarksTheEventAndIsConsumedOnce() {
        byte[] pdu = Pdu.deliver().build();
        tokens.add(key(pdu), new InjectionTokens.Match(InjectionTokens.Kind.RELEASE, "evt-1", null, null));
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 2, 1));
        String json = correlator.drainOutbox().get(0).json();
        assertTrue(json.contains("\"releaseOf\":\"evt-1\""), json);
        assertTrue(json.contains("\"fate\":\"FT-RELEASED\""), json);
        assertEquals(0, tokens.remaining());
    }

    @Test
    void canaryTokenSetsSelfTestFlags() {
        byte[] pdu = Pdu.deliver().build();
        tokens.add(key(pdu), new InjectionTokens.Match(InjectionTokens.Kind.CANARY, null, "run-9",
                "CL-WAP-CP"));
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 2, 1));
        String json = tick().get(0).json();
        assertTrue(json.contains("\"selfTest\":true"), json);
        assertTrue(json.contains("\"runId\":\"run-9\""), json);
        assertTrue(json.contains("\"canaryClass\":\"CL-WAP-CP\""), json);
        assertTrue(json.contains("\"transport\":\"INJECTED\""), json);
        // A canary that met no filter is FT-SHOWN and still reaches the ring (not a counter).
        assertTrue(json.contains("\"fate\":\"FT-SHOWN\""), json);
    }

    @Test
    void storageFullResultSealsNackStorage() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 0, 3));
        assertTrue(correlator.drainOutbox().get(0).json().contains("\"fate\":\"FT-NACK-STORAGE\""));
    }

    /** Each refusal code of dispatchMessage seals at once as FT-REJECTED and keeps the code. */
    @Test
    void aRejectedDispatchSealsAtOnceAndKeepsTheResult() {
        for (int code : new int[] {2, 4, 5, 6, 7, 8, 9, 10, 11}) {
            byte[] pdu = Pdu.deliver().originator("+1555010" + code).build();
            correlator.accept(raw(pdu, 0));
            correlator.accept(dispatch(pdu, 0, 0, code));
            List<OutboundEvent> out = correlator.drainOutbox();
            assertEquals(1, out.size(), "code " + code);
            String json = out.get(0).json();
            assertTrue(json.contains("\"fate\":\"FT-REJECTED\""), json);
            assertTrue(json.contains("\"ackResult\":" + code), json);
        }
    }

    /** A message that Android never dispatches is FT-NOT-DISPATCHED after the timeout, not unknown. */
    @Test
    void aRawWithoutDispatchIsNotDispatchedAfterTheTimeout() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        assertTrue(correlator.drainOutbox().isEmpty());
        List<OutboundEvent> out = tick();
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-NOT-DISPATCHED\""), json);
        assertTrue(json.contains("seal.timeout"), json);
    }

    @Test
    void simMarkClassesTheNextDispatchAsSimStored() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(Observation.simMark(key(pdu), clock.elapsedNs()));
        correlator.accept(raw(pdu, 0));
        List<OutboundEvent> sealed = correlator.drainOutbox();
        // Not sealed yet: drive the timeout.
        String json = sealed.isEmpty() ? tick().get(0).json() : sealed.get(0).json();
        assertTrue(json.contains("\"provisionalClass\":\"CL-SIM-STORED\""), json);
    }

    @Test
    void vvmConsumerGivesTheClassOfANormalPdu() throws Exception {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(Observation.stage(key(pdu), TraceStage.FILTER_VVM, clock.elapsedNs(),
                "consumed=true"));
        String json = correlator.drainOutbox().get(0).json();
        assertTrue(json.contains("\"finalClass\":\"CL-VVM\""), json);
        assertTrue(json.contains("\"fate\":\"FT-VVM\""), json);
        assertFalse(new JSONObject(json).has(Ipc.EventKey.EXTRA_SUBTYPES), json);
    }

    @Test
    void vvmOnADataPortIsVvmWithThePortAsAnExtra() throws Exception {
        // A carrier voicemail SMS must not show as an unknown data port.
        byte[] pdu = Pdu.deliver().dcs(0x04)
                .udhBytes(new byte[] {6, 0x05, 4, 0x3E, (byte) 0x81, 0, 0})
                .ud(new byte[] {1, 2})
                .build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(Observation.stage(key(pdu), TraceStage.FILTER_VVM, clock.elapsedNs(),
                "consumed=true"));
        String json = correlator.drainOutbox().get(0).json();
        JSONObject o = new JSONObject(json);
        assertEquals("CL-VVM", o.getString("finalClass"), json);
        assertEquals("CL-DATA-PORT", o.getString("provisionalClass"), json);
        assertEquals("FT-VVM", o.getString("fate"), json);
        assertFalse(o.has(Ipc.EventKey.SUBTYPE), json);
        JSONArray extras = o.getJSONArray(Ipc.EventKey.EXTRA_SUBTYPES);
        assertEquals(1, extras.length(), json);
        assertEquals("CL-DATA-PORT:" + Subtype.DataPort.UNKNOWN, extras.getString(0), json);
    }

    @Test
    void vvmConsumerIsAnExtraOfAPduClass() throws Exception {
        // Rule 9: a consumer class wins only over CL-NORMAL. A Type 0 PDU stays CL-SILENT.
        byte[] pdu = Pdu.deliver().pid(0x40).build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(Observation.stage(key(pdu), TraceStage.FILTER_VVM, clock.elapsedNs(),
                "consumed=true"));
        String json = correlator.drainOutbox().get(0).json();
        JSONObject o = new JSONObject(json);
        assertEquals("CL-SILENT", o.getString("finalClass"), json);
        assertEquals("FT-VVM", o.getString("fate"), json);
        assertEquals(Subtype.Silent.TYPE0, o.getString(Ipc.EventKey.SUBTYPE), json);
        JSONArray extras = o.getJSONArray(Ipc.EventKey.EXTRA_SUBTYPES);
        assertEquals(1, extras.length(), json);
        assertEquals("CL-VVM", extras.getString(0), json);
    }

    @Test
    void theEventCarriesTheSubtypeExtrasAndAnomalies() throws Exception {
        // Class 0, 8-bit, with two port elements (16001 then 16002): AN-IE-DUP.
        byte[] pdu = Pdu.deliver().dcs(0x14)
                .udhBytes(new byte[] {12, 0x05, 4, 0x3E, (byte) 0x81, 0, 0, 0x05, 4, 0x3E, (byte) 0x82, 0, 0})
                .ud(new byte[] {1, 2})
                .build();
        correlator.accept(raw(pdu, 0));
        JSONObject o = new JSONObject(tick().get(0).json());
        assertEquals("CL-FLASH", o.getString("finalClass"), o.toString());
        assertEquals(Subtype.Flash.CLASS0, o.getString(Ipc.EventKey.SUBTYPE));
        // The last port element wins, so the extra names port 16002.
        JSONArray extras = o.getJSONArray(Ipc.EventKey.EXTRA_SUBTYPES);
        assertEquals("CL-DATA-PORT:" + Subtype.DataPort.UNKNOWN, extras.getString(0), o.toString());
        assertEquals(16002, o.getInt("destPort"), o.toString());
        JSONArray anomalies = o.getJSONArray(Ipc.EventKey.ANOMALIES);
        assertEquals(AnomalyCode.IE_DUP.wire(), anomalies.getString(0), o.toString());
    }

    @Test
    void aRepeatedPartIsFlaggedConcatDup() throws Exception {
        byte[] part1 = Pdu.deliver().udh(9, 2, 1, -1).build();
        byte[] part2 = Pdu.deliver().udh(9, 2, 2, -1).build();
        correlator.accept(raw(part1, 0));
        correlator.accept(raw(part1, 0));
        List<OutboundEvent> first = tick();
        // Every revision of the group is the same row (group event id) and carries the group's
        // codes. The app keeps the highest revision; that one must name AN-CONCAT-DUP. The second
        // arrival is its own message with its own outcome, so it is one more row, never a revision of the group.
        java.util.Set<String> ids = new java.util.HashSet<>();
        JSONObject last = null;
        JSONObject repeat = null;
        for (OutboundEvent ev : first) {
            JSONObject o = new JSONObject(ev.json());
            JSONObject detail = o.optJSONObject("fateDetail");
            if (detail == null || !detail.has("have")) {
                assertNull(repeat, "one row for the repeated copy");
                repeat = o;
                continue;
            }
            ids.add(o.getString("eventId"));
            if (last == null || o.getInt("revision") > last.getInt("revision")) last = o;
        }
        assertEquals(1, ids.size(), "one row for the group: " + first.size() + " events");
        assertNotNull(repeat, "the repeated copy is sealed on its own");
        assertFalse(ids.contains(repeat.getString("eventId")));
        assertEquals("FT-NOT-DISPATCHED", repeat.getString("fate"), "no dispatch result was seen for the copy");
        assertNotNull(last);
        assertTrue(last.toString().contains(AnomalyCode.CONCAT_DUP.wire()), "the repeated part is reported: " + last);
        correlator.accept(raw(part2, 0));
        List<OutboundEvent> second = tick();
        assertEquals(1, second.size());
        String json = second.get(0).json();
        assertTrue(json.contains("\"have\":2"), json);
        assertTrue(json.contains(AnomalyCode.CONCAT_DUP.wire()), "the sealed group keeps the code: " + json);
    }

    /** Android rejects a repeated part as a duplicate; only the copy gets FT-REJECTED. */
    @Test
    void aRejectedRepeatOfAPartIsItsOwnEventAndTheGroupKeepsItsFate() throws Exception {
        byte[] part1 = Pdu.deliver().udh(11, 2, 1, -1).build();
        byte[] part2 = Pdu.deliver().udh(11, 2, 2, -1).build();
        correlator.accept(raw(part1, 0));
        correlator.accept(dispatch(part1, 0, 0, 1));
        correlator.accept(raw(part1, 0));
        correlator.accept(dispatch(part1, 0, 0, 5));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size(), "only the rejected copy is sealed");
        JSONObject copy = new JSONObject(out.get(0).json());
        assertEquals("FT-REJECTED", copy.getString("fate"));
        assertEquals(5, copy.getInt("ackResult"));
        assertFalse(copy.has("fateDetail") && copy.getJSONObject("fateDetail").has("have"), copy.toString());

        correlator.accept(raw(part2, 0));
        correlator.accept(dispatch(part2, 0, 0, 1));
        List<OutboundEvent> group = tick();
        assertEquals(1, group.size());
        JSONObject g = new JSONObject(group.get(0).json());
        assertNotEquals(copy.getString("eventId"), g.getString("eventId"));
        assertEquals("FT-ACCEPTED", g.getString("fate"), g.toString());
        assertEquals(1, g.getInt("ackResult"));
        assertTrue(g.toString().contains("\"have\":2"), g.toString());
        assertTrue(g.toString().contains(AnomalyCode.CONCAT_DUP.wire()), "the group still names the repeat: " + g);
    }

    /**
     * A group member that Android rejects (a duplicate of a part its raw table had before a phone process
     * restart) does not decide the group fate. The other member does; only a group whose open members are all rejected
     * is FT-REJECTED.
     */
    @Test
    void aRejectedMemberDoesNotDecideTheGroupFate() throws Exception {
        byte[] part1 = Pdu.deliver().udh(12, 2, 1, -1).build();
        byte[] part2 = Pdu.deliver().udh(12, 2, 2, -1).build();
        correlator.accept(raw(part1, 0));
        correlator.accept(dispatch(part1, 0, 0, 5));
        List<OutboundEvent> early = correlator.drainOutbox();
        assertEquals(1, early.size());
        assertEquals("FT-INCOMPLETE", new JSONObject(early.get(0).json()).getString("fate"), "the part is not the group");

        correlator.accept(raw(part2, 0));
        correlator.accept(dispatch(part2, 0, 0, 1));
        assertTrue(correlator.drainOutbox().isEmpty(), "the rejected part does not seal the complete group");
        List<OutboundEvent> group = tick();
        assertEquals(1, group.size());
        JSONObject g = new JSONObject(group.get(0).json());
        assertEquals(early.get(0).eventId(), g.getString("eventId"));
        assertEquals("FT-ACCEPTED", g.getString("fate"), g.toString());
        assertTrue(g.toString().contains("\"have\":2"), g.toString());
    }

    /** When Android rejects every member, the group is FT-REJECTED. */
    @Test
    void aGroupWhoseMembersAreAllRejectedIsRejected() throws Exception {
        byte[] part1 = Pdu.deliver().udh(13, 2, 1, -1).build();
        byte[] part2 = Pdu.deliver().udh(13, 2, 2, -1).build();
        correlator.accept(raw(part1, 0));
        correlator.accept(dispatch(part1, 0, 0, 5));
        correlator.drainOutbox();
        correlator.accept(raw(part2, 0));
        correlator.accept(dispatch(part2, 0, 0, 5));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size());
        assertEquals("FT-REJECTED", new JSONObject(out.get(0).json()).getString("fate"), out.get(0).json());
    }

    /** Two arrivals of the same single PDU; the rejected second copy never changes the first. */
    @Test
    void aRejectedSecondCopyLeavesTheFirstArrivalUnchanged() throws Exception {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 0, 1));
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 0, 5));
        List<OutboundEvent> rejected = correlator.drainOutbox();
        assertEquals(1, rejected.size());
        JSONObject second = new JSONObject(rejected.get(0).json());
        assertEquals("FT-REJECTED", second.getString("fate"));
        assertEquals(5, second.getInt("ackResult"));

        List<OutboundEvent> later = tick();
        assertEquals(1, later.size());
        JSONObject first = new JSONObject(later.get(0).json());
        assertNotEquals(second.getString("eventId"), first.getString("eventId"));
        assertEquals(1, first.getInt("ackResult"));
        assertEquals("FT-ACCEPTED", first.getString("fate"));
    }

    /** A second dispatch of a PDU whose event already has a result is another copy, not a revision. */
    @Test
    void aSecondDispatchWithoutASecondRawIsANewEvent() throws Exception {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 0, 1));
        correlator.accept(dispatch(pdu, 0, 0, 5));
        List<OutboundEvent> rejected = correlator.drainOutbox();
        assertEquals(1, rejected.size());
        assertEquals("FT-REJECTED", new JSONObject(rejected.get(0).json()).getString("fate"));
        JSONObject first = new JSONObject(tick().get(0).json());
        assertEquals(1, first.getInt("ackResult"));
        assertEquals("FT-ACCEPTED", first.getString("fate"));
    }

    @Test
    void aReplayAfterUnlockIsNotARepeatedPart() {
        env.unlocked = false;
        byte[] pdu = Pdu.deliver().udh(1, 1, 1, 2948).build();
        correlator.accept(raw(pdu, 0));
        clock.advanceMs(5_000);
        assertTrue(correlator.tick(clock.elapsedNs()).get(0).json().contains("FT-PENDING-UNLOCK"));

        env.unlocked = true;
        clock.advanceMs(1_000);
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 0, 1));
        List<OutboundEvent> out = tick();
        assertEquals(1, out.size());
        assertTrue(out.get(0).json().contains("\"replay\":true"), out.get(0).json());
        assertFalse(out.get(0).json().contains(AnomalyCode.CONCAT_DUP.wire()), out.get(0).json());
    }

    @Test
    void carrierConsumerMapsToRcsForTheRcsPackage() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(Observation.stage(key(pdu), TraceStage.FILTER_CARRIER, clock.elapsedNs(),
                "consumed=true pkg=com.google.android.ims"));
        String json = correlator.drainOutbox().get(0).json();
        assertTrue(json.contains("\"finalClass\":\"CL-RCS-PROV\""), json);
        assertTrue(json.contains("\"fate\":\"FT-CARRIER\""), json);
        // The consumer app reaches the app through fateDetail.package.
        assertTrue(json.contains("\"package\":\"com.google.android.ims\""), json);
    }

    @Test
    void dispatchIntentShownMakesNormalCounterOnly() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(Observation.stage(key(pdu), TraceStage.HANDLER_DISPATCH_INTENT,
                clock.elapsedNs(), "action=android.provider.Telephony.SMS_DELIVER pkg=com.google.android.apps.messaging"));
        assertTrue(correlator.drainOutbox().isEmpty(), "CL-NORMAL + FT-SHOWN must not enter the ring");
        assertEquals(1, correlator.counterOnlyCount());
        assertEquals(1, counters.total());
        assertTrue(counters.countersJson().contains("\"classId\":\"CL-NORMAL\""));
        assertTrue(counters.countersJson().contains("\"direction\":\"IN\""));
    }

    @Test
    void blockedNormalKeepsItsPdus() {
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(Observation.verdict(key(pdu), Verdict.BLOCK, 3L, null, clock.elapsedNs()));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size(), "a blocked normal SMS keeps its PDUs for release");
        assertTrue(out.get(0).json().contains("\"pdus\""));
        assertEquals(0, counters.total());
    }

    @Test
    void incompleteMultipartSealsIncompleteWithPurgeAt() {
        byte[] part1 = Pdu.deliver().udh(9, 2, 1, -1).build();
        correlator.accept(raw(part1, 0));
        List<OutboundEvent> out = tick();
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-INCOMPLETE\""), json);
        assertTrue(json.contains("\"purgeAt\""), json);
        assertTrue(json.contains("\"have\":1"), json);
        assertTrue(json.contains("\"total\":2"), json);
    }

    @Test
    void completedMultipartEmitsOneEventWithRevisionTwo() {
        byte[] part1 = Pdu.deliver().udh(9, 2, 1, -1).build();
        byte[] part2 = Pdu.deliver().udh(9, 2, 2, -1).build();
        correlator.accept(raw(part1, 0));
        List<OutboundEvent> first = tick();
        assertEquals(1, first.size());
        assertEquals(1, first.get(0).revision());
        correlator.accept(raw(part2, 0));
        List<OutboundEvent> second = tick();
        assertEquals(1, second.size());
        assertEquals(2, second.get(0).revision());
        assertEquals(first.get(0).eventId(), second.get(0).eventId(), "same eventId across revisions");
        assertTrue(second.get(0).json().contains("\"have\":2"));
        assertFalse(second.get(0).json().contains("FT-INCOMPLETE"));
    }

    @Test
    void partsOneSecondApartGroupWithoutAnIncompleteEmission() {
        byte[] part1 = Pdu.deliver().udh(4, 2, 1, -1).build();
        byte[] part2 = Pdu.deliver().udh(4, 2, 2, -1).build();
        correlator.accept(raw(part1, 0));
        clock.advanceMs(1_000);
        correlator.accept(raw(part2, 0));
        correlator.accept(Observation.verdict(key(part1), Verdict.BLOCK, 1L, null,
                clock.elapsedNs()));
        // Both parts were blocked; the group is complete, so it must seal once, not also time out.
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size(), "a complete group emits one event");
        assertEquals(1, out.get(0).revision());
        String json = out.get(0).json();
        assertTrue(json.contains("\"have\":2"), json);
        assertTrue(json.contains("\"fate\":\"FT-BLOCKED\""), json);
        assertEquals(2, countOccurrences(json, "\"seq\":"), json);
        assertEquals(0, correlator.groups().size());
        assertTrue(tick().isEmpty(), "nothing else is emitted later");
    }

    private static int countOccurrences(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    @Test
    void aConsumerFateOfOnePartIsUsedForTheWholeGroup() {
        byte[] part1 = Pdu.deliver().udh(4, 2, 1, -1).build();
        byte[] part2 = Pdu.deliver().udh(4, 2, 2, -1).build();
        correlator.accept(raw(part1, 0));
        clock.advanceMs(1_000);
        correlator.accept(raw(part2, 0));
        correlator.accept(Observation.verdict(key(part2), Verdict.QUARANTINE, -1L, null,
                clock.elapsedNs()));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-QUARANTINED\""), out.get(0).json());
    }

    @Test
    void outOfRangeConcatSequenceIsNotGrouped() {
        byte[] pdu = Pdu.deliver().udh(9, 0, 1, -1).build();
        correlator.accept(raw(pdu, 0));
        // total=0 is not a group: the part seals on its own timeout as a plain message.
        String json = tick().get(0).json();
        assertFalse(json.contains("purgeAt"), json);
    }

    @Test
    void orphanedGroupsArePurgedAfterTheRetention() {
        byte[] part1 = Pdu.deliver().udh(9, 3, 1, -1).build();
        correlator.accept(raw(part1, 0));
        clock.advanceMs(7 * 24L * 60 * 60 * 1000 + 1);
        List<OutboundEvent> out = correlator.tick(clock.elapsedNs());
        assertEquals(0, correlator.groups().size(), "the orphan group is dropped");
        assertFalse(out.isEmpty());
        assertTrue(out.get(out.size() - 1).json().contains("\"fate\":\"FT-INCOMPLETE\""));
    }

    @Test
    void aGroupStillIncompleteAfter24HoursIsMultipartIncomplete() throws Exception {
        byte[] part1 = Pdu.deliver().udh(9, 2, 1, -1).build();
        byte[] part2 = Pdu.deliver().udh(9, 2, 2, -1).build();
        correlator.accept(raw(part1, 0));
        OutboundEvent first = tick().get(0);
        assertTrue(first.json().contains("\"finalClass\":\"CL-NORMAL\""), first.json());

        clock.advanceMs(Correlator.INCOMPLETE_AFTER_MS - 30_000 - 1);
        assertTrue(correlator.tick(clock.elapsedNs()).isEmpty(), "not yet 24 h after the first part");
        clock.advanceMs(1);
        List<OutboundEvent> out = correlator.tick(clock.elapsedNs());
        assertEquals(1, out.size());
        JSONObject o = new JSONObject(out.get(0).json());
        assertEquals("CL-MULTIPART-INCOMPLETE", o.getString("finalClass"), o.toString());
        assertEquals("FT-INCOMPLETE", o.getString("fate"), o.toString());
        assertEquals(first.eventId(), out.get(0).eventId(), "a revision of the same event");
        assertEquals(2, out.get(0).revision());
        assertFalse(o.has(Ipc.EventKey.EXTRA_SUBTYPES), "CL-NORMAL parts add no extra: " + o);
        assertEquals(1, correlator.groups().size(), "the parts stay until the purge");
        assertTrue(correlator.tick(clock.elapsedNs()).isEmpty(), "sealed once");

        // A late part still completes the group, which gets the class of its parts again.
        correlator.accept(raw(part2, 0));
        List<OutboundEvent> late = tick();
        assertEquals(1, late.size());
        assertTrue(late.get(0).json().contains("\"finalClass\":\"CL-NORMAL\""), late.get(0).json());
        assertTrue(late.get(0).json().contains("\"have\":2"), late.get(0).json());
        assertEquals(0, correlator.groups().size());
    }

    @Test
    void anIncompleteGroupKeepsTheClassOfItsPartsAsAnExtra() throws Exception {
        byte[] part1 = Pdu.deliver().pid(0x40).udh(3, 2, 1, -1).build();
        correlator.accept(raw(part1, 0));
        correlator.tick(clock.elapsedNs());
        clock.advanceMs(Correlator.INCOMPLETE_AFTER_MS);
        List<OutboundEvent> out = correlator.tick(clock.elapsedNs());
        JSONObject o = new JSONObject(out.get(out.size() - 1).json());
        assertEquals("CL-MULTIPART-INCOMPLETE", o.getString("finalClass"), o.toString());
        JSONArray extras = o.getJSONArray(Ipc.EventKey.EXTRA_SUBTYPES);
        assertEquals("CL-SILENT:" + Subtype.Silent.TYPE0, extras.getString(0), o.toString());
    }

    @Test
    void theGroupTableIsCapped() {
        for (int i = 0; i < MultipartGroups.MAX_GROUPS + 5; i++) {
            correlator.accept(raw(Pdu.deliver().udh(i & 0xFF, 2, 1, -1).build(), 0));
            clock.advanceMs(1);
        }
        correlator.tick(clock.elapsedNs());
        assertTrue(correlator.groups().size() <= MultipartGroups.MAX_GROUPS);
    }

    @Test
    void pendingUnlockSealsAfterFiveSecondsWhileLocked() {
        env.unlocked = false;
        byte[] pdu = Pdu.deliver().udh(1, 1, 1, 2948).build();
        correlator.accept(raw(pdu, 0));
        assertTrue(correlator.drainOutbox().isEmpty());
        clock.advanceMs(5_000);
        List<OutboundEvent> out = correlator.tick(clock.elapsedNs());
        assertEquals(1, out.size());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-PENDING-UNLOCK\""), out.get(0).json());
        assertEquals(1, correlator.pendingUnlockCount());
        assertEquals(1, spool.size(), "the pending entry is mirrored to the spool");
    }

    @Test
    void replayAfterUnlockUpdatesTheSameEvent() {
        env.unlocked = false;
        byte[] pdu = Pdu.deliver().udh(1, 1, 1, 2948).build();
        correlator.accept(raw(pdu, 0));
        clock.advanceMs(5_000);
        OutboundEvent pending = correlator.tick(clock.elapsedNs()).get(0);
        assertTrue(pending.json().contains("FT-PENDING-UNLOCK"));

        env.unlocked = true;
        clock.advanceMs(1_000);
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 0, 1));
        List<OutboundEvent> out = tick();
        assertEquals(1, out.size());
        assertEquals(pending.eventId(), out.get(0).eventId());
        assertEquals(2, out.get(0).revision());
        assertTrue(out.get(0).json().contains("\"replay\":true"));
        assertEquals(0, correlator.pendingUnlockCount());
    }

    @Test
    void unlockReplayStageLinksToThePendingEventWithoutARaw() {
        env.unlocked = false;
        byte[] pdu = Pdu.deliver().udh(1, 1, 1, 2948).build();
        correlator.accept(raw(pdu, 0));
        clock.advanceMs(5_000);
        OutboundEvent pending = correlator.tick(clock.elapsedNs()).get(0);
        assertTrue(pending.json().contains("FT-PENDING-UNLOCK"));
        assertEquals(1, correlator.pendingUnlockCount());

        // HK-26 only emits the stage: no radio RAW follows.
        env.unlocked = true;
        clock.advanceMs(1_000);
        correlator.accept(Observation.stage(key(pdu), TraceStage.UNLOCK_REPLAY,
                clock.elapsedNs(), null));
        // The replay waits for the stage of the delivery that broadcastSms starts, which gives its fate.
        assertTrue(correlator.drainOutbox().isEmpty(), "no timeout fate before the delivery stage");
        assertEquals(0, correlator.pendingUnlockCount());
        correlator.accept(Observation.stage(key(pdu), TraceStage.WAP_DISPATCH, clock.elapsedNs(),
                "ct=application/vnd.wap.sic result=-1"));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size(), "the replay revises the pending event, it does not duplicate it");
        assertEquals(pending.eventId(), out.get(0).eventId());
        assertEquals(2, out.get(0).revision());
        assertTrue(out.get(0).json().contains("\"replay\":true"));
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-APP\""), out.get(0).json());
        assertEquals(0, correlator.pendingCount(), "the replay is sealed, not left waiting");
    }

    /** A replay that no delivery stage joins still seals, with FT-ACCEPTED after the timeout. */
    @Test
    void anUnjoinedReplaySealsAtTheTimeout() {
        env.unlocked = false;
        byte[] pdu = Pdu.deliver().udh(1, 1, 1, 2948).build();
        correlator.accept(raw(pdu, 0));
        clock.advanceMs(5_000);
        OutboundEvent pending = correlator.tick(clock.elapsedNs()).get(0);
        env.unlocked = true;
        correlator.accept(Observation.stage(key(pdu), TraceStage.UNLOCK_REPLAY, clock.elapsedNs(), null));
        List<OutboundEvent> out = tick();
        assertEquals(1, out.size());
        assertEquals(pending.eventId(), out.get(0).eventId());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-ACCEPTED\""), out.get(0).json());
    }

    /**
     * A pending CL-NORMAL message that the replay delivers to the SMS app is a new revision of a row the app
     * has. It replaces that row (FT-SHOWN) and is not turned into a second counter.
     */
    @Test
    void aShownNormalReplayRevisesThePendingRowInsteadOfCounting() {
        env.unlocked = false;
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        clock.advanceMs(5_000);
        OutboundEvent pending = correlator.tick(clock.elapsedNs()).get(0);
        assertTrue(pending.json().contains("\"fate\":\"FT-PENDING-UNLOCK\""), pending.json());

        env.unlocked = true;
        clock.advanceMs(1_000);
        correlator.accept(Observation.stage(key(pdu), TraceStage.UNLOCK_REPLAY, clock.elapsedNs(), null));
        correlator.accept(Observation.stage(key(pdu), TraceStage.HANDLER_DISPATCH_INTENT, clock.elapsedNs(),
                "action=android.provider.Telephony.SMS_DELIVER pkg=com.google.android.apps.messaging"));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size(), "the replay revision reaches the app");
        assertEquals(pending.eventId(), out.get(0).eventId());
        assertEquals(2, out.get(0).revision());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-SHOWN\""), out.get(0).json());
        assertEquals(0, correlator.counterOnlyCount());
        assertEquals(0, counters.total());
    }

    /**
     * A normal SMS that arrives before the first unlock is stored in the raw table (dispatch result 1), so it is
     * one FT-ACCEPTED event, which the app counts. Its replay after the unlock has no pending entry: it adds no counter
     * and no second event, so the message counts once.
     */
    @Test
    void aPreUnlockSmsAndItsReplayCountOnce() {
        env.unlocked = false;
        byte[] pdu = Pdu.deliver().build();
        correlator.accept(raw(pdu, 0));
        correlator.accept(dispatch(pdu, 0, 0, 1));
        List<OutboundEvent> first = tick();
        assertEquals(1, first.size());
        assertTrue(first.get(0).json().contains("\"fate\":\"FT-ACCEPTED\""), first.get(0).json());
        assertEquals(0, correlator.pendingUnlockCount(), "a dispatched SMS is not pending (spool stays free of it)");

        env.unlocked = true;
        clock.advanceMs(1_000);
        correlator.accept(Observation.stage(key(pdu), TraceStage.UNLOCK_REPLAY, clock.elapsedNs(), null));
        correlator.accept(Observation.stage(key(pdu), TraceStage.HANDLER_DISPATCH_INTENT, clock.elapsedNs(),
                "action=android.provider.Telephony.SMS_DELIVER pkg=com.google.android.apps.messaging"));
        assertTrue(correlator.drainOutbox().isEmpty(), "the replay is not a second event");
        assertEquals(0, counters.total(), "the replay adds no counter: the app counted the FT-ACCEPTED event");
        assertEquals(0, correlator.pendingCount(), "the replay is sealed, not left waiting");
    }

    @Test
    void identicalPendingPdusReplayInArrivalOrder() {
        env.unlocked = false;
        byte[] pdu = Pdu.deliver().udh(1, 1, 1, 2948).build();
        correlator.accept(raw(pdu, 0));
        clock.advanceMs(5_000);
        List<OutboundEvent> pending = correlator.tick(clock.elapsedNs());
        correlator.accept(raw(pdu, 0));
        clock.advanceMs(5_000);
        pending.addAll(correlator.tick(clock.elapsedNs()));
        assertEquals(2, pending.size(), "the second identical PDU is its own event");
        assertFalse(pending.get(0).eventId().equals(pending.get(1).eventId()));
        assertEquals(2, correlator.pendingUnlockCount());

        env.unlocked = true;
        correlator.accept(Observation.stage(key(pdu), TraceStage.UNLOCK_REPLAY,
                clock.elapsedNs(), null));
        correlator.accept(Observation.stage(key(pdu), TraceStage.WAP_DISPATCH, clock.elapsedNs(), "result=-1"));
        List<OutboundEvent> first = correlator.drainOutbox();
        assertEquals(1, first.size());
        assertEquals(pending.get(0).eventId(), first.get(0).eventId(), "FIFO: oldest first");
        assertEquals(1, correlator.pendingUnlockCount());
        correlator.accept(Observation.stage(key(pdu), TraceStage.UNLOCK_REPLAY,
                clock.elapsedNs(), null));
        correlator.accept(Observation.stage(key(pdu), TraceStage.WAP_DISPATCH, clock.elapsedNs(), "result=-1"));
        assertEquals(pending.get(1).eventId(), correlator.drainOutbox().get(0).eventId());
        assertEquals(0, correlator.pendingUnlockCount());
    }

    @Test
    void pendingEntriesAreCapped() {
        env.unlocked = false;
        byte[] pdu = Pdu.deliver().udh(1, 1, 1, 2948).build();
        correlator.accept(raw(pdu, 0));
        for (int i = 0; i < Correlator.MAX_PENDING + 3; i++) {
            clock.advanceMs(5_000);
            correlator.tick(clock.elapsedNs());
            correlator.accept(raw(pdu, 0));
        }
        clock.advanceMs(5_000);
        correlator.tick(clock.elapsedNs());
        assertTrue(correlator.pendingUnlockCount() <= Correlator.MAX_PENDING);
    }

    @Test
    void spoolFailureDisablesTheSpoolAndKeepsRam() {
        env.unlocked = false;
        spool.failWrites = true;
        byte[] pdu = Pdu.deliver().udh(1, 1, 1, 2948).build();
        correlator.accept(raw(pdu, 0));
        clock.advanceMs(5_000);
        List<OutboundEvent> out = correlator.tick(clock.elapsedNs());
        assertEquals(1, out.size(), "the event is still emitted");
        assertFalse(correlator.spoolAvailable());
    }

    @Test
    void restoreWhileUnlockedEmitsUnlockExpiredAndClearsTheSpool() {
        env.unlocked = true;
        byte[] pdu = Pdu.deliver().udh(1, 1, 1, 2948).build();
        spool.seed(new PendingUnlockStore.Entry(key(pdu), "evt-1", 1, clock.wallMs(),
                "CL-WAP-OTHER", "{}"));
        List<OutboundEvent> out = correlator.restore(clock.elapsedNs());
        assertEquals(1, out.size());
        assertEquals("evt-1", out.get(0).eventId());
        assertEquals(2, out.get(0).revision());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-UNLOCK-EXPIRED\""));
        assertEquals(0, spool.size(), "the restored entry leaves the spool");
    }

    @Test
    void restoreExpiresAnEntryOlderThan24HoursEvenWhileLocked() {
        // The spool returns old entries, and the correlator emits FT-UNLOCK-EXPIRED for them.
        env.unlocked = false;
        spool.seed(new PendingUnlockStore.Entry("k", "evt-old", 1,
                clock.wallMs() - Correlator.PENDING_EXPIRY_MS - 1, "CL-WAP-OTHER", "{}"));
        List<OutboundEvent> out = correlator.restore(clock.elapsedNs());
        assertEquals(1, out.size());
        assertEquals("evt-old", out.get(0).eventId());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-UNLOCK-EXPIRED\""));
        assertEquals(0, correlator.pendingUnlockCount());
        assertEquals(0, spool.size());
    }

    @Test
    void anExpiredRevisionKeepsTheCaptureDataOfThePendingOne() throws Exception {
        // The app replaces the row with the higher revision, so revision 2 must not lose revision 1.
        env.unlocked = false;
        byte[] pdu = Pdu.deliver().udh(1, 1, 1, 2948).build();
        correlator.accept(raw(pdu, 0));
        clock.advanceMs(5_000);
        JSONObject pending = new JSONObject(correlator.tick(clock.elapsedNs()).get(0).json());
        clock.advanceMs(Correlator.PENDING_EXPIRY_MS + 1);
        List<OutboundEvent> out = correlator.tick(clock.elapsedNs());
        assertEquals(1, out.size());
        JSONObject expired = new JSONObject(out.get(0).json());
        assertEquals("FT-UNLOCK-EXPIRED", expired.getString("fate"));
        assertEquals(2, expired.getInt("revision"));
        assertEquals(pending.getString("originator"), expired.getString("originator"));
        assertEquals(pending.getLong("receivedAtWall"), expired.getLong("receivedAtWall"));
        assertEquals(pending.getString("finalClass"), expired.getString("finalClass"));
        assertEquals(pending.optString("subtype", null), expired.optString("subtype", null));
        assertEquals(pending.getJSONArray("pdus").getString(0), expired.getJSONArray("pdus").getString(0));
        assertTrue(expired.getJSONArray("trace").length() > pending.getJSONArray("trace").length());
    }

    @Test
    void restoreWhileLockedKeepsThePendingEntry() {
        env.unlocked = false;
        spool.seed(new PendingUnlockStore.Entry("k", "evt-2", 1, clock.wallMs(), "CL-WAP-OTHER",
                "{}"));
        assertTrue(correlator.restore(clock.elapsedNs()).isEmpty());
        assertEquals(1, correlator.pendingUnlockCount());
    }

    @Test
    void entriesOlderThan24HoursExpireAsUnlockExpired() {
        env.unlocked = false;
        spool.seed(new PendingUnlockStore.Entry("k", "evt-3", 1, clock.wallMs(), "CL-WAP-OTHER",
                "{}"));
        correlator.restore(clock.elapsedNs());
        assertEquals(1, correlator.pendingUnlockCount());
        clock.advanceMs(Correlator.PENDING_EXPIRY_MS + 1);
        List<OutboundEvent> out = correlator.tick(clock.elapsedNs());
        assertEquals(1, out.size());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-UNLOCK-EXPIRED\""));
        assertEquals(0, correlator.pendingUnlockCount());
    }

    @Test
    void cellBroadcastIsSealedAsSystemWithItsFields() {
        byte[] pdu = {0x12, 0x34, 0x11, 0x02, 0x01, 0x11, 0x00, 0x00};
        correlator.accept(Observation.cb(pdu, 0x1234, 0x1102, 1, 1, true, 0, 1, 0, null,
                clock.elapsedNs(), clock.wallMs()));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"finalClass\":\"CL-CB\""), json);
        assertTrue(json.contains("\"transport\":\"CB\""), json);
        assertTrue(json.contains("\"messageId\":4354"), json);
        assertTrue(json.contains("\"emergency\":true"), json);
    }

    @Test
    void traceOffsetsAreRelativeToTheFirstStage() {
        byte[] pdu = Pdu.deliver().build();
        clock.advanceMs(10);
        correlator.accept(raw(pdu, 0));
        clock.advanceMs(20);
        correlator.accept(dispatch(pdu, 0, 0, 1));
        String json = tick().get(0).json();
        assertTrue(json.contains("\"tNs\":0"), json);
        assertTrue(json.contains("\"tNs\":20000000"), json);
    }

    @Test
    void aRawWithoutPduKeepsTheFactsOfTheDispatch() throws Exception {
        // A RAW without the PDU must not wipe the facts (here: the SIM OTA header) of the dispatch.
        byte[] pdu = hex(SimOtaClassifierTest.SAT_PUSH_NOSEC);
        byte[] tpdu = dev.nightjar.contract.Hashes.stripSmsc(pdu);
        correlator.accept(Observation.dispatch(key(pdu), null, tpdu, 0, 0, 1, -1, clock.elapsedNs()));
        correlator.accept(Observation.raw(key(pdu), null, null, "3gpp", Transport.IMS, 0, -1, -1, -1, false,
                clock.elapsedNs(), clock.wallMs()));
        List<OutboundEvent> out = correlator.drainOutbox();
        if (out.isEmpty()) out = tick();
        JSONObject o = new JSONObject(out.get(0).json());
        assertTrue(o.has(dev.nightjar.contract.Ipc.EventKey.SIM_OTA_HEADER), o.toString());
    }

    @Test
    void inboundEventsGetTheSubscriptionOfTheirPhone() throws Exception {
        // subId comes from the subscription map, not from the phone id.
        env.setSubForPhone(0, 7);
        env.setSlot(7, 1);
        env.setCarrier(7, "Test Carrier");
        byte[] pdu = Pdu.deliver().dcs(0x10).build();
        correlator.accept(raw(pdu, 0));
        JSONObject o = new JSONObject(tick().get(0).json());
        assertEquals(7, o.getInt("subId"));
        assertEquals(1, o.getInt("slot"));
        assertEquals("Test Carrier", o.getString("carrierName"));
    }

    @Test
    void withoutASubscriptionTheSubIdStaysUnknown() throws Exception {
        // A phone id is not a subscription id; release resolves -1 to the default SMS subscription.
        byte[] pdu = Pdu.deliver().dcs(0x10).build();
        correlator.accept(raw(pdu, 1));
        JSONObject o = new JSONObject(tick().get(0).json());
        assertEquals(-1, o.getInt("subId"));
        assertEquals(1, o.getInt("slot"));
    }

    @Test
    void theOtherFramingNeverHidesAHardAnomaly() {
        // Routed from pduparse: the framing retry accepts only a clean read.
        assertTrue(Correlator.otherFramingWins(PduFacts.parseRadio(Pdu.deliver().build())));
        PduFacts bad = PduFacts.parseRadio(Pdu.deliver().dcs(0x04).udhBytes(new byte[] {0x7F, 0x00}).build());
        assertTrue(!bad.valid() || Anomalies.malformed(bad.facts().anomalyMask()), "fixture has a hard anomaly");
        assertFalse(Correlator.otherFramingWins(bad));
        assertFalse(Correlator.otherFramingWins(null));
    }

    @Test
    void theRoamingFactFollowsTheSubscription() throws Exception {
        // Roaming is read for the subscription of the event, not at creation for "any".
        env.setSubForPhone(1, 9);
        env.setRoaming(9, true);
        byte[] pdu = Pdu.deliver().dcs(0x10).build();
        correlator.accept(raw(pdu, 1));
        JSONObject o = new JSONObject(tick().get(0).json());
        assertTrue(o.getJSONObject("env").getBoolean("roaming"));
    }

    @Test
    void theEnvironmentCarriesTheEmergencyCallState() throws Exception {
        correlator.setEmergencyCallActive(() -> true);
        byte[] pdu = Pdu.deliver().dcs(0x10).build();
        correlator.accept(raw(pdu, 0));
        JSONObject o = new JSONObject(tick().get(0).json());
        assertTrue(o.getJSONObject("env").getBoolean("emergencyCallActive"));
    }

    @Test
    void aPartSealedAloneUsesTheGroupIdentity() {
        // With a gap, every revision of a group is one event id; no orphan row per part.
        byte[] part1 = Pdu.deliver().udh(5, 3, 1, -1).build();
        byte[] part2 = Pdu.deliver().udh(5, 3, 2, -1).build();
        byte[] part3 = Pdu.deliver().udh(5, 3, 3, -1).build();
        correlator.accept(raw(part1, 0));
        List<OutboundEvent> first = tick();
        correlator.accept(raw(part3, 0));
        List<OutboundEvent> second = tick();
        correlator.accept(raw(part2, 0));
        List<OutboundEvent> third = correlator.drainOutbox();
        if (third.isEmpty()) third = tick();
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(1, third.size());
        String id = first.get(0).eventId();
        assertEquals(id, second.get(0).eventId());
        assertEquals(id, third.get(0).eventId());
        assertEquals(1, first.get(0).revision());
        assertEquals(2, second.get(0).revision());
        assertEquals(3, third.get(0).revision());
        assertTrue(second.get(0).json().contains("\"have\":2"), second.get(0).json());
        assertTrue(third.get(0).json().contains("\"have\":3"), third.get(0).json());
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    @Test
    void countersAggregatePerDayAndSubId() {
        counters.increment(clock.wallMs(), 1, Direction.IN, ClassId.NORMAL);
        counters.increment(clock.wallMs(), 1, Direction.IN, ClassId.NORMAL);
        counters.increment(clock.wallMs(), 2, Direction.IN, ClassId.NORMAL);
        assertEquals(3, counters.total());
        assertEquals(2, counters.size());
        assertTrue(counters.countersJson().contains("\"n\":2"));
    }

    @Test
    void countersDrainSettlesOnceAndAnUnsettledDrainIsNotLost() {
        counters.increment(clock.wallMs(), 1, Direction.IN, ClassId.NORMAL);
        assertTrue(counters.drainJson().contains("\"n\":1"));
        assertEquals(0, counters.total());
        counters.unsent();
        assertEquals(1, counters.total());

        // A drain that got neither delivered() nor unsent() is merged into the next drain.
        counters.drainJson();
        counters.increment(clock.wallMs(), 1, Direction.IN, ClassId.NORMAL);
        assertTrue(counters.drainJson().contains("\"n\":2"));
        counters.delivered();
        counters.unsent();
        assertEquals(0, counters.total());
        assertEquals("[]", counters.drainJson());
    }

    @Test
    void unknownKeyStagesDoNotCreateEvents() {
        correlator.accept(Observation.stage(null, null, 0, TraceStage.WAP_DISPATCH,
                clock.elapsedNs(), "ct=x"));
        assertTrue(correlator.drainOutbox().isEmpty());
        assertEquals(0, correlator.pendingCount());
    }

    @Test
    void malformedObservationNeverThrows() {
        assertDoesNotThrow(() -> {
            correlator.accept(Observation.raw(null, null, null, null, Transport.CS, -1, -1, -1, -1,
                    false, 0, 0));
            correlator.accept(null);
            correlator.tick(clock.elapsedNs());
        });
    }
}
