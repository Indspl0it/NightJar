package dev.nightjar.contract;

import java.util.List;

/**
 * Subtype codes for the event field {@link Ipc.EventKey#SUBTYPE}.
 *
 * <p>A class is the unit for the user's action (Allow, Hold, Block). A subtype only adds detail for the title, the
 * explanation and the severity. Read a code together with its class: the same spelling can occur in two classes (for
 * example {@code NOTIFY}). A class that has no subtypes sends no subtype (the field is omitted).
 *
 * <p>Some classes carry a free-form subtype instead of a fixed code ({@link #isFreeForm}):
 *
 * <ul>
 *   <li>CL-MALFORMED-SMS, CL-UNREADABLE-SMS and CL-WAP-UNREADABLE: the {@link AnomalyCode} wire name
 *       ({@link AnomalyCode#forcedSubtype}). A CL-UNREADABLE-SMS message from a parser failure has no subtype;
 *   <li>CL-WAP-OTHER: the content type;
 *   <li>CL-INJECTED, CL-MO-VISIBLE, CL-MO-UNPERSISTED and CL-MO-RAW: the package name of the app;
 *   <li>CL-MO-DATA: the destination port as decimal text.
 * </ul>
 *
 * <p>When two detection rules match, the first gives the class and the subtype, and the others go to
 * {@link Ipc.EventKey#EXTRA_SUBTYPES} in the forms that field documents.
 */
public final class Subtype {
    private Subtype() {}

    /** CL-SILENT. */
    public static final class Silent {
        private Silent() {}

        public static final String TYPE0 = "TYPE0";
        public static final String TYPE0_PORT = "TYPE0-PORT";
    }

    /** CL-FLASH. */
    public static final class Flash {
        private Flash() {}

        public static final String CLASS0 = "CLASS0";
        public static final String CLASS0_EMPTY = "CLASS0-EMPTY";
    }

    /** CL-REPLACE: TP-PID 0x41 to 0x47 give TYPE1 to TYPE7. */
    public static final class Replace {
        private Replace() {}

        public static final String TYPE1 = "TYPE1";
        public static final String TYPE2 = "TYPE2";
        public static final String TYPE3 = "TYPE3";
        public static final String TYPE4 = "TYPE4";
        public static final String TYPE5 = "TYPE5";
        public static final String TYPE6 = "TYPE6";
        public static final String TYPE7 = "TYPE7";
    }

    /** CL-SIM-DD. The order of the detection rules is SAT, WIB, NOSEC, RFM and RAM, OTHER. */
    public static final class SimOta {
        private SimOta() {}

        public static final String SAT = "SIM-OTA-SAT";
        public static final String WIB = "SIM-OTA-WIB";
        public static final String NOSEC = "SIM-OTA-NOSEC";
        public static final String RFM = "SIM-OTA-RFM";
        public static final String RAM = "SIM-OTA-RAM";
        public static final String OTHER = "SIM-OTA-OTHER";
    }

    /** CL-SIM-STORED. */
    public static final class SimStored {
        private SimStored() {}

        public static final String CLASS2 = "CLASS2";
    }

    /** CL-TELEMATIC: the kind of telematic interworking (TP-PID 0x20 to 0x3F). */
    public static final class Telematic {
        private Telematic() {}

        public static final String FAX = "FAX";
        public static final String TELEX = "TELEX";
        public static final String PAGING = "PAGING";
        public static final String VOICE = "VOICE";
        public static final String ERMES = "ERMES";
        public static final String X400 = "X400";
        public static final String EMAIL = "EMAIL";
        public static final String OTHER = "OTHER";
    }

    /** CL-MWI. */
    public static final class Mwi {
        private Mwi() {}

        public static final String VOICEMAIL = "VOICEMAIL";
        public static final String FAX = "FAX";
        public static final String EMAIL = "EMAIL";
        public static final String OTHER = "OTHER";
        public static final String EXTENDED = "EXTENDED";
        public static final String DISCARD_SILENT = "DISCARD-SILENT";
    }

    /** CL-STATUS: from TP-ST. */
    public static final class Status {
        private Status() {}

        public static final String DELIVERED = "DELIVERED";
        public static final String PENDING = "PENDING";
        public static final String FAILED = "FAILED";
    }

    /**
     * CL-DATA-PORT. A port in the port table gets its port name code. The port table ({@code PortNames}) maps
     * each port to one of these codes. ports 9204 and 9205 are CL-SMART, ports 9206 and 9207 are
     * {@link #WAP_VCARD_VCALENDAR}.
     */
    public static final class DataPort {
        private DataPort() {}

