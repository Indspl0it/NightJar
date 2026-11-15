package dev.nightjar.payload.capture;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.FateDetail;
import dev.nightjar.contract.Ipc;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Transport;
import dev.nightjar.contract.Verdict;
import dev.nightjar.contract.Wire;
import dev.nightjar.payload.JsonBuilder;
import dev.nightjar.pduparse.Anomalies;
import dev.nightjar.pduparse.OtaFacts;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * serialization.
 *
 * <p>Keys are written in sorted order at every level (the app's golden fixtures compare byte for
 * byte), null fields are omitted, PDUs are unwrapped Base64, and trace offsets are relative to the
 * first stage.
 *
 * <p>Thread: worker only (reads a mutable {@link Event}).
 */
public final class EventDto {
    private EventDto() {}

    /** Serializes {@code e}; returns one line, no trailing newline. */
    public static String toJson(Event e) {
        JsonBuilder o = new JsonBuilder()
                .put("eventId", e.eventId)
                .put("revision", e.revision)
                .put(Ipc.Key.SESSION_ID, e.sessionId)
                .put("direction", wire(e.direction))
                .put("transport", wire(e.transport))
                .put("source", e.source)
                .put("subId", e.subId)
                .put("slot", e.slot)
                .put("carrierName", e.carrierName);
        if (!e.pdus.isEmpty()) {
            List<String> encoded = new ArrayList<>(e.pdus.size());
            for (byte[] p : e.pdus) encoded.add(Base64.getEncoder().encodeToString(p));
            o.put("pdus", encoded);
        }
        o.put("format", e.format);
        if (!e.parts.isEmpty()) {
            java.util.List<Object> parts = new java.util.ArrayList<>();
            for (Event.Part p : e.parts) {
                parts.add(new JsonBuilder()
                        .put("seq", p.seq())
                        .put("tNs", p.tNs())
                        .map());
            }
            o.put("parts", parts);
        }
        if (e.concatRef >= 0) {
            o.put("concat", new JsonBuilder()
                    .put("ref", e.concatRef)
                    .put("total", e.concatTotal)
                    .put("have", e.concatHave)
                    .map());
        }
        o.put("receivedAtWall", e.receivedAtWall)
                .put("receivedAtElapsedNs", Math.max(0L, e.receivedAtElapsedNs));
        if (e.ackResult != null) o.put("ackResult", e.ackResult.intValue());
        o.put("provisionalClass", wire(e.provisionalClass))
                .put("predictedClass", wire(e.predictedClass))
                .put("finalClass", wire(e.finalClass))
                .put("verdict", verdictMap(e))
                .put("fate", wire(e.fate))
                .put(Ipc.EventKey.SUBTYPE, FinalClassifier.subtype(e));
        if (!e.extraSubtypes.isEmpty()) o.put(Ipc.EventKey.EXTRA_SUBTYPES, new ArrayList<Object>(e.extraSubtypes));
        if (e.anomalyMask != Anomalies.NONE) {
            List<Object> names = new ArrayList<>();
            for (AnomalyCode c : Anomalies.list(e.anomalyMask)) names.add(c.wire());
            o.put(Ipc.EventKey.ANOMALIES, names);
        }
        OtaFacts ota = e.facts == null ? null : e.facts.facts().ota();
        if (ota != null) o.put(Ipc.EventKey.SIM_OTA_HEADER, simOtaHeader(ota));
        java.util.Map<String, Object> detail = fateDetailMap(e);
        if (!detail.isEmpty()) o.put("fateDetail", detail);
        o.put("originator", e.originator)
                .put("smsc", e.smsc)
                .put("destination", e.destination);
        if (e.destPort >= 0) o.put("destPort", e.destPort);
        o.put("contentType", e.contentType)
                .put("callingPackage", e.callingPackage)
                .put("attributionTag", e.attributionTag)
                .put("method", e.method);
        if (e.persistFlag != null) o.put("persistFlag", e.persistFlag.booleanValue());
        if (e.messageRef >= 0) o.put("messageRef", e.messageRef);
        if (e.cb != null) {
            o.put("cb", new JsonBuilder()
                    .put("serial", e.cb.serial())
                    .put("messageId", e.cb.messageId())
                    .put("page", e.cb.page())
                    .put("pages", e.cb.pages())
                    .put("emergency", e.cb.emergency())
                    .map());
        }
        o.put("trace", traceJson(e))
                .put("flags", flagsJson(e.flags))
                .put("env", envJson(e.env))
                .put(Ipc.Key.PAYLOAD_VERSION, e.payloadVersion)
                .put(Ipc.Key.BUILD_FINGERPRINT, e.buildFingerprint);
        return o.json();
    }

