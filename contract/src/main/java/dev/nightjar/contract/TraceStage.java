package dev.nightjar.contract;

/** Trace stage wire names. */
public enum TraceStage implements WireEnum {
    RADIO_CS_NEW_SMS("radio.cs.newSms"),
    RADIO_CS_STATUS_REPORT("radio.cs.statusReport"),
    RADIO_CS_SMS_ON_SIM("radio.cs.smsOnSim"),
    SIM_RECORDS_HANDLE_SMS("sim.records.handleSms"),
    RADIO_IMS_ON_SMS_RECEIVED("radio.ims.onSmsReceived"),
    RADIO_IMS_STATUS_REPORT("radio.ims.statusReport"),
    INJECT_APP("inject.app"),
    INJECT_RELEASE("inject.release"),
    INJECT_CANARY("inject.canary"),
    INJECT_DEBUG("inject.debug"),
    HANDLER_DISPATCH_MESSAGE("handler.dispatchMessage"),
    HANDLER_RADIO_SPECIFIC("handler.radioSpecific"),
    MWI_INDICATOR("mwi.indicator"),
    FW_HEAD_FILTER("fw.headFilter"),
    FILTER_VVM("filter.vvm"),
    FILTER_MISSED_CALL("filter.missedCall"),
    FILTER_CARRIER("filter.carrier"),
    APP_TOKEN("appToken"),
    HANDLER_DISPATCH_INTENT("handler.dispatchIntent"),
    HANDLER_DROP_FILTERED("handler.dropFiltered"),
    BLOCKLIST("blocklist"),
    WAP_DISPATCH("wap.dispatch"),
    USIM_DOWNLOAD("usim.download"),
    USIM_POR("usim.por"),
    RAW_TABLE_SCAN("rawTable.scan"),
    UNLOCK_REPLAY("unlock.replay"),
    CB_HANDLE("cb.handle"),
    CB_CONFIG("cb.config"),
    MO_REQUEST("mo.request"),
    MO_DISPATCH("mo.dispatch"),
    MO_STK("mo.stk"),
    MO_RESULT("mo.result"),
    OEM_CHECK("oem.check"),
    OEM_REDIRECT("oem.redirect"),
    OEM_SCAN_RAW_TABLE("oem.scanRawTable"),
    SEAL_TIMEOUT("seal.timeout"),
    FATE("fate"),
    SPOOL_EVICTED("spool.evicted"),
    SPOOL_CORRUPT("spool.corrupt");

    private final String wire;

    TraceStage(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