        public static final String UNKNOWN = "UNKNOWN";
        public static final String PORT0 = "PORT0";
        public static final String WAP_PUSH = "WAP-PUSH";
        public static final String WAP_PUSH_SECURE = "WAP-PUSH-SECURE";
        public static final String WAP_CONNECTIONLESS_SESSION = "WAP-CONNECTIONLESS-SESSION";
        public static final String WAP_SESSION = "WAP-SESSION";
        public static final String WAP_CONNECTIONLESS_SESSION_SECURE = "WAP-CONNECTIONLESS-SESSION-SECURE";
        public static final String WAP_SESSION_SECURE = "WAP-SESSION-SECURE";
        public static final String WAP_VCARD_VCALENDAR = "WAP-VCARD-VCALENDAR";
        public static final String WAP_WTA_CONNECTIONLESS_SESSION_SECURE = "WAP-WTA-CONNECTIONLESS-SESSION-SECURE";
        public static final String WAP_WTA_SESSION_SECURE = "WAP-WTA-SESSION-SECURE";
        public static final String SERVICE_CARD = "SERVICE-CARD";
        public static final String INTERNET_ACCESS_CONFIG = "INTERNET-ACCESS-CONFIG";
        public static final String DYNAMIC_MENU_CONTROL = "DYNAMIC-MENU-CONTROL";
        public static final String MESSAGE_ACCESS = "MESSAGE-ACCESS";
        public static final String EMAIL_NOTIFICATION = "EMAIL-NOTIFICATION";
        public static final String SYNCML_OTA_CONFIG = "SYNCML-OTA-CONFIG";
        public static final String WAP_OTA_CONFIG = "WAP-OTA-CONFIG";
    }

    /** CL-SMART: Nokia Smart Messaging ports 5505, 5506, 5507, 5514, 9204, 9205. */
    public static final class Smart {
        private Smart() {}

        public static final String RINGTONE = "RINGTONE";
        public static final String OPLOGO = "OPLOGO";
        public static final String CLILOGO = "CLILOGO";
        public static final String PICTURE = "PICTURE";
        public static final String VCARD = "VCARD";
        public static final String VCAL = "VCAL";
    }

    /** CL-LOC-SUPL. */
    public static final class LocSupl {
        private LocSupl() {}

        public static final String SMS = "SMS";
    }

    /** CL-WAP-MMS: from the MMS PDU type. */
    public static final class WapMms {
        private WapMms() {}

        public static final String NOTIFY = "NOTIFY";
        public static final String DELIVERY_REPORT = "DELIVERY-REPORT";
        public static final String READ_REPORT = "READ-REPORT";
    }

    /** CL-WAP-SISL. */
    public static final class WapSisl {
        private WapSisl() {}

        public static final String SI = "SI";
        public static final String SI_DELETE = "SI-DELETE";
        public static final String SL = "SL";
    }

    /** CL-WAP-CP: from the SEC parameter. */
    public static final class WapCp {
        private WapCp() {}

        public static final String NETWPIN = "NETWPIN";
        public static final String USERPIN = "USERPIN";
        public static final String USERNETWPIN = "USERNETWPIN";
        public static final String USERPINMAC = "USERPINMAC";
        public static final String NOSEC = "NOSEC";
    }

    /** CL-WAP-DM. */
    public static final class WapDm {
        private WapDm() {}

        public static final String NOTIFY = "NOTIFY";
        public static final String BOOTSTRAP = "BOOTSTRAP";
    }

    /** CL-WAP-SUPL: from the SUPL INIT notification type (: 0 and 4 SILENT, 1 NOTIFY, 2 and 3 VERIFY). */
    public static final class WapSupl {
        private WapSupl() {}

        public static final String NOTIFY = "NOTIFY";
        public static final String VERIFY = "VERIFY";
        public static final String SILENT = "SILENT";
    }

    /** CL-WAP-VCARD. */
    public static final class WapVcard {
        private WapVcard() {}

        public static final String VCARD = "VCARD";
        public static final String VCAL = "VCAL";
    }

    /**
     * CL-CB: CMAS and ETWS levels, channel 50, and other channels. With {@link #PUBLIC_SAFETY}, {@link #OPERATOR}
     * and {@link #ETWS_OTHER}, every emergency message identifier of 3GPP TS 23.041 has a code
     * ({@code pduparse.CbCategories} maps them).
     */
    public static final class Cb {
        private Cb() {}

        public static final String PRESIDENTIAL = "PRESIDENTIAL";
        public static final String EXTREME = "EXTREME";
        public static final String SEVERE = "SEVERE";
        public static final String AMBER = "AMBER";
        public static final String TEST = "TEST";
        public static final String EXERCISE = "EXERCISE";
        /** CMAS public safety alert, 4396 and 4397. */
        public static final String PUBLIC_SAFETY = "PUBLIC-SAFETY";
        /** CMAS operator defined use, 4382 and 4395. */
        public static final String OPERATOR = "OPERATOR";
        public static final String ETWS_EQ = "ETWS-EQ";
        public static final String ETWS_TSUNAMI = "ETWS-TSUNAMI";
        public static final String ETWS_BOTH = "ETWS-BOTH";
        public static final String ETWS_TEST = "ETWS-TEST";
        /** ETWS other emergency type (4356) and the ETWS future extension identifiers 4357 to 4359. */
        public static final String ETWS_OTHER = "ETWS-OTHER";
        public static final String AREA_INFO = "AREA-INFO";
        public static final String CHANNEL = "CHANNEL";
    }