    /**
     * {@code fateDetail} (C.3): the entries the correlator stored, plus the consumer facts of the event:
     * {@code package} (the app that took the message), {@code por} (SIM proof-of-receipt status word),
     * {@code oemReason} and {@code expired}. A stored entry with the same key wins.
     */
    static java.util.Map<String, Object> fateDetailMap(Event e) {
        JsonBuilder detail = new JsonBuilder();
        for (var entry : e.fateDetail.entrySet()) {
            detail.put(entry.getKey(), entry.getValue());
        }
        java.util.Map<String, Object> stored = e.fateDetail;
        if (e.consumerPackage != null && !stored.containsKey(FateDetail.PACKAGE)) {
            detail.put(FateDetail.PACKAGE, e.consumerPackage);
        }
        if (e.por != null && !stored.containsKey("por")) detail.put("por", e.por);
        if (e.oemReason != null && !stored.containsKey(FateDetail.OEM_REASON)) {
            detail.put(FateDetail.OEM_REASON, e.oemReason);
        }
        if (e.expired && !stored.containsKey("expired")) detail.put("expired", true);
        return detail.map();
    }

    /** {@code verdict} object (C.3) as a map for the deterministic writer: action, ruleId, clampedFrom, clampReason. */
    public static java.util.Map<String, Object> verdictMap(Event e) {
        JsonBuilder v = new JsonBuilder()
                .put("action", e.verdict == null ? "ALLOW" : e.verdict.wire());
        if (e.ruleId >= 0) v.put("ruleId", e.ruleId);
        if (e.clampedFrom != null) v.put("clampedFrom", e.clampedFrom.wire());
        v.put("clampReason", e.clampReason);
        return v.map();
    }

    /**
     * {@code simOtaHeader}: the header fields of the SIM OTA packet that {@code OtaParser} read. A field that
     * could not be read is omitted. The secured data is not in the facts, so it cannot reach the event.
     */
    static java.util.Map<String, Object> simOtaHeader(OtaFacts ota) {
        JsonBuilder h = new JsonBuilder();
        if (ota.packetLength() >= 0) h.put(Ipc.SimOtaKey.CPL, ota.packetLength());
        if (ota.headerLength() >= 0) h.put(Ipc.SimOtaKey.CHL, ota.headerLength());
        if (ota.spi() >= 0) h.put(Ipc.SimOtaKey.SPI, ota.spi());
        if (ota.kic() >= 0) h.put(Ipc.SimOtaKey.KIC, ota.kic());
        if (ota.kid() >= 0) h.put(Ipc.SimOtaKey.KID, ota.kid());
        if (ota.tar() >= 0) h.put(Ipc.SimOtaKey.TAR, ota.tar());
        if (ota.counter() >= 0) h.put(Ipc.SimOtaKey.CNTR, ota.counter());
        return h.map();
    }

    private static java.util.List<Object> traceJson(Event e) {
        java.util.List<Object> arr = new java.util.ArrayList<>();
        long base = Long.MAX_VALUE;
        for (Event.TraceStep s : e.trace) {
            if (s.tNs() >= 0 && s.tNs() < base) base = s.tNs();
        }
        if (base == Long.MAX_VALUE) base = 0;
        for (Event.TraceStep s : e.trace) {
            arr.add(new JsonBuilder()
                    .put("stage", s.stage() == null ? "" : s.stage().wire())
                    .put("tNs", Math.max(0L, s.tNs() - base))
                    .put("detail", s.detail())
                    .map());
        }
        return arr;
    }

