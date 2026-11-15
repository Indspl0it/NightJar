package dev.nightjar.contract;

/**
 * Port table: the class and the subtype code of an SMS by the destination port of its UDH
 * application port addressing element (IE 0x04 or 0x05, 3GPP TS 23.040). Display names
 * live in the app; the subtype code is the key.
 *
 * <p>A port that is not in the table is {@link ClassId#DATA_PORT} with {@link Subtype.DataPort#UNKNOWN}. ports
 * 9204 and 9205 are {@link ClassId#SMART}, because Nokia Smart Messaging and WAP use the same ports. Port
 * {@link #WAP_PUSH} carries WAP push: the classifier applies the WAP push rules to it first, and the table entry names
 * a port 2948 message that has no readable push yet.
 *
 * <p>A new entry needs a public source in its comment and a code in {@link Subtype}.
 */
public final class PortNames {
    private PortNames() {}

    /** IANA service name {@code wap-push} (WAP-259 WDP). */
    public static final int WAP_PUSH = 2948;

    /** The class of a message to {@code port}, or {@code null} for a negative port (no port element). */
    public static ClassId classOf(int port) {
        if (port < 0) return null;
        switch (port) {
            case 5505:
            case 5506:
            case 5507:
            case 5514:
            case 9204:
            case 9205:
                return ClassId.SMART;
            case 7275:
                return ClassId.LOC_SUPL;
            default:
                return ClassId.DATA_PORT;
        }
    }

    /** The subtype code of a message to {@code port}, or {@code null} for a negative port (no port element). */
    public static String subtype(int port) {
        if (port < 0) return null;
        switch (port) {
            // 3GPP TS 23.040 port 0 has no assigned use (AN-PORT-ZERO).
            case 0:
                return Subtype.DataPort.PORT0;
            // IANA service name and port number registry: wap-push.
            case WAP_PUSH:
                return Subtype.DataPort.WAP_PUSH;
            // IANA: wap-pushsecure.
            case 2949:
                return Subtype.DataPort.WAP_PUSH_SECURE;
            // IANA: wap-wsp (WAP connectionless session service).
            case 9200:
                return Subtype.DataPort.WAP_CONNECTIONLESS_SESSION;
            // IANA: wap-wsp-wtp (WAP session service).
            case 9201:
                return Subtype.DataPort.WAP_SESSION;
            // IANA: wap-wsp-s (WAP secure connectionless session service).
            case 9202:
                return Subtype.DataPort.WAP_CONNECTIONLESS_SESSION_SECURE;
            // IANA: wap-wsp-wtp-s (WAP secure session service).
            case 9203:
                return Subtype.DataPort.WAP_SESSION_SECURE;
            // IANA: wap-vcard-s and wap-vcal-s (WAP vCard and vCalendar, secure).
            case 9206:
            case 9207:
                return Subtype.DataPort.WAP_VCARD_VCALENDAR;
            // The entries below are the restricted SMS ports of JSR 120 (Wireless Messaging API 1.1) Appendix A,
            // "Restricted ports": the ports a phone keeps for its own services.
            // JSR 120: 2805, WAP WTA secure connection-less session service (IANA: wta-wsp-s).
            case 2805:
                return Subtype.DataPort.WAP_WTA_CONNECTIONLESS_SESSION_SECURE;
            // JSR 120: 2923, WAP WTA secure session service (IANA: wta-wsp-wtp-s).
            case 2923:
                return Subtype.DataPort.WAP_WTA_SESSION_SECURE;
            // JSR 120: 5502, Service Card reader (Nokia Smart Messaging).
            case 5502:
                return Subtype.DataPort.SERVICE_CARD;
            // JSR 120: 5503, Internet access configuration reader (Nokia Smart Messaging).
            case 5503:
                return Subtype.DataPort.INTERNET_ACCESS_CONFIG;
            // JSR 120: 5508, Dynamic Menu Control Protocol (Nokia Smart Messaging).
            case 5508:
                return Subtype.DataPort.DYNAMIC_MENU_CONTROL;
            // JSR 120: 5511, Message Access Protocol (Nokia Smart Messaging).
            case 5511:
                return Subtype.DataPort.MESSAGE_ACCESS;
            // JSR 120: 5512, Simple Email Notification (Nokia Smart Messaging).
            case 5512:
                return Subtype.DataPort.EMAIL_NOTIFICATION;
            // JSR 120: 49996, SyncML OTA configuration.
            case 49996:
                return Subtype.DataPort.SYNCML_OTA_CONFIG;
            // JSR 120: 49999, WAP OTA configuration (Nokia/Ericsson OTA settings).
            case 49999:
                return Subtype.DataPort.WAP_OTA_CONFIG;
            // Nokia Smart Messaging Specification 3.0.0, port 0x1581: ringing tone.
            case 5505:
                return Subtype.Smart.RINGTONE;
            // Nokia Smart Messaging 3.0.0, port 0x1582: operator logo.
            case 5506:
                return Subtype.Smart.OPLOGO;
            // Nokia Smart Messaging 3.0.0, port 0x1583: CLI (caller) logo.
            case 5507:
                return Subtype.Smart.CLILOGO;
            // Nokia Smart Messaging 3.0.0, port 0x158A: multipart message (picture message).
            case 5514:
                return Subtype.Smart.PICTURE;
            // Nokia Smart Messaging 3.0.0, port 0x23F4, and IANA wap-vcard.
            case 9204:
                return Subtype.Smart.VCARD;
            // Nokia Smart Messaging 3.0.0, port 0x23F5, and IANA wap-vcal.
            case 9205:
                return Subtype.Smart.VCAL;
            // IANA: oma-ulp (OMA UserPlane Location, SUPL INIT over the SMS bearer, OMA-TS-ULP).
            case 7275:
                return Subtype.LocSupl.SMS;
            default:
                return Subtype.DataPort.UNKNOWN;
        }
    }

    /** True when {@code port} has an entry in the table (port 0 included). */
    public static boolean known(int port) {
        String s = subtype(port);
        return s != null && !Subtype.DataPort.UNKNOWN.equals(s);
    }
}