    /** CL-MO-STK (name CL-MO-SIM). */
    public static final class MoStk {
        private MoStk() {}

        public static final String SEND_SMS = "SEND-SMS";
    }

    /** True when {@code c} carries a free-form subtype (see the class comment), not a fixed code. */
    public static boolean isFreeForm(ClassId c) {
        if (c == null) return false;
        switch (c) {
            case MALFORMED_SMS:
            case UNREADABLE_SMS:
            case WAP_UNREADABLE:
            case WAP_OTHER:
            case INJECTED:
            case MO_VISIBLE:
            case MO_UNPERSISTED:
            case MO_RAW:
            case MO_DATA:
                return true;
            default:
                return false;
        }
    }

    /** The fixed subtype codes of {@code c}, in declaration order. Empty for a class without fixed codes. */
    public static List<String> known(ClassId c) {
        if (c == null) return List.of();
        switch (c) {
            case SILENT:
                return List.of(Silent.TYPE0, Silent.TYPE0_PORT);
            case FLASH:
                return List.of(Flash.CLASS0, Flash.CLASS0_EMPTY);
            case REPLACE:
                return List.of(Replace.TYPE1, Replace.TYPE2, Replace.TYPE3, Replace.TYPE4, Replace.TYPE5,
                        Replace.TYPE6, Replace.TYPE7);
            case SIM_DD:
                return List.of(SimOta.SAT, SimOta.WIB, SimOta.NOSEC, SimOta.RFM, SimOta.RAM, SimOta.OTHER);
            case SIM_STORED:
                return List.of(SimStored.CLASS2);
            case TELEMATIC:
                return List.of(Telematic.FAX, Telematic.TELEX, Telematic.PAGING, Telematic.VOICE, Telematic.ERMES,
                        Telematic.X400, Telematic.EMAIL, Telematic.OTHER);
            case MWI:
                return List.of(Mwi.VOICEMAIL, Mwi.FAX, Mwi.EMAIL, Mwi.OTHER, Mwi.EXTENDED, Mwi.DISCARD_SILENT);
            case STATUS:
                return List.of(Status.DELIVERED, Status.PENDING, Status.FAILED);
            case DATA_PORT:
                return List.of(DataPort.UNKNOWN, DataPort.PORT0, DataPort.WAP_PUSH, DataPort.WAP_PUSH_SECURE,
                        DataPort.WAP_CONNECTIONLESS_SESSION, DataPort.WAP_SESSION,
                        DataPort.WAP_CONNECTIONLESS_SESSION_SECURE, DataPort.WAP_SESSION_SECURE,
                        DataPort.WAP_VCARD_VCALENDAR, DataPort.WAP_WTA_CONNECTIONLESS_SESSION_SECURE,
                        DataPort.WAP_WTA_SESSION_SECURE, DataPort.SERVICE_CARD, DataPort.INTERNET_ACCESS_CONFIG,
                        DataPort.DYNAMIC_MENU_CONTROL, DataPort.MESSAGE_ACCESS, DataPort.EMAIL_NOTIFICATION,
                        DataPort.SYNCML_OTA_CONFIG, DataPort.WAP_OTA_CONFIG);
            case SMART:
                return List.of(Smart.RINGTONE, Smart.OPLOGO, Smart.CLILOGO, Smart.PICTURE, Smart.VCARD, Smart.VCAL);
            case LOC_SUPL:
                return List.of(LocSupl.SMS);
            case WAP_MMS:
                return List.of(WapMms.NOTIFY, WapMms.DELIVERY_REPORT, WapMms.READ_REPORT);
            case WAP_SISL:
                return List.of(WapSisl.SI, WapSisl.SI_DELETE, WapSisl.SL);
            case WAP_CP:
                return List.of(WapCp.NETWPIN, WapCp.USERPIN, WapCp.USERNETWPIN, WapCp.USERPINMAC, WapCp.NOSEC);
            case WAP_DM:
                return List.of(WapDm.NOTIFY, WapDm.BOOTSTRAP);
            case WAP_SUPL:
                return List.of(WapSupl.NOTIFY, WapSupl.VERIFY, WapSupl.SILENT);
            case WAP_VCARD:
                return List.of(WapVcard.VCARD, WapVcard.VCAL);
            case CB:
                return List.of(Cb.PRESIDENTIAL, Cb.EXTREME, Cb.SEVERE, Cb.AMBER, Cb.TEST, Cb.EXERCISE,
                        Cb.PUBLIC_SAFETY, Cb.OPERATOR, Cb.ETWS_EQ, Cb.ETWS_TSUNAMI, Cb.ETWS_BOTH, Cb.ETWS_TEST,
                        Cb.ETWS_OTHER, Cb.AREA_INFO, Cb.CHANNEL);
            case MO_STK:
                return List.of(MoStk.SEND_SMS);
            default:
                return List.of();
        }
    }
}