    private static java.util.Map<String, Object> flagsJson(Event.Flags f) {
        return new JsonBuilder()
                .put("selfTest", f.selfTest)
                .put("runId", f.runId)
                .put("canaryClass", f.canaryClass)
                .put("replay", f.replay)
                .put("releaseOf", f.releaseOf)
                .put("debug", f.debug)
                .map();
    }

    private static java.util.Map<String, Object> envJson(Event.EnvFacts env) {
        return new JsonBuilder()
                .put("roaming", env.roaming)
                .put("emergencyCallActive", env.emergencyCallActive)
                .put(Ipc.Key.USER_UNLOCKED, env.userUnlocked)
                .put(Ipc.Key.BOOT_COUNT, env.bootCount)
                .map();
    }

    /**
     * Copies the capture data of an earlier revision ({@code json}, the C.3 line this class wrote) into {@code e}, so a
     * later revision does not erase it: the app replaces the whole row with the higher revision.
     *
     * <p>Always copied: addressing, concatenation, times, subscription, content type, anomaly codes and the trace. The
     * trace steps are shifted so that the last one lands at {@code nowNs}. With {@code withPdus} also the PDUs and
     * parts (and the facts parsed from them). With {@code withOutcome} also
     * the classes, the subtype and extras, the verdict, the fate detail, the flags and the calling package: for a
     * revision that only changes the fate. Without it the pipeline classifies again (a post-unlock replay).
     *
     * <p>Never throws: a field that cannot be read stays as it is.
     */
    static void restoreFrom(Event e, String json, long nowNs, boolean withPdus, boolean withOutcome) {
        if (json == null || json.isEmpty()) return;
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception ex) {
            return;
        }
        try {
            restoreCapture(e, o, nowNs, withPdus);
            if (withOutcome) restoreOutcome(e, o);
        } catch (Exception ex) {
            // Partial restore: what was copied stays, the rest keeps the defaults.
        }
    }

    private static void restoreCapture(Event e, JSONObject o, long nowNs, boolean withPdus) throws Exception {
        if (o.has("subId")) e.subId = o.optInt("subId", e.subId);
        if (o.has("slot")) e.slot = o.optInt("slot", e.slot);
        if (e.carrierName == null) e.carrierName = text(o, "carrierName");
        e.transport = Wire.parse(Transport.class, text(o, "transport"), e.transport);
        String format = text(o, "format");
        if (format != null) e.format = format;
        long wall = o.optLong("receivedAtWall", 0L);
        if (wall > 0) e.receivedAtWall = wall;
        long elapsed = o.optLong("receivedAtElapsedNs", 0L);
        if (elapsed > 0) e.receivedAtElapsedNs = elapsed;
        JSONArray pdus = o.optJSONArray("pdus");
        JSONArray parts = o.optJSONArray("parts");
        if (withPdus && pdus != null && e.pdus.isEmpty()) {
            for (int i = 0; i < pdus.length(); i++) {
                byte[] pdu = Base64.getDecoder().decode(pdus.getString(i));
                JSONObject part = parts == null ? null : parts.optJSONObject(i);
                e.addPdu(pdu, part == null ? nowNs : part.optLong("tNs", nowNs));
            }
            if (e.facts == null && !e.pdus.isEmpty()) e.facts = Correlator.parseFacts(e.pdus.get(0), e.transport);
        }
        JSONObject concat = o.optJSONObject("concat");
        if (concat != null && e.concatRef < 0) {
            e.concatRef = concat.optInt("ref", -1);
            e.concatTotal = concat.optInt("total", 0);
            e.concatHave = concat.optInt("have", 0);
        }
        if (e.originator == null) e.originator = text(o, "originator");
        if (e.smsc == null) e.smsc = text(o, "smsc");
        if (e.destination == null) e.destination = text(o, "destination");
        if (e.destPort < 0 && o.has("destPort")) e.destPort = o.optInt("destPort", -1);
        if (e.contentType == null) e.contentType = text(o, "contentType");
        JSONArray anomalies = o.optJSONArray(Ipc.EventKey.ANOMALIES);
        if (anomalies != null) {
            for (int i = 0; i < anomalies.length(); i++) {
                AnomalyCode c = Wire.parse(AnomalyCode.class, anomalies.optString(i, null), null);
                if (c != null) e.anomalyMask = Anomalies.with(e.anomalyMask, c);
            }
        }
        JSONArray trace = o.optJSONArray("trace");
        if (trace != null && trace.length() > 0) {
            long last = 0;
            for (int i = 0; i < trace.length(); i++) {
                JSONObject s = trace.optJSONObject(i);
                if (s != null) last = Math.max(last, s.optLong("tNs", 0L));
            }
            List<Event.TraceStep> earlier = new ArrayList<>(trace.length());
            for (int i = 0; i < trace.length(); i++) {
                JSONObject s = trace.optJSONObject(i);
                if (s == null) continue;
                TraceStage stage = Wire.parse(TraceStage.class, text(s, "stage"), null);
                if (stage == null) continue;
                earlier.add(new Event.TraceStep(stage, nowNs - last + s.optLong("tNs", 0L), text(s, "detail")));
            }
            List<Event.TraceStep> later = new ArrayList<>(e.trace);
            e.trace.clear();
            for (Event.TraceStep s : earlier) e.addTrace(s.stage(), s.tNs(), s.detail());
            for (Event.TraceStep s : later) e.addTrace(s.stage(), s.tNs(), s.detail());
        }
    }

    private static void restoreOutcome(Event e, JSONObject o) throws Exception {
        e.source = o.optInt("source", e.source);
        e.provisionalClass = Wire.parse(ClassId.class, text(o, "provisionalClass"), e.provisionalClass);
        e.predictedClass = Wire.parse(ClassId.class, text(o, "predictedClass"), e.predictedClass);
        e.finalClass = Wire.parse(ClassId.class, text(o, "finalClass"), e.finalClass);
        e.subtype = text(o, Ipc.EventKey.SUBTYPE);
        JSONArray extras = o.optJSONArray(Ipc.EventKey.EXTRA_SUBTYPES);
        if (extras != null) {
            e.extraSubtypes.clear();
            for (int i = 0; i < extras.length(); i++) {
                String code = extras.optString(i, null);
                if (code != null) e.extraSubtypes.add(code);
            }
        }
        JSONObject verdict = o.optJSONObject("verdict");
        if (verdict != null) {
            e.verdict = Wire.parse(Verdict.class, text(verdict, "action"), e.verdict);
            e.ruleId = verdict.optLong("ruleId", e.ruleId);
            e.clampedFrom = Wire.parse(Verdict.class, text(verdict, "clampedFrom"), e.clampedFrom);
            e.clampReason = text(verdict, "clampReason");
        }
        JSONObject detail = o.optJSONObject("fateDetail");
        if (detail != null) {
            java.util.Iterator<String> keys = detail.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = detail.opt(k);
                if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                    e.fateDetail.putIfAbsent(k, v);
                }
            }
        }
        JSONObject flags = o.optJSONObject("flags");
        if (flags != null) {
            e.flags.selfTest = flags.optBoolean("selfTest", e.flags.selfTest);
            if (e.flags.runId == null) e.flags.runId = text(flags, "runId");
            if (e.flags.canaryClass == null) e.flags.canaryClass = text(flags, "canaryClass");
            e.flags.replay = flags.optBoolean("replay", e.flags.replay);
            if (e.flags.releaseOf == null) e.flags.releaseOf = text(flags, "releaseOf");
            e.flags.debug = flags.optBoolean("debug", e.flags.debug);
        }
        if (e.callingPackage == null) e.callingPackage = text(o, "callingPackage");
    }

    /** A string value, or null when the key is absent or JSON null. */
    private static String text(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }

    private static String wire(dev.nightjar.contract.WireEnum e) {
        return e == null ? null : e.wire();
    }

    private static String wire(ClassId c) {
        return c == null ? null : c.wire();
    }
}
