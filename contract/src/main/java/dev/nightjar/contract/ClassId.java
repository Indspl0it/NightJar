package dev.nightjar.contract;

/**
 * Message classes. Wire names are the class ids.
 *
 * <p>Never change a wire name or the order of the existing constants: new classes go at the end. Newer class names that
 * describe an existing class keep the existing wire name: CL-MO-SIM is {@link #MO_STK}, and CL-MO-APP is
 * {@link #MO_VISIBLE}, {@link #MO_UNPERSISTED} or {@link #MO_RAW} (how the app sent the message). Display names live in
 * the app. The subtype codes of each class are in {@link Subtype}.
 */
public enum ClassId implements WireEnum {
    NORMAL("CL-NORMAL"),
    SILENT("CL-SILENT"),
    FLASH("CL-FLASH"),
    REPLACE("CL-REPLACE"),
    MWI("CL-MWI"),
    VVM("CL-VVM"),
    MISSED_CALL("CL-MISSED-CALL"),
    CARRIER("CL-CARRIER"),
    RCS_PROV("CL-RCS-PROV"),
    APP_TOKEN("CL-APP-TOKEN"),
    DATA_PORT("CL-DATA-PORT"),
    WAP_MMS("CL-WAP-MMS"),
    WAP_SISL("CL-WAP-SISL"),
    WAP_CP("CL-WAP-CP"),
    WAP_DM("CL-WAP-DM"),
    WAP_SUPL("CL-WAP-SUPL"),
    WAP_OTHER("CL-WAP-OTHER"),
    SIM_DD("CL-SIM-DD"),
    SIM_STORED("CL-SIM-STORED"),
    STATUS("CL-STATUS"),
    CB("CL-CB"),
    OEM("CL-OEM"),
    INJECTED("CL-INJECTED"),
    MO_VISIBLE("CL-MO-VISIBLE"),
    MO_UNPERSISTED("CL-MO-UNPERSISTED"),
    MO_DATA("CL-MO-DATA"),
    MO_RAW("CL-MO-RAW"),
    MO_STK("CL-MO-STK"),
    MO_VVM("CL-MO-VVM"),

    // Classes added by the classification redesign.
    RETURN_CALL("CL-RETURN-CALL"),
    ME_DOWNLOAD("CL-ME-DOWNLOAD"),
    ME_DEPERSO("CL-ME-DEPERSO"),
    CLASS3("CL-CLASS3"),
    TELEMATIC("CL-TELEMATIC"),
    EMPTY("CL-EMPTY"),
    BINARY("CL-BINARY"),
    COMPRESSED("CL-COMPRESSED"),
    MULTIPART_INCOMPLETE("CL-MULTIPART-INCOMPLETE"),
    MALFORMED_SMS("CL-MALFORMED-SMS"),
    SMART("CL-SMART"),
    LOC_SUPL("CL-LOC-SUPL"),
    WAP_CO("CL-WAP-CO"),
    WAP_DS("CL-WAP-DS"),
    WAP_EMN("CL-WAP-EMN"),
    WAP_DRM("CL-WAP-DRM"),
    WAP_VCARD("CL-WAP-VCARD"),
    WAP_IMPS("CL-WAP-IMPS"),
    WAP_UNREADABLE("CL-WAP-UNREADABLE"),
    CB_SIM("CL-CB-SIM"),
    /**
     * An SMS that NightJar cannot read: the TPDU has the wrong message type (AN-MTI, TP-MTI 1 or 3), or the parser
     * failed. AOSP still reads such a PDU as an SMS-DELIVER, so a held message can be released. Known
     * damage (a cut address, a PDU that is too short) is {@link #MALFORMED_SMS}, which wins when both apply.
     */
    UNREADABLE_SMS("CL-UNREADABLE-SMS");

    private final String wire;

    ClassId(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
