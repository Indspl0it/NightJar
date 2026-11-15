package dev.nightjar.payload.capture;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.PortNames;
import dev.nightjar.contract.Subtype;
import dev.nightjar.pduparse.Anomalies;
import dev.nightjar.pduparse.Encoding;
import dev.nightjar.pduparse.SimOtaSubtypes;
import dev.nightjar.pduparse.TpduFacts;
import dev.nightjar.pduparse.UdhFacts;
import dev.nightjar.pduparse.WspFacts;
import dev.nightjar.pduparse.WspParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provisional class from the PDU alone. The rules run in this
 * order; the first rule that matches gives the class and the subtype, and every later match goes to the extra subtypes
 * ({@link #extraCode}):
 *
 * <ol>
 *  <li>parse failure and forcing anomalies: CL-MALFORMED-SMS (known damage), CL-UNREADABLE-SMS (a parse
 *       failure with no known damage: the wrong message type, or a parser error), CL-WAP-UNREADABLE, CL-SIM-DD for
 *       AN-OTA-HDR. Known damage wins over CL-UNREADABLE-SMS ({@link #parseFailureClass}). A parse failure never gives
 *       CL-NORMAL;
 *   <li>source: CL-INJECTED (outbound classes are the outbound ledger's);
 *   <li>TPDU type and PID: CL-STATUS, Type 0, replace, return call, ME download, de-personalization, SIM data download,
 *       telematic;
 *   <li>DCS: class 0, class 2 (or read from the SIM), class 3, message waiting, compressed. A reserved DCS is only an
 *       anomaly;
 *   <li>UDH elements: special SMS indication (IE 0x01), enhanced voicemail (IE 0x23);
 *   <li>port ({@link PortNames}); port 2948 goes to the WAP push rules;
 *  <li>WAP push ({@link WapContentType});
 *   <li>content: empty message, binary message without a port;
 *   <li>consumer classes: decided later by {@link FinalClassifier}. A consumer class wins over a port match (rule 6)
 *  and a content match (rule 8), never over rules 1 to 5 or 7: {@link Result#consumerMayWin()};
 *   <li>CL-NORMAL.
 * </ol>
 *
 * <p>Thread: any (stateless).
 */
public final class ProvisionalClassifier {
    static final int PID_TYPE_ZERO = 0x40;
    static final int PID_REPLACE_FIRST = 0x41;
    static final int PID_RETURN_CALL = 0x5F;
    static final int PID_ME_DOWNLOAD = 0x7D;
    static final int PID_ME_DEPERSONALIZATION = 0x7E;
    static final int PID_SIM_DATA_DOWNLOAD = 0x7F;

    private static final String[] REPLACE_TYPES = {
        Subtype.Replace.TYPE1, Subtype.Replace.TYPE2, Subtype.Replace.TYPE3, Subtype.Replace.TYPE4,
        Subtype.Replace.TYPE5, Subtype.Replace.TYPE6, Subtype.Replace.TYPE7,
    };

    /** The rule numbers of the classification order; {@link #RULE_NONE} when the rule is not known. */
    public static final int RULE_NONE = 0;
    public static final int RULE_ANOMALY = 1;
    public static final int RULE_SOURCE = 2;
    public static final int RULE_PID = 3;
    public static final int RULE_DCS = 4;
    public static final int RULE_UDH = 5;
    public static final int RULE_PORT = 6;
    public static final int RULE_WAP = 7;
    public static final int RULE_CONTENT = 8;
    public static final int RULE_NORMAL = 10;

    private ProvisionalClassifier() {}

    /**
     * Class, subtype ({@code null} when the class has none), extra subtype codes in rule order, and the number of the
     * rule that gave the class.
     */
    public record Result(ClassId classId, String subtype, List<String> extras, int rule) {
        static final Result NORMAL = new Result(ClassId.NORMAL, null, List.of(), RULE_NORMAL);

        /** A result whose rule is not known: a consumer class never wins over it. */
        public Result(ClassId classId, String subtype, List<String> extras) {
            this(classId, subtype, extras, RULE_NONE);
        }

        /**
         * True when a consumer class (rule 9) wins over this result: the PDU rules gave CL-NORMAL, or only a port
         * (rule 6) or content (rule 8) rule matched first. A carrier voicemail SMS on a data port is then
         * CL-VVM, not an unknown port.
         */
        public boolean consumerMayWin() {
            return classId == ClassId.NORMAL || rule == RULE_PORT || rule == RULE_CONTENT;
        }
    }

    /**
     * @param facts parsed TPDU, or null when the bytes could not be read
     * @param fromSim true when HK-03b marked this TPDU as read from the SIM
     * @param injected true for a source-2 message without a release/canary token
     * @param contentType WAP content type when the PDU is a WAP push, else null
     */
    public static ClassId classify(PduFacts facts, boolean fromSim, boolean injected, String contentType) {
        return result(facts, fromSim, injected, contentType).classId();
    }

    /** As {@link #classify}, with the subtype and the extra subtypes. Never throws: an internal error gives NORMAL. */
    public static Result result(PduFacts facts, boolean fromSim, boolean injected, String contentType) {
        try {
            return resultUnguarded(facts == null ? null : facts.facts(), fromSim, injected, contentType);
        } catch (RuntimeException e) {
            // Fail open: classification is a label, never a reason to stop the pipeline.
            return injected ? new Result(ClassId.INJECTED, null, List.of(), RULE_SOURCE) : Result.NORMAL;
        }
    }

    static Result resultUnguarded(TpduFacts f, boolean fromSim, boolean injected, String contentType) {
        Matches m = new Matches();

        // 1. Parse failure and forcing anomalies.
        m.rule = RULE_ANOMALY;
        int mask = f == null ? Anomalies.NONE : f.anomalyMask();
        if (f != null) {
            AnomalyCode forcing = Anomalies.forcing(mask);
            ClassId failure = parseFailureClass(f.valid(), mask);
            if (failure == ClassId.MALFORMED_SMS) {
                boolean hard = forcing != null && forcing.forcedClass() == ClassId.MALFORMED_SMS;
                m.add(ClassId.MALFORMED_SMS, hard ? forcing.forcedSubtype() : malformedSubtype(mask));
            } else if (failure == ClassId.UNREADABLE_SMS) {
                // AN-MTI names the wrong message type. A parser error has no code and no subtype.
                m.add(ClassId.UNREADABLE_SMS,
                        Anomalies.has(mask, AnomalyCode.MTI) ? AnomalyCode.MTI.forcedSubtype() : null);
            } else if (forcing != null) {
                m.add(forcing.forcedClass(), forcing.forcedSubtype());
            }
        }

        // 2. Source.
        m.rule = RULE_SOURCE;
        if (injected) m.add(ClassId.INJECTED, null);

        if (f == null || !f.valid()) {
            // Nothing else of the TPDU can be trusted. A push the WAP hook saw still names its content type.
            if (f == null && contentType != null) {
                m.rule = RULE_WAP;
                wap(m, null, contentType);
            }
            return m.result();
        }

        if (f.statusReport()) {
            // 3a. TPDU type: a status report has no PID or DCS rules of its own.
            m.rule = RULE_PID;
            m.add(ClassId.STATUS, statusSubtype(f.status()));
            return m.result();
        }
        if (f.mti() == 0) deliverRules(m, f, fromSim, contentType);
        return m.result();
    }

    private static void deliverRules(Matches m, TpduFacts f, boolean fromSim, String contentType) {
        int pid = f.protocolId();
        // The elements of a cut header say nothing more (UdhParser): no element rule and no port rule reads them.
        UdhFacts u = f.udh() != null && f.udh().complete() ? f.udh() : null;
        int port = u == null ? -1 : u.destPort();

        // 3. PID (3GPP TS 23.040).
        m.rule = RULE_PID;
        if (pid == PID_TYPE_ZERO) {
            m.add(ClassId.SILENT, port >= 0 ? Subtype.Silent.TYPE0_PORT : Subtype.Silent.TYPE0);
        } else if (f.replace()) {
            m.add(ClassId.REPLACE, REPLACE_TYPES[pid - PID_REPLACE_FIRST]);
        } else if (pid == PID_RETURN_CALL) {
            m.add(ClassId.RETURN_CALL, null);
        } else if (pid == PID_ME_DOWNLOAD) {
            m.add(ClassId.ME_DOWNLOAD, null);
        } else if (pid == PID_ME_DEPERSONALIZATION) {
            m.add(ClassId.ME_DEPERSO, null);
        } else if (pid == PID_SIM_DATA_DOWNLOAD) {
            simOta(m, f);
        } else if ((pid & 0xE0) == 0x20) {
            m.add(ClassId.TELEMATIC, telematicSubtype(pid));
        }

        // 4. DCS (3GPP TS 23.038).
        m.rule = RULE_DCS;
        int dcsClass = f.dcsClass();
        if (dcsClass == 0) {
            m.add(ClassId.FLASH, f.udl() == 0 ? Subtype.Flash.CLASS0_EMPTY : Subtype.Flash.CLASS0);
        }
        if ((dcsClass == 2 || fromSim) && pid != PID_SIM_DATA_DOWNLOAD) {
            // SimOtaSubtypes.isSimOta is the one SIM OTA rule: the framework's USIM data download
            // (class 2 with PID 0x7C too) and a class 2 message with a command packet identifier (TS 31.115). A
            // message from the SIM with a command packet identifier stays SIM OTA as before.
            if (SimOtaSubtypes.isSimOta(f) || f.ota() != null) {
                simOta(m, f);
            } else {
                m.add(ClassId.SIM_STORED, Subtype.SimStored.CLASS2);
            }
        }
        if (dcsClass == 3) m.add(ClassId.CLASS3, null);
        if (f.messageWaitingGroup()) m.add(ClassId.MWI, dcsMwiSubtype(f));
        if (f.compressed()) m.add(ClassId.COMPRESSED, null);

        // 5. UDH information elements (TS 23.040).
        m.rule = RULE_UDH;
        if (u != null) {
            if (u.specialSms()) m.add(ClassId.MWI, mwiType(u.specialSmsType() & 0x03));
            if (u.enhancedVoicemail()) m.add(ClassId.MWI, Subtype.Mwi.EXTENDED);
        }

        // 6. Port. Port 2948 is a WAP push: rule 7 names it.
        m.rule = RULE_PORT;
        boolean wapPort = port == PortNames.WAP_PUSH;
        if (port >= 0 && !wapPort) m.add(PortNames.classOf(port), PortNames.subtype(port));

        // 7. WAP push.
        m.rule = RULE_WAP;
        if (wapPort || contentType != null) wap(m, f, contentType);

        // 8. Content: only for a normal PID and DCS.
        m.rule = RULE_CONTENT;
        boolean normalCoding = pid == 0 && dcsClass < 0 && !f.messageWaitingGroup();
        if (normalCoding && f.udl() == 0 && !f.udhi()) m.add(ClassId.EMPTY, null);
        if (f.encoding() == Encoding.EIGHT_BIT && port < 0 && !binaryByNature(m.cls)) m.add(ClassId.BINARY, null);
    }

    /** Classes whose user data is always binary; a "binary message" extra would add nothing to them. */
    private static boolean binaryByNature(ClassId c) {
        return c == ClassId.SIM_DD || c == ClassId.ME_DOWNLOAD || c == ClassId.ME_DEPERSO;
    }

    // ------------------------------------------------------------------ WAP push

    private static void wap(Matches m, TpduFacts f, String contentType) {
        WspFacts w = f == null ? null : f.wsp();
        if (w != null && (w.anomalyMask() & WspParser.UNREADABLE) != 0) {
            AnomalyCode c = Anomalies.forcing(w.anomalyMask() & WspParser.UNREADABLE);
            m.add(ClassId.WAP_UNREADABLE, c == null ? null : c.forcedSubtype());
            return;
        }
        String ct;
        if (w != null) {
            if (!w.valid()) {
                // valid() is false only when the content type could not be read (pduparse). A soft code such as
                // AN-WSP-APPID leaves the push valid, so it never reaches this branch.
                m.add(ClassId.WAP_UNREADABLE, AnomalyCode.WSP_CTYPE.forcedSubtype());
                return;
            }
            ct = w.contentType();
            if (ct == null) {
                // A well-known code that the registry does not assign: readable, but unnamed.
                m.add(ClassId.WAP_OTHER, "0x" + Integer.toHexString(w.contentTypeCode()));
                return;
            }
        } else {
            ct = contentType;
        }
        if (ct == null) {
            // A segment of a multipart push before the WAP hook reassembled it: name the port only.
            m.add(ClassId.DATA_PORT, PortNames.subtype(PortNames.WAP_PUSH));
            return;
        }
        if (WapContentType.CT_UNKNOWN.equals(ct)) {
            m.add(ClassId.WAP_UNREADABLE, AnomalyCode.WSP_CTYPE.forcedSubtype());
            return;
        }
        ClassId c = WapContentType.classify(ct, w);
        byte[] buf = f == null ? null : f.userData();
        int base = f == null ? -1 : f.bodyOffset();
        m.add(c, WapContentType.subtype(c, ct, w, buf, base));
    }

    // ------------------------------------------------------------------ SIM OTA

    /**
     * CL-SIM-DD with the subtype order of SAT, WIB, NOSEC, RFM and RAM, OTHER ({@link SimOtaSubtypes}).
     * The first code is the subtype and the others become extras. AN-OTA-HDR gives SIM-OTA-OTHER first; rule 1
     * has already added it, so only the TAR and SPI codes that could still be read are new.
     */
    /**
     * The class of rule 1 for a parse failure, or {@code null} when rule 1 does not decide on these
     * grounds. Known damage (a code that forces CL-MALFORMED-SMS) gives CL-MALFORMED-SMS, also when the parse is valid
     * and also next to AN-MTI: it is the stricter class, and it cannot be released. A parse failure without known
     * damage (AN-MTI, or a parser error with no code) gives CL-UNREADABLE-SMS: AOSP still reads such a PDU as an SMS,
     * so a held message can be released.
     */
    static ClassId parseFailureClass(boolean valid, int mask) {
        if (Anomalies.malformed(mask)) return ClassId.MALFORMED_SMS;
        if (!valid) return ClassId.UNREADABLE_SMS;
        return null;
    }

    /**
     * The subtype of a CL-MALFORMED-SMS that no hard anomaly forced: the first code that forces CL-MALFORMED-SMS, else
     * the first code in contract order, so the title always names an anomaly. Null only when the mask is empty.
     */
    static String malformedSubtype(int mask) {
        String first = null;
        for (AnomalyCode c : Anomalies.list(mask)) {
            if (c.forcedClass() == ClassId.MALFORMED_SMS) return c.wire();
            if (first == null) first = c.wire();
        }
        return first;
    }

    private static void simOta(Matches m, TpduFacts f) {
        for (String code : SimOtaSubtypes.codes(f)) m.add(ClassId.SIM_DD, code);
    }

    // ------------------------------------------------------------------ subtype tables

    /** TS 23.040 0x00-0x1F completed, 0x20-0x3F still trying, 0x40-0x7F failed; bit 7 reserved. */
    static String statusSubtype(int st) {
        if (st < 0 || st > 0x7F) return null;
        if (st < 0x20) return Subtype.Status.DELIVERED;
        if (st < 0x40) return Subtype.Status.PENDING;
        return Subtype.Status.FAILED;
    }

    /** TS 23.040 §9.2.3.9, telematic interworking (bits 7-5 = 001): the device type in bits 4-0. */
    static String telematicSubtype(int pid) {
        return switch (pid & 0x1F) {
            case 0x01 -> Subtype.Telematic.TELEX;
            case 0x02, 0x03 -> Subtype.Telematic.FAX; // group 3 and group 4 telefax
            case 0x04 -> Subtype.Telematic.VOICE;
            case 0x05 -> Subtype.Telematic.ERMES;
            case 0x06 -> Subtype.Telematic.PAGING; // national paging system
            case 0x11 -> Subtype.Telematic.X400; // any public X.400 message handling system
            case 0x12 -> Subtype.Telematic.EMAIL; // Internet electronic mail
            default -> Subtype.Telematic.OTHER;
        };
    }

    /**
     * TS 23.038 §4 message waiting groups. DISCARD-SILENT is the discard group 0xC0 with the indication inactive and no
     * user data: a signal that shows nothing and stores nothing.
     */
    static String dcsMwiSubtype(TpduFacts f) {
        if (f.mwiDontStore() && !f.mwiActive() && f.udl() == 0) return Subtype.Mwi.DISCARD_SILENT;
        return mwiType(f.mwiType());
    }

    /** Indication type 0 voicemail, 1 fax, 2 email, 3 other (TS 23.038 and TS 23.040). */
    static String mwiType(int type) {
        return switch (type) {
            case 0 -> Subtype.Mwi.VOICEMAIL;
            case 1 -> Subtype.Mwi.FAX;
            case 2 -> Subtype.Mwi.EMAIL;
            default -> Subtype.Mwi.OTHER;
        };
    }

    // ------------------------------------------------------------------ extra subtypes

    /**
     * The extra subtype code of a rule that matched after the first one. A rule of the event's own class gives its bare
     * subtype code; a rule of another class gives {@code <class wire>:<subtype>}, or the class wire name alone when that
     * rule has no subtype. Returns {@code null} when there is nothing to add.
     */
    public static String extraCode(ClassId eventClass, ClassId ruleClass, String ruleSubtype) {
        if (ruleClass == null) return null;
        if (ruleClass == eventClass) return ruleSubtype;
        return ruleSubtype == null ? ruleClass.wire() : ruleClass.wire() + ":" + ruleSubtype;
    }

    /**
     * The extra subtype codes that {@code r} gives an event of another class {@code eventClass} (a consumer
     * class that won over a port or content match): the class and subtype of {@code r} first, then its extras in rule
     * order, each written again for {@code eventClass}. A bare code is a subtype of the class of {@code r}; a code of
     * {@code eventClass} itself becomes bare.
     */
    public static List<String> extrasFor(ClassId eventClass, Result r) {
        List<String> out = new ArrayList<>(1 + r.extras().size());
        addCode(out, extraCode(eventClass, r.classId(), r.subtype()));
        String own = eventClass.wire();
        for (String code : r.extras()) {
            if (!code.startsWith(CLASS_PREFIX)) {
                addCode(out, extraCode(eventClass, r.classId(), code));
            } else if (code.startsWith(own + ":")) {
                addCode(out, code.substring(own.length() + 1));
            } else if (!code.equals(own)) {
                addCode(out, code);
            }
        }
        return out;
    }

    /** Every class wire name starts with this; no subtype code does. */
    private static final String CLASS_PREFIX = "CL-";

    private static void addCode(List<String> out, String code) {
        if (code != null && !out.contains(code)) out.add(code);
    }

    /** First match wins; later matches become extra subtypes without duplicates. */
    private static final class Matches {
        /** The rule that runs now; the first match keeps it. */
        int rule = RULE_NONE;
        ClassId cls;
        String sub;
        int clsRule = RULE_NONE;
        List<String> extras;

        void add(ClassId c, String s) {
            if (c == null) return;
            if (cls == null) {
                cls = c;
                sub = s;
                clsRule = rule;
                return;
            }
            if (c == cls && (s == null || s.equals(sub))) return;
            String code = extraCode(cls, c, s);
            if (code == null) return;
            if (extras == null) extras = new ArrayList<>(2);
            if (!extras.contains(code)) extras.add(code);
        }

        Result result() {
            if (cls == null) return Result.NORMAL;
            return new Result(cls, sub, extras == null ? List.of() : Collections.unmodifiableList(extras), clsRule);
        }
    }
}
