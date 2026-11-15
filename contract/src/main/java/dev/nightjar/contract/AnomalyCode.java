package dev.nightjar.contract;

/**
 * Anomaly codes. The parser never throws: it returns facts plus these codes, and the payload
 * sends them in the event field {@link Ipc.EventKey#ANOMALIES}. Display names live in the app.
 *
 * <p>{@link #forcedClass} is the class policy:
 *
 * <ul>
 *   <li>a hard anomaly stops a full parse and gives {@link ClassId#MALFORMED_SMS};
 *   <li>{@link #MTI} (wrong message type) stops a full parse and gives {@link ClassId#UNREADABLE_SMS}. A hard anomaly
 *  of {@link ClassId#MALFORMED_SMS} wins over it;
 *   <li>a WSP anomaly that stops the push parse gives {@link ClassId#WAP_UNREADABLE};
 *   <li>{@link #OTA_HDR} gives {@link ClassId#SIM_DD} with subtype {@link Subtype.SimOta#OTHER};
 *   <li>every other anomaly keeps the class. The app raises the severity to Suspicious or more.
 * </ul>
 */
public enum AnomalyCode implements WireEnum {
    ADDR_TRUNC("AN-ADDR-TRUNC", ClassId.MALFORMED_SMS),
    ADDR_LEN("AN-ADDR-LEN", ClassId.MALFORMED_SMS),
    ADDR_TOA("AN-ADDR-TOA", null),
    PDU_SHORT("AN-PDU-SHORT", ClassId.MALFORMED_SMS),
    MTI("AN-MTI", ClassId.UNREADABLE_SMS),
    PID_RSV("AN-PID-RSV", null),
    DCS_RSV("AN-DCS-RSV", null),
    UDL_LONG("AN-UDL-LONG", ClassId.MALFORMED_SMS),
    UDL_SHORT("AN-UDL-SHORT", null),
    UDH_LEN("AN-UDH-LEN", ClassId.MALFORMED_SMS),
    IE_OVERRUN("AN-IE-OVERRUN", ClassId.MALFORMED_SMS),
    IE_RSV("AN-IE-RSV", null),
    IE_DUP("AN-IE-DUP", null),
    PORT_ZERO("AN-PORT-ZERO", null),
    CONCAT_BAD("AN-CONCAT-BAD", null),
    CONCAT_DUP("AN-CONCAT-DUP", null),
    WSP_TYPE("AN-WSP-TYPE", ClassId.WAP_UNREADABLE),
    WSP_HLEN("AN-WSP-HLEN", ClassId.WAP_UNREADABLE),
    WSP_CTYPE("AN-WSP-CTYPE", ClassId.WAP_UNREADABLE),
    WSP_APPID("AN-WSP-APPID", null),
    WBXML_VER("AN-WBXML-VER", null),
    WBXML_STR("AN-WBXML-STR", null),
    WBXML_TOK("AN-WBXML-TOK", null),
    OTA_HDR("AN-OTA-HDR", ClassId.SIM_DD);

    private final String wire;
    private final ClassId forcedClass;

    AnomalyCode(String wire, ClassId forcedClass) {
        this.wire = wire;
        this.forcedClass = forcedClass;
    }

    @Override
    public String wire() {
        return wire;
    }

    /** The class this anomaly forces, or {@code null} when the message keeps its class. */
    public ClassId forcedClass() {
        return forcedClass;
    }

    /**
     * The subtype that goes with {@link #forcedClass()}: the anomaly wire name for CL-MALFORMED-SMS,
     * CL-UNREADABLE-SMS and CL-WAP-UNREADABLE, {@link Subtype.SimOta#OTHER} for {@link #OTA_HDR}, else {@code null}.
     */
    public String forcedSubtype() {
        if (forcedClass == null) return null;
        if (forcedClass == ClassId.SIM_DD) return Subtype.SimOta.OTHER;
        return wire;
    }
}
