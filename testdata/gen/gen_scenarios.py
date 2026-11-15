"""Generate testdata/scenarios.json.
Usage: python -I gen_scenarios.py <scenarios.json> <intent.json>
The intent file is the input of check_scenarios.py. Synthetic data only."""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gen_lib import *  # noqa: F401,F403

SCEN = []
INTENT = {}
ANOMALY_NAMES = {
    "AN-ADDR-TRUNC": "address cut off", "AN-ADDR-LEN": "address too long",
    "AN-ADDR-TOA": "unknown address type", "AN-PDU-SHORT": "message too short",
    "AN-MTI": "unknown message type", "AN-PID-RSV": "unknown protocol identifier",
    "AN-DCS-RSV": "unknown coding scheme", "AN-UDL-LONG": "length is more than the data",
    "AN-UDL-SHORT": "data is more than the length", "AN-UDH-LEN": "header length is wrong",
    "AN-IE-OVERRUN": "header element is too long", "AN-IE-RSV": "unknown header element",
    "AN-IE-DUP": "repeated port element", "AN-PORT-ZERO": "port 0",
    "AN-CONCAT-BAD": "bad multipart numbers", "AN-CONCAT-DUP": "repeated multipart number",
    "AN-WSP-TYPE": "unknown WSP type", "AN-WSP-HLEN": "WSP header length is wrong",
    "AN-WSP-CTYPE": "content type is unreadable", "AN-WSP-APPID": "application ID is unreadable",
    "AN-WBXML-VER": "unknown WBXML version", "AN-WBXML-STR": "WBXML string table is wrong",
    "AN-WBXML-TOK": "bad WBXML token", "AN-OTA-HDR": "SIM command header is wrong",
}
HARD = ["AN-ADDR-TRUNC", "AN-ADDR-LEN", "AN-PDU-SHORT", "AN-MTI", "AN-UDL-LONG", "AN-UDH-LEN", "AN-IE-OVERRUN"]
WSP_HARD = ["AN-WSP-TYPE", "AN-WSP-HLEN", "AN-WSP-CTYPE"]
SOFT = ["AN-ADDR-TOA", "AN-PID-RSV", "AN-DCS-RSV", "AN-UDL-SHORT", "AN-IE-RSV", "AN-IE-DUP", "AN-PORT-ZERO",
        "AN-CONCAT-BAD", "AN-CONCAT-DUP", "AN-WSP-APPID", "AN-WBXML-VER", "AN-WBXML-STR", "AN-WBXML-TOK", "AN-OTA-HDR"]
TD = "3GPP TS 23.040"
TS_DCS = "3GPP TS 23.038"


def add(id, family, title, tier, expect, spec, intent=None, check_spec=False, note=None, **fields):
    s = {"id": id, "family": family, "title": title, "tier": tier}
    s.update(fields)
    s["expect"] = expect
    s["spec"] = spec
    if check_spec:
        s["checkSpec"] = True
    if note:
        s["note"] = note
    assert all(x["id"] != id for x in SCEN), id
    SCEN.append(s)
    if intent is not None:
        INTENT[id] = intent


def ex(cls, sub, sev, act, title, anomalies=None, **more):
    e = {"class": cls, "subtype": sub, "severity": sev, "action": act, "title": title}
    if anomalies:
        e["anomalies"] = anomalies
    e.update(more)
    return e


def pat_note(rule):
    """The expected severity is the one a pattern rule gives a clean history."""
    return "Severity is with an unknown sender and no history, rule %s." % rule


def pdu_scenario(id, family, title, tier, pdu, expect, spec, intent, **kw):
    add(id, family, title, tier, expect, spec, intent=intent, pdu=hx(pdu), **kw)


def floor(sev, anomalies):
    order = ["Info", "Notice", "Suspicious", "High"]
    if anomalies and order.index(sev) < 2:
        return "Suspicious"
    return sev


def normal_soft(code):
    return ex("CL-NORMAL", None, "Suspicious", "Allow", "Normal message", anomalies=[code])


# ---- text ---------------------------------------------------------------------------------------
txt = "Test message 1"
n, u = ud7(txt)
pdu_scenario("text-7bit", "text", "Text, GSM 7-bit", 1, deliver(udl=n, ud=u),
             ex("CL-NORMAL", None, "Info", "Allow", "Normal message"), TD + " 9.2.3.16, " + TS_DCS + " 4",
             {"mti": 0, "pid": 0, "dcs": 0, "udhi": False, "anomalies": [], "text": txt}, text=txt)

p1 = ("Test message 1 " * 10)[:150]
p2 = "Test message 2"
parts = []
for i, t in ((1, p1), (2, p2)):
    n, u = ud7(t, ie(0x00, [0x2A, 2, i]))
    parts.append((t, deliver(udl=n, ud=u, udhi=True)))
add("text-multipart", "text", "Text, two parts", 1,
    ex("CL-NORMAL", None, "Info", "Allow", "Normal message"), TD + " 9.2.3.24.1 (concatenation IE 0x00)",
    intent={"parts": [{"mti": 0, "udhi": True, "concat": [0x2A, 2, 1], "text": p1, "anomalies": []},
                      {"mti": 0, "udhi": True, "concat": [0x2A, 2, 2], "text": p2, "anomalies": []}]},
    text=p1 + p2, parts=[{"text": t, "pdu": hx(p)} for t, p in parts])

ucs = "Test 中文 1"
n, u = ud_bytes(ucs.encode("utf-16-be"))
pdu_scenario("text-ucs2", "text", "Text, UCS-2", 1, deliver(dcs=0x08, udl=n, ud=u),
             ex("CL-NORMAL", None, "Info", "Allow", "Normal message"), TD + " 9.2.3.16, " + TS_DCS + " 4",
             {"mti": 0, "pid": 0, "dcs": 0x08, "udhi": False, "anomalies": [], "text": ucs}, text=ucs)

n, u = ud7("Test message 1", ie(0x00, [0x2B, 2, 1]))
pdu_scenario("multipart-incomplete", "text", "Multipart, part 2 never arrives", 2, deliver(udl=n, ud=u, udhi=True),
             ex("CL-MULTIPART-INCOMPLETE", None, "Notice", "Notify", "Incomplete multipart message"),
             TD + " 9.2.3.24.1", {"mti": 0, "udhi": True, "concat": [0x2B, 2, 1], "anomalies": []},
             note="Send this part only. Do not send part 2. NightJar reports the class 24 hours after part 1.",
             waitHours=24)


# ---- ports --------------------------------------------------------------------------------------
def data_port(id, port, payload, expect, spec, anomalies=None, tier=1, title=None, check_spec=False, **kw):
    n, u = ud_bytes(payload, port_ie(port))
    add(id, "data-port", title or ("8-bit data to port %d" % port), tier, expect, spec,
        intent={"mti": 0, "pid": 0, "dcs": 0x04, "udhi": True, "port": port, "anomalies": anomalies or []},
        check_spec=check_spec, pdu=hx(deliver(dcs=0x04, udl=n, ud=u, udhi=True)),
        port=port, payload=hx(payload), **kw)


data_port("data-port-2948-raw", 2948, H("DEADBEEF"),
          ex("CL-WAP-UNREADABLE", "AN-WSP-TYPE", "High", "Block", "Malformed WAP push: unknown WSP type",
             anomalies=["AN-WSP-TYPE"]), "WAP-230 8.2.1", anomalies=["AN-WSP-TYPE"],
          title="Data to port 2948, not a WSP push")
data_port("data-port-2949", 2949, H("01020304"),
          ex("CL-DATA-PORT", "WAP-PUSH-SECURE", "Notice", "Allow", "WAP push (secure)"),
          "IANA port 2949 (wap-pushsecure)")
data_port("data-port-9200", 9200, H("01020304"),
          ex("CL-DATA-PORT", "WAP-CONNECTIONLESS-SESSION", "Notice", "Allow",
             "WAP connectionless session"), "IANA port 9200 (wap-wsp)")
for p, name, sub in ((9201, "WAP session", "WAP-SESSION"),
                     (9202, "WAP connectionless session (secure)", "WAP-CONNECTIONLESS-SESSION-SECURE"),
                     (9203, "WAP session (secure)", "WAP-SESSION-SECURE"),
                     (9206, "WAP vCard and vCalendar", "WAP-VCARD-VCALENDAR"),
                     (9207, "WAP vCard and vCalendar", "WAP-VCARD-VCALENDAR")):
    data_port("data-port-%d" % p, p, H("01020304"),
              ex("CL-DATA-PORT", sub, "Notice", "Allow", name),
              "IANA port %d" % p, check_spec=True)
# JSR 120 (WMA 1.1) Appendix A restricted ports that carry phone configuration. Notify.
for p, name, sub in ((5503, "Internet access configuration", "INTERNET-ACCESS-CONFIG"),
                     (49996, "SyncML OTA configuration", "SYNCML-OTA-CONFIG"),
                     (49999, "WAP OTA configuration", "WAP-OTA-CONFIG")):
    data_port("data-port-%d" % p, p, H("01020304"),
              ex("CL-DATA-PORT", sub, "Notice", "Notify", name),
              "JSR 120 (WMA 1.1) Appendix A, restricted port %d" % p,
              note="The payload is dummy bytes. The port carries phone settings, so the default action is Notify.")
data_port("data-port-16001", 16001, H("01020304"),
          ex("CL-DATA-PORT", "UNKNOWN", "Suspicious", "Notify", "Data message to port 16001"), "Plan section 8.4.2")
data_port("data-port-0", 0, H("01020304"),
          ex("CL-DATA-PORT", "PORT0", "Suspicious", "Hold", "Data message to port 0", anomalies=["AN-PORT-ZERO"]),
          TD + " 9.2.3.24.4", anomalies=["AN-PORT-ZERO"],
          note="Android may refuse port 0 in sendDataMessage. Then send the pdu as tier 2.")
for p, sub, t in ((5505, "RINGTONE", "Ringtone"), (5506, "OPLOGO", "Operator logo"), (5507, "CLILOGO", "Caller logo"),
                  (5514, "PICTURE", "Picture message"), (9204, "VCARD", "Contact card"), (9205, "VCAL", "Calendar entry")):
    data_port("smart-%s" % sub.lower(), p, H("0102030405"),
              ex("CL-SMART", sub, "Notice", "Hold", t), "Nokia Smart Messaging, IANA port %d" % p, check_spec=True,
              title="Smart messaging to port %d" % p,
              note="Ports 9204 and 9205 are also in the port list of plan section 8.1. CL-04 decides which wins."
              if p in (9204, 9205) else None)
data_port("loc-supl-sms-7275", 7275, H("01020304"),
          ex("CL-LOC-SUPL", "SMS", "High", "Block", "Location request (SUPL)"), "OMA SUPL, IANA port 7275",
          check_spec=True, title="SUPL INIT over SMS to port 7275", note=pat_note("PAT-03"))

# ---- silent, flash, replace, PID classes ----------------------------------------------------------
n, u = ud7("Test")
pdu_scenario("silent-type0", "silent", "Silent SMS (Type 0)", 2, deliver(pid=0x40, udl=n, ud=u),
             ex("CL-SILENT", "TYPE0", "Suspicious", "Notify", "Silent SMS (Type 0)"), TD + " 9.2.3.9",
             {"mti": 0, "pid": 0x40, "dcs": 0, "udhi": False, "anomalies": []})
n, u = ud_bytes(H("01020304"), port_ie(16001))
pdu_scenario("silent-type0-port", "silent", "Silent SMS (Type 0) to a port", 2,
             deliver(pid=0x40, dcs=0x04, udl=n, ud=u, udhi=True),
             ex("CL-SILENT", "TYPE0-PORT", "High", "Notify", "Silent SMS (Type 0) to port 16001",
                extraSubtypes=["CL-DATA-PORT:UNKNOWN"]), TD + " 9.2.3.9",
             {"mti": 0, "pid": 0x40, "dcs": 4, "udhi": True, "port": 16001, "anomalies": []})
n, u = ud7("Test message 1")
pdu_scenario("flash-class0", "flash", "Flash SMS (Class 0)", 2, deliver(dcs=0x10, udl=n, ud=u),
             ex("CL-FLASH", "CLASS0", "Notice", "Hold", "Flash SMS (Class 0)"), TS_DCS + " 4",
             {"mti": 0, "pid": 0, "dcs": 0x10, "udhi": False, "anomalies": [], "text": "Test message 1"})
pdu_scenario("flash-class0-empty", "flash", "Empty flash SMS (Class 0)", 2, deliver(dcs=0x10, udl=0),
             ex("CL-FLASH", "CLASS0-EMPTY", "Suspicious", "Hold", "Empty flash SMS (Class 0)"), TS_DCS + " 4",
             {"mti": 0, "pid": 0, "dcs": 0x10, "udhi": False, "anomalies": []})
for i in range(1, 8):
    n, u = ud7("Test message %d" % i)
    pdu_scenario("replace-type%d" % i, "replace", "Replacement message (type %d)" % i, 2,
                 deliver(pid=0x40 + i, udl=n, ud=u),
                 ex("CL-REPLACE", "TYPE%d" % i, "Suspicious", "Hold", "Replacement message (type %d)" % i),
                 TD + " 9.2.3.9", {"mti": 0, "pid": 0x40 + i, "dcs": 0, "udhi": False, "anomalies": []},
                 note=pat_note("PAT-09"))
n, u = ud7("Test")
pdu_scenario("return-call", "pid", "Return call request", 2, deliver(pid=0x5F, udl=n, ud=u),
             ex("CL-RETURN-CALL", None, "Notice", "Hold", "Return call request"), TD + " 9.2.3.9",
             {"mti": 0, "pid": 0x5F, "dcs": 0, "udhi": False, "anomalies": []})
pdu_scenario("me-download", "pid", "Data download to the phone", 2, deliver(pid=0x7D, dcs=0xF4, udl=4, ud=bytes(4)),
             ex("CL-ME-DOWNLOAD", None, "Suspicious", "Block", "Data download to the phone",
                extraSubtypes=["CL-FLASH:CLASS0"]), TD + " 9.2.3.9",
             {"mti": 0, "pid": 0x7D, "dcs": 0xF4, "udhi": False, "anomalies": []})
pdu_scenario("me-deperso", "pid", "Phone unlock message", 2, deliver(pid=0x7E, dcs=0xF4, udl=4, ud=bytes(4)),
             ex("CL-ME-DEPERSO", None, "High", "Hold", "Phone unlock message (de-personalization)",
                extraSubtypes=["CL-FLASH:CLASS0"]), TD + " 9.2.3.9",
             {"mti": 0, "pid": 0x7E, "dcs": 0xF4, "udhi": False, "anomalies": []})
n, u = ud7("Test message 1")
pdu_scenario("sim-stored-class2", "sim-ota", "Saved to the SIM (class 2 text)", 2, deliver(dcs=0x12, udl=n, ud=u),
             ex("CL-SIM-STORED", "CLASS2", "Notice", "Allow", "Saved to the SIM"), TS_DCS + " 4",
             {"mti": 0, "pid": 0, "dcs": 0x12, "udhi": False, "anomalies": []})
pdu_scenario("class3-text", "flash", "Message for a connected device (Class 3)", 2, deliver(dcs=0x13, udl=n, ud=u),
             ex("CL-CLASS3", None, "Notice", "Allow", "Message for a connected device (Class 3)"), TS_DCS + " 4",
             {"mti": 0, "pid": 0, "dcs": 0x13, "udhi": False, "anomalies": []})

n, u = ud7("Test")
for sub, pid, kind in (("FAX", 0x22, "fax"), ("TELEX", 0x21, "telex"), ("PAGING", 0x26, "paging"), ("VOICE", 0x24, "voice"),
                       ("ERMES", 0x25, "ERMES"), ("X400", 0x31, "X.400"), ("EMAIL", 0x32, "email"), ("OTHER", 0x27, "other")):
    pdu_scenario("telematic-%s" % sub.lower(), "pid", "Telematic message: %s" % kind, 2, deliver(pid=pid, udl=n, ud=u),
                 ex("CL-TELEMATIC", sub, "Notice", "Hold", "Telematic message: %s" % kind), TD + " 9.2.3.9",
                 {"mti": 0, "pid": pid, "dcs": 0, "udhi": False, "anomalies": []}, check_spec=(sub == "OTHER"))

# ---- message waiting ----------------------------------------------------------------------------
for sub, dcs, t in (("VOICEMAIL", 0xD8, "Voicemail indicator"), ("FAX", 0xD9, "Fax waiting"),
                    ("EMAIL", 0xDA, "Email waiting"), ("OTHER", 0xDB, "Message waiting")):
    pdu_scenario("mwi-%s" % sub.lower(), "mwi", t, 2, deliver(dcs=dcs, udl=0),
                 ex("CL-MWI", sub, "Info", "Allow", t), TS_DCS + " 4",
                 {"mti": 0, "pid": 0, "dcs": dcs, "udhi": False, "anomalies": []})
n, u = ud_bytes(b"", ie(0x01, [0x80, 0x02]))
pdu_scenario("mwi-iei01-voicemail", "mwi", "Voicemail indicator in the header", 2, deliver(udl=n, ud=u, udhi=True),
             ex("CL-MWI", "VOICEMAIL", "Info", "Allow", "Voicemail indicator"), TD + " 9.2.3.24.2",
             {"mti": 0, "pid": 0, "dcs": 0, "udhi": True, "iei": [0x01], "anomalies": []})
n, u = ud_bytes(b"", ie(0x23, [0x80, 0x01, 0x00, 0x00]))
pdu_scenario("mwi-extended-iei23", "mwi", "Enhanced voicemail information", 2, deliver(udl=n, ud=u, udhi=True),
             ex("CL-MWI", "EXTENDED", "Info", "Allow", "Message waiting"), TD + " 9.2.3.24.13",
             {"mti": 0, "pid": 0, "dcs": 0, "udhi": True, "iei": [0x23], "anomalies": []}, check_spec=True,
             note="The body of IEI 0x23 is a placeholder. CL-04 checks it against the spec.")
pdu_scenario("mwi-discard-silent", "mwi", "Silent message-waiting signal", 2, deliver(dcs=0xC0, udl=0),
             ex("CL-MWI", "DISCARD-SILENT", "Suspicious", "Block", "Silent message-waiting signal"), TS_DCS + " 4",
             {"mti": 0, "pid": 0, "dcs": 0xC0, "udhi": False, "anomalies": []})

# ---- status reports, empty, binary, compressed ----------------------------------------------------
for sub, st, t in (("DELIVERED", 0x00, "delivered"), ("PENDING", 0x20, "pending"), ("FAILED", 0x40, "failed")):
    add("status-%s" % sub.lower(), "status", "Delivery report: %s" % t, 2,
        ex("CL-STATUS", sub, "Info", "Allow", "Delivery report: %s" % t), TD + " 9.2.2.3, 9.2.3.15",
        intent={"mti": 2, "st": st, "anomalies": []}, pdu=hx(status_report(st)))
pdu_scenario("empty-message", "content", "Empty message", 2, deliver(udl=0),
             ex("CL-EMPTY", None, "Suspicious", "Block", "Empty message"), TD + " 9.2.3.16",
             {"mti": 0, "pid": 0, "dcs": 0, "udhi": False, "anomalies": []})
n, u = ud_bytes(H("DEADBEEF"))
pdu_scenario("binary-no-port", "content", "Binary message without a port", 2, deliver(dcs=0x04, udl=n, ud=u),
             ex("CL-BINARY", None, "Suspicious", "Hold", "Binary message without a port"), TS_DCS + " 4",
             {"mti": 0, "pid": 0, "dcs": 4, "udhi": False, "anomalies": []})
n, u = ud_bytes(H("0102030405"))
pdu_scenario("compressed", "content", "Compressed message", 2, deliver(dcs=0x20, udl=n, ud=u),
             ex("CL-COMPRESSED", None, "Notice", "Hold", "Compressed message"), TS_DCS + " 4",
             {"mti": 0, "pid": 0, "dcs": 0x20, "udhi": False, "anomalies": []},
             note="The data is dummy bytes. It is not a real compressed stream.")


# ---- anomalies ----------------------------------------------------------------------------------
def MAL(code):
    return ex("CL-MALFORMED-SMS", code, "Suspicious", "Block", "Malformed SMS: " + ANOMALY_NAMES[code], anomalies=[code])


def anomaly(code, pdu, intent_extra=None, expect=None, sid=None, spec=TD, **kw):
    sid = sid or "anomaly-" + code[3:].lower()
    intent = {"mti": 0, "anomalies": [code]}
    intent.update(intent_extra or {})
    pdu_scenario(sid, "malformed", "Anomaly %s: %s" % (code, ANOMALY_NAMES[code]), 2, pdu, expect or MAL(code),
                 spec, intent, **kw)


n, u = ud7("Test message 1")
anomaly("AN-ADDR-TRUNC", smsc() + bytes([0x04, 12, 0x91, 0x51, 0x55]), spec=TD + " 9.1.2.5")
anomaly("AN-ADDR-LEN", deliver(oa=bytes([22, 0x91]) + bcd("1555014215550142155501"), udl=n, ud=u), spec=TD + " 9.1.2.5")
anomaly("AN-ADDR-TOA", deliver(oa=oa_num(toa=0xF1), udl=n, ud=u), {"toa": 0xF1}, normal_soft("AN-ADDR-TOA"), spec=TD + " 9.1.2.5")
anomaly("AN-PDU-SHORT", smsc() + bytes([0x04]) + oa_num() + bytes([0x00, 0x00]), spec=TD + " 9.2.2.1")
# The wrong message type is CL-UNREADABLE-SMS (AOSP still reads it as a DELIVER, so it can be held and
# released). Recommended holds it.
anomaly("AN-MTI", smsc() + bytes([0x07]) + oa_num() + bytes([0, 0]) + SCTS + bytes([n]) + u, {"mti": 3},
        ex("CL-UNREADABLE-SMS", "AN-MTI", "Suspicious", "Hold", "Unreadable SMS", anomalies=["AN-MTI"]),
        spec=TD + " 9.2.3.1")
anomaly("AN-PID-RSV", deliver(pid=0x50, udl=n, ud=u), {"pid": 0x50}, normal_soft("AN-PID-RSV"), spec=TD + " 9.2.3.9")
anomaly("AN-DCS-RSV", deliver(dcs=0x80, udl=n, ud=u), {"dcs": 0x80}, normal_soft("AN-DCS-RSV"), spec=TS_DCS + " 4")
anomaly("AN-DCS-RSV", deliver(dcs=0x0C, udl=len(u), ud=u), {"dcs": 0x0C}, normal_soft("AN-DCS-RSV"), spec=TS_DCS + " 4",
        sid="anomaly-dcs-rsv-alphabet")
anomaly("AN-UDL-LONG", deliver(udl=40, ud=u), spec=TD + " 9.2.3.16")
anomaly("AN-UDL-SHORT", deliver(udl=5, ud=u), expect=normal_soft("AN-UDL-SHORT"), spec=TD + " 9.2.3.16")
# A cut header says nothing more, so the 8-bit data without a readable port is also a binary message (extra subtype).
anomaly("AN-UDH-LEN", deliver(dcs=0x04, udl=6, ud=bytes([0x7F, 1, 2, 3, 4, 5]), udhi=True), {"udhi": True, "dcs": 4},
        expect=dict(MAL("AN-UDH-LEN"), extraSubtypes=["CL-BINARY"]), spec=TD + " 9.2.3.24")
anomaly("AN-IE-OVERRUN", deliver(dcs=0x04, udl=8, ud=bytes([3, 0x05, 0x04, 0x0B, 0xDE, 0xAD, 0xBE, 0xEF]), udhi=True),
        {"udhi": True, "dcs": 4}, expect=dict(MAL("AN-IE-OVERRUN"), extraSubtypes=["CL-BINARY"]), spec=TD + " 9.2.3.24")
n2, u2 = ud_bytes(H("0102"), ie(0x30, [0x00]))
anomaly("AN-IE-RSV", deliver(dcs=0x04, udl=n2, ud=u2, udhi=True), {"udhi": True, "dcs": 4, "iei": [0x30]},
        ex("CL-BINARY", None, "Suspicious", "Hold", "Binary message without a port", anomalies=["AN-IE-RSV"]), spec=TD + " 9.2.3.24")
n2, u2 = ud_bytes(H("01020304"), port_ie(16001) + port_ie(16002))
anomaly("AN-IE-DUP", deliver(dcs=0x04, udl=n2, ud=u2, udhi=True), {"udhi": True, "dcs": 4, "port": 16002},
        ex("CL-DATA-PORT", "UNKNOWN", "Suspicious", "Notify", "Data message to port 16002", anomalies=["AN-IE-DUP"]),
        spec=TD + " 9.2.3.24.4", note="The last port element wins, as in the Android SmsHeader.")
for tag, ref, tot, seq in (("total0", 0x31, 0, 1), ("seq0", 0x32, 2, 0), ("seq-gt-total", 0x33, 2, 3)):
    nn, uu = ud7("Test message 1", ie(0x00, [ref, tot, seq]))
    anomaly("AN-CONCAT-BAD", deliver(udl=nn, ud=uu, udhi=True), {"udhi": True, "concat": [ref, tot, seq]},
            normal_soft("AN-CONCAT-BAD"), spec=TD + " 9.2.3.24.1", sid="anomaly-concat-bad-" + tag)
add("anomaly-concat-dup", "malformed", "Anomaly AN-CONCAT-DUP: repeated multipart number", 2,
    normal_soft("AN-CONCAT-DUP"), TD + " 9.2.3.24.1",
    sequence=[{"ref": "multipart-incomplete", "delaySeconds": 0}, {"ref": "multipart-incomplete", "delaySeconds": 5}],
    note="The same part number arrives twice for one reference.")


# ---- WSP / WAP push -----------------------------------------------------------------------------
def wsp_scn(id, title, wsp_bytes, expect, intent_extra, spec="WAP-230 (WSP)", family="wap", **kw):
    n, u = push_ud(wsp_bytes)
    intent = {"mti": 0, "pid": 0, "dcs": 4, "udhi": True, "port": 2948, "anomalies": []}
    intent.update(intent_extra)
    add(id, family, title, 1, expect, spec, intent=intent, port=2948, payload=hx(wsp_bytes),
        pdu=hx(deliver(dcs=4, udl=n, ud=u, udhi=True)), **kw)


def UNREAD(c):
    return ex("CL-WAP-UNREADABLE", c, "High", "Block", "Malformed WAP push: " + ANOMALY_NAMES[c], anomalies=[c])


def SISL(sub, sev, act, t, an=None):
    return ex("CL-WAP-SISL", sub, floor(sev, an), act, t, anomalies=an)


ctsic = ct_short(0x2E)
wsp_scn("anomaly-wsp-type", "Anomaly AN-WSP-TYPE: unknown WSP type", bytes([1, 0x40, 3, 0xAE, 0xAF, 0x84]),
        UNREAD("AN-WSP-TYPE"), {"anomalies": ["AN-WSP-TYPE"]}, family="malformed")
wsp_scn("anomaly-wsp-hlen", "Anomaly AN-WSP-HLEN: bad header length", bytes([1, 6, 0x40, 0xAE]),
        UNREAD("AN-WSP-HLEN"), {"anomalies": ["AN-WSP-HLEN"]}, family="malformed")
wsp_scn("anomaly-wsp-ctype", "Anomaly AN-WSP-CTYPE: unreadable content type", bytes([1, 6, 3, 0x05, 0xAF, 0x84]),
        UNREAD("AN-WSP-CTYPE"), {"anomalies": ["AN-WSP-CTYPE"]}, family="malformed")
wsp_scn("anomaly-wsp-appid", "Anomaly AN-WSP-APPID: unreadable application ID",
        bytes([1, 6, 3, 0xAE, 0xAF, 0x05]) + SI_WBXML, SISL("SI", "Notice", "Hold", "Web link push", ["AN-WSP-APPID"]),
        {"anomalies": ["AN-WSP-APPID"], "ctype": "application/vnd.wap.sic", "wbxml": True}, family="malformed")
wsp_scn("anomaly-wbxml-ver", "Anomaly AN-WBXML-VER: unknown version", wsp(ctsic, app_id(4), bytes([0x09]) + SI_WBXML[1:]),
        SISL("SI", "Notice", "Hold", "Web link push", ["AN-WBXML-VER"]),
        {"anomalies": ["AN-WBXML-VER"], "ctype": "application/vnd.wap.sic", "wbxml": True}, family="malformed")
wsp_scn("anomaly-wbxml-str", "Anomaly AN-WBXML-STR: string table too long",
        wsp(ctsic, app_id(4), SI_WBXML[:3] + bytes([0x50]) + SI_WBXML[4:]),
        SISL("SI", "Notice", "Hold", "Web link push", ["AN-WBXML-STR"]),
        {"anomalies": ["AN-WBXML-STR"], "ctype": "application/vnd.wap.sic", "wbxml": True}, family="malformed")
wsp_scn("anomaly-wbxml-tok", "Anomaly AN-WBXML-TOK: bad token", wsp(ctsic, app_id(4), H("02056A00") + H("3F01")),
        SISL("SI", "Notice", "Hold", "Web link push", ["AN-WBXML-TOK"]),
        {"anomalies": ["AN-WBXML-TOK"], "ctype": "application/vnd.wap.sic", "wbxml": True}, family="malformed")

wsp_scn("wap-sic-si", "WAP push: SI (binary)", wsp(ctsic, app_id(4), SI_WBXML),
        SISL("SI", "Notice", "Hold", "Web link push"), {"ctype": "application/vnd.wap.sic", "wbxml": True},
        spec="OMA WAP-167 (Service Indication)")
wsp_scn("wap-sic-delete", "WAP push: SI with delete action", wsp(ctsic, app_id(4), SI_DELETE),
        SISL("SI-DELETE", "Notice", "Hold", "Link deletion push"), {"ctype": "application/vnd.wap.sic", "wbxml": True},
        spec="OMA WAP-167 (Service Indication)")
wsp_scn("wap-si-text", "WAP push: SI (text)", wsp(ct_short(0x2D), app_id(4), SI_TEXT),
        SISL("SI", "Notice", "Hold", "Web link push"), {"ctype": "text/vnd.wap.si"}, spec="OMA WAP-167")
wsp_scn("wap-slc-autoopen", "WAP push: SL (binary), opens link", wsp(ct_short(0x30), app_id(4), SL_WBXML),
        SISL("SL", "High", "Block", "Auto-open link push"), {"ctype": "application/vnd.wap.slc", "wbxml": True},
        spec="OMA WAP-168 (Service Loading)")
wsp_scn("wap-sl-text", "WAP push: SL (text)", wsp(ct_short(0x2F), app_id(4), SL_TEXT),
        SISL("SL", "High", "Block", "Auto-open link push"), {"ctype": "text/vnd.wap.sl"}, spec="OMA WAP-168")


def cp_ct(sec, mac=False):
    params = bytes([0x91, 0x80 + sec]) + ((bytes([0x92]) + b"0" * 40 + b"\0") if mac else b"")
    return ct_params(0x36, params)


for sub, sec, secname, mac in (("NETWPIN", 0, "network PIN", False), ("USERPIN", 1, "user PIN", False),
                               ("USERNETWPIN", 2, "user and network PIN", False), ("USERPINMAC", 3, "user PIN with MAC", True)):
    wsp_scn("wap-cp-%s" % sub.lower(), "WAP push: settings, %s" % sub, wsp(cp_ct(sec, mac), app_id(10), CP_WBXML),
            ex("CL-WAP-CP", sub, "High" if sub == "USERPIN" else "Suspicious", "Block",
               "Settings push (APN or proxy), " + secname),
            {"ctype": "application/vnd.wap.connectivity-wbxml", "wbxml": True, "cp_sec": sub},
            spec="OMA-WAP-TS-ProvCont (SEC parameter)", note=pat_note("PAT-04") if sub == "USERPIN" else None)
wsp_scn("wap-cp-nosec", "WAP push: settings, no security", wsp(ct_short(0x36), app_id(10), CP_WBXML),
        ex("CL-WAP-CP", "NOSEC", "High", "Block", "Settings push (APN or proxy), no security"),
        {"ctype": "application/vnd.wap.connectivity-wbxml", "wbxml": True, "cp_sec": "NOSEC"}, spec="OMA-WAP-TS-ProvCont")
wsp_scn("wap-dm-notify", "WAP push: device management notification", wsp(ct_short(0x44), app_id(7), dm_notification()),
        ex("CL-WAP-DM", "NOTIFY", "Suspicious", "Block", "Device management request"),
        {"ctype": "application/vnd.syncml.notification"}, spec="OMA DM 1.2 Notification Initiated Session", checkSpec=True)
wsp_scn("wap-dm-bootstrap", "WAP push: device management setup", wsp(ct_short(0x42), app_id(7), DM_BOOT),
        ex("CL-WAP-DM", "BOOTSTRAP", "Suspicious", "Block", "Device management setup"),
        {"ctype": "application/vnd.syncml.dm+wbxml", "wbxml": True}, spec="OMA DM 1.2 Bootstrap", checkSpec=True,
        note="The WBXML body is minimal. CL-04 checks it.")
wsp_scn("wap-ds-notify", "WAP push: data sync notification",
        wsp(ct_text("application/vnd.syncml.ds.notification"), app_id(7), ds_notification()),
        ex("CL-WAP-DS", None, "Notice", "Hold", "Data sync request"),
        {"ctype": "application/vnd.syncml.ds.notification"}, spec="OMA DS 1.2 Notification", checkSpec=True,
        note="The body is dummy bytes.")
for sub, nt, sev, t in (("NOTIFY", 1, "High", "Location request"), ("VERIFY", 2, "High", "Location request (asks you)"),
                        ("SILENT", 0, "High", "Silent location request")):
    wsp_scn("wap-supl-%s" % sub.lower(), "WAP push: SUPL INIT, %s" % sub, wsp(ct_long(0x312), app_id(12), supl_init(nt)),
            ex("CL-WAP-SUPL", sub, sev, "Block", t), {"ctype": "application/vnd.omaloc-supl-init", "supl": nt},
            spec="OMA SUPL 2.0 ULP (SUPL INIT notification type)", checkSpec=True,
            note="The body is a SUPL INIT in X.691 UPER (OMA ULP), the layout of the payload and decoder reader. CL-04 checks it."
            + ("" if sub == "SILENT" else " " + pat_note("PAT-03")))
for sub, body, mt, t in (("NOTIFY", MMS_NOTIFY, 0x82, "MMS notification"), ("DELIVERY-REPORT", MMS_DELIVERY, 0x86, "MMS delivery report"),
                         ("READ-REPORT", MMS_READ, 0x88, "MMS read report")):
    wsp_scn("wap-mms-%s" % sub.lower(), "WAP push: %s" % t, wsp(ct_short(0x3E), app_id(2), body),
            ex("CL-WAP-MMS", sub, "Info", "Allow", t), {"ctype": "application/vnd.wap.mms-message", "mms": mt},
            spec="OMA-TS-MMS_ENC (PDU type)")
wsp_scn("wap-coc", "WAP push: cache control (binary)", wsp(ct_short(0x32), app_id(4), CO_WBXML),
        ex("CL-WAP-CO", None, "Notice", "Hold", "Cache control push"), {"ctype": "application/vnd.wap.coc", "wbxml": True},
        spec="WAP-175 (Cache Operation)")
wsp_scn("wap-co-text", "WAP push: cache control (text)", wsp(ct_short(0x31), app_id(4), CO_TEXT),
        ex("CL-WAP-CO", None, "Notice", "Hold", "Cache control push"), {"ctype": "text/vnd.wap.co"}, spec="WAP-175")
wsp_scn("wap-emn", "WAP push: email notification", wsp(ct_long(0x30A), app_id(4), EMN_WBXML),
        ex("CL-WAP-EMN", None, "Info", "Allow", "Email notification"), {"ctype": "application/vnd.wap.emn+wbxml", "wbxml": True},
        spec="OMA Email Notification 1.0", checkSpec=True)
wsp_scn("wap-drm-rights-wbxml", "WAP push: DRM licence (binary)", wsp(ct_short(0x4B), app_id(4), DRM_WBXML),
        ex("CL-WAP-DRM", None, "Notice", "Allow", "DRM licence"),
        {"ctype": "application/vnd.oma.drm.rights+wbxml", "wbxml": True}, spec="OMA DRM 1.0 Rights Expression", checkSpec=True)
wsp_scn("wap-drm-rights-xml", "WAP push: DRM licence (text)", wsp(ct_short(0x4A), app_id(4), DRM_XML),
        ex("CL-WAP-DRM", None, "Notice", "Allow", "DRM licence"), {"ctype": "application/vnd.oma.drm.rights+xml"},
        spec="OMA DRM 1.0 Rights Expression", checkSpec=True)
wsp_scn("wap-vcard", "WAP push: contact card", wsp(ct_short(0x07), app_id(4), VCARD),
        ex("CL-WAP-VCARD", "VCARD", "Notice", "Hold", "Contact card push"), {"ctype": "text/x-vcard"},
        spec="WAP-230 content type 0x07")
wsp_scn("wap-vcal", "WAP push: calendar entry", wsp(ct_short(0x06), app_id(4), VCAL),
        ex("CL-WAP-VCARD", "VCAL", "Notice", "Hold", "Calendar push"), {"ctype": "text/x-vcalendar"},
        spec="WAP-230 content type 0x06")
wsp_scn("wap-imps", "WAP push: chat service wake-up", wsp(ct_short(0x46), app_id(4), IMPS),
        ex("CL-WAP-IMPS", None, "Notice", "Hold", "Chat service wake-up"), {"ctype": "application/vnd.wv.csp.cir"},
        spec="OMA IMPS CIR", checkSpec=True, note="The body is dummy bytes.")
wsp_scn("wap-wml", "WAP push: WML (text)", wsp(ct_short(0x08), app_id(4), WML_TEXT),
        ex("CL-WAP-OTHER", "text/vnd.wap.wml", "Suspicious", "Hold", "Push message (text/vnd.wap.wml)"),
        {"ctype": "text/vnd.wap.wml"}, spec="WAP-191 (WML)")
wsp_scn("wap-wmlc", "WAP push: WML (binary)", wsp(ct_short(0x14), app_id(4), WMLC),
        ex("CL-WAP-OTHER", "application/vnd.wap.wmlc", "Suspicious", "Hold", "Push message (application/vnd.wap.wmlc)"),
        {"ctype": "application/vnd.wap.wmlc", "wbxml": True}, spec="WAP-191 (WML)")
wsp_scn("wap-other-png", "WAP push: PNG image", wsp(ct_short(0x20), app_id(4), PNG),
        ex("CL-WAP-OTHER", "image/png", "Suspicious", "Hold", "Push message (image/png)"), {"ctype": "image/png"},
        spec="WAP-230 content type 0x20")
# ---- SIM OTA -----------------------------------------------------------------------------------
SPI_CC = bytes([0x02, 0x00])
SPI_NONE = bytes([0x00, 0x00])
CC8 = bytes(8)
OTA_SPEC = "3GPP TS 31.115, ETSI TS 102 225, ETSI TS 102 226 (TAR)"


def ota_pdu(tar, spi, cc, pid=0x7F, iei=0x70, cp=None):
    cp = cp if cp is not None else ota_packet(tar, spi, cc)
    n, u = ud_bytes(cp, ie(iei, []))
    return deliver(pid=pid, dcs=0xF6, udl=n, ud=u, udhi=True)


def SIMDD(sub, sev, text, an=None):
    return ex("CL-SIM-DD", sub, floor(sev, an), "Block", "SIM update from the network: " + text, anomalies=an)


ota_items = (
    ("rfm", "SIM-OTA-RFM", H("B00000"), SPI_CC, CC8, "Suspicious", "changes files on the SIM"),
    ("ram", "SIM-OTA-RAM", H("000000"), SPI_CC, CC8, "Suspicious", "installs or deletes SIM apps"),
    # TAR 'PSH' 50 53 48 is the S@T low priority push (SIMalliance S@T 01.23 section 7, the Simjacker TAR).
    # TAR 00 00 01 is in the WIB list of SRLabs SIMtester; the WIB TARs are not in a public specification.
    # B0 00 10 and B0 00 01 (the placeholders) are RFM TARs (ETSI TS 101 220 Annex D).
    ("sat", "SIM-OTA-SAT", H("505348"), SPI_NONE, b"", "High", "sends a command to the SIM browser (S@T)"),
    ("wib", "SIM-OTA-WIB", H("000001"), SPI_NONE, b"", "High", "sends a command to the SIM browser (WIB)"),
    ("nosec", "SIM-OTA-NOSEC", H("445566"), SPI_NONE, b"", "High", "has no security protection"),
    ("other", "SIM-OTA-OTHER", H("445566"), SPI_CC, CC8, "Suspicious", "sends data to a SIM app"),
)
for key, sub, tar, spi, cc, sev, text in ota_items:
    expect = SIMDD(sub, sev, text)
    if key in ("sat", "wib"):
        expect["extraSubtypes"] = ["SIM-OTA-NOSEC"]  # The browser TAR wins, NOSEC is the extra
    pdu_scenario("sim-ota-%s" % key, "sim-ota", "SIM OTA: %s" % sub, 2, ota_pdu(tar, spi, cc), expect, OTA_SPEC,
                 {"mti": 0, "pid": 0x7F, "dcs": 0xF6, "udhi": True, "iei": [0x70], "anomalies": [],
                  "ota": {"tar": tar.hex().upper(), "spi": spi.hex().upper()}}, check_spec=True,
                 note="Dummy counter and dummy secured data. No real keys. CL-08 checked the TAR value."
                 + (" S@T and WIB win over NOSEC and RFM, because they are the attack paths." if key in ("sat", "wib") else ""))
pdu_scenario("sim-ota-class2-iei70", "sim-ota", "SIM OTA in a class 2 message, PID 0", 2,
             ota_pdu(H("B00000"), SPI_CC, CC8, pid=0x00), SIMDD("SIM-OTA-RFM", "Suspicious", "changes files on the SIM"),
             OTA_SPEC, {"mti": 0, "pid": 0x00, "dcs": 0xF6, "udhi": True, "iei": [0x70], "anomalies": [],
                        "ota": {"tar": "B00000", "spi": SPI_CC.hex().upper()}}, check_spec=True)
full = ota_packet(H("B00000"), SPI_CC, CC8)
pdu_scenario("anomaly-ota-hdr", "malformed", "Anomaly AN-OTA-HDR: SIM command header is wrong", 2,
             ota_pdu(None, None, None, cp=full[:8]),
             SIMDD("SIM-OTA-OTHER", "Suspicious", "sends data to a SIM app", ["AN-OTA-HDR"]), OTA_SPEC,
             {"mti": 0, "pid": 0x7F, "dcs": 0xF6, "udhi": True, "iei": [0x70], "anomalies": ["AN-OTA-HDR"]})

# ---- cell broadcast ----------------------------------------------------------------------------
CBS = "3GPP TS 23.041 9.4.1.2"


def cb(id, mid, sub, title, sev="Info", act="Allow", cls="CL-CB", check=False, note=None):
    page = cb_page(0x1000 + (mid & 0xFF), mid, 0x0F, 0x11, "Test message 1")
    add(id, "cb", title, 2, ex(cls, sub, sev, act, title), CBS,
        intent={"cb": {"mid": mid, "dcs": 0x0F, "page": 0x11, "text": "Test message 1"}}, check_spec=check,
        method="cb-pdu", pdu=hx(page),
        note=note or "A cell broadcast page (88 bytes), not an SMS-DELIVER. Inject it as a GSM CB page.")


for sub, mid, t in (("PRESIDENTIAL", 4370, "presidential"), ("EXTREME", 4371, "extreme"), ("SEVERE", 4373, "severe"),
                    ("AMBER", 4379, "amber"), ("TEST", 4380, "test"), ("EXERCISE", 4381, "exercise")):
    cb("cb-cmas-%s" % sub.lower(), mid, sub, "Emergency alert: %s" % t)
for sub, mid, t in (("ETWS-EQ", 4352, "Earthquake warning"), ("ETWS-TSUNAMI", 4353, "Tsunami warning"),
                    ("ETWS-BOTH", 4354, "Earthquake and tsunami warning"), ("ETWS-TEST", 4355, "Test warning")):
    cb("cb-%s" % sub.lower(), mid, sub, t, check=(sub == "ETWS-BOTH"))
cb("cb-area-info", 50, "AREA-INFO", "Area information (channel 50)")
cb("cb-channel-221", 221, "CHANNEL", "Cell broadcast (channel 221)", sev="Notice")
cb("cb-sim-download", 0x1000, None, "Cell broadcast for the SIM", sev="Suspicious", act="Block", cls="CL-CB-SIM", check=True,
   note="The message identifier range for CB data download to the SIM is a guess. CL-09 checks it. "
        "A cell broadcast page (88 bytes), not an SMS-DELIVER.")

# ---- messages from apps, the SIM and the phone ----------------------------------------------------------
add("injected-by-app", "injected", "Message added by an app", 2,
    ex("CL-INJECTED", "com.example.sender", "High", "Hold", "Message added by an app (Example Sender)"),
    "Android Telephony provider", method="inject-provider", text=txt,
    note="An app writes a row into the SMS inbox. The tester needs root or the default SMS role. No received PDU exists.")
add("mo-sim-send", "mo", "The SIM sent a message", 2,
    ex("CL-MO-STK", "SEND-SMS", "High", "Notify", "The SIM sent a message"), "3GPP TS 31.111 (SEND SHORT MESSAGE)",
    method="stk-send", note="Needs a SIM applet that sends an SMS. Without an applet, test this class with a JVM test.")
add("mo-app-text", "mo", "Message sent by an app", 1,
    ex("CL-MO-VISIBLE", "com.example.sender", "Suspicious", "Allow", "Message sent by an app (Example Sender)"),
    "Android SmsManager", method="app-send", text=txt,
    note="The tester sends this message without the messaging app. The target number is another phone.")
add("mo-app-data", "mo", "Data message sent by an app", 1,
    ex("CL-MO-DATA", "16001", "Suspicious", "Allow", "Data message sent"),
    "Android SmsManager", method="app-send", port=16001, payload="01020304", note="Outgoing data message to port 16001.")
for cls, key, t, sev in (("CL-VVM", "vvm", "Voicemail app control", "Info"),
                         ("CL-MISSED-CALL", "missed-call", "Missed-call notice", "Info"),
                         ("CL-CARRIER", "carrier", "Carrier app message", "Info"),
                         ("CL-RCS-PROV", "rcs-prov", "Chat (RCS) setup", "Info"),
                         ("CL-APP-TOKEN", "app-token", "App-only message", "Notice"),
                         ("CL-OEM", "oem", "Handled by the phone maker", "Notice")):
    add("consumer-%s" % key, "consumer", t, 2, ex(cls, None, sev, "Allow", t), "Existing classes",
        method="consumer-observed",
        note="The phone software that uses the message decides this class. No single PDU gives it. "
             "Severity, action and title are the values that exist today. CL-06 checks them.")


# ---- patterns ----------------------------------------------------------------------------------
def pattern(id, title, seq, cls, sub, sev, act, t, reason, pre=None):
    extra = {"precondition": pre} if pre else {}
    add(id, "pattern", title, 2, ex(cls, sub, sev, act, t, reason=reason), "Plan section 8.6",
        sequence=[{"ref": r, "delaySeconds": d} for r, d in seq], **extra,
        note="Send the sequence in order. Wait delaySeconds before each message. The expected result is for the last message.")


pattern("pattern-silent-burst", "Pattern: silent SMS burst",
        [("silent-type0", 0), ("silent-type0", 300), ("silent-type0", 300)],
        "CL-SILENT", "TYPE0", "High", "Notify", "Silent SMS (Type 0)", "Repeated silent SMS can show where a phone is.")
pattern("pattern-silent-unknown-sender", "Pattern: silent SMS from an unknown sender", [("silent-type0", 0)],
        "CL-SILENT", "TYPE0", "Suspicious", "Notify", "Silent SMS (Type 0)", "The sender is not in Known senders.",
        pre="senderNotInKnownSenders")
pattern("pattern-supl-unknown-sender", "Pattern: location request from an unknown sender", [("wap-supl-notify", 0)],
        "CL-WAP-SUPL", "NOTIFY", "High", "Block", "Location request", "The sender is not a known carrier number.",
        pre="senderNotKnownCarrier")
pattern("pattern-cp-nosec-unknown-sender", "Pattern: settings push without PIN (NOSEC)", [("wap-cp-nosec", 0)],
        "CL-WAP-CP", "NOSEC", "High", "Block", "Settings push (APN or proxy), no security",
        "A settings push without a network PIN came from an unknown sender.", pre="senderNotInKnownSenders")
pattern("pattern-cp-userpin-unknown-sender", "Pattern: settings push with user PIN only", [("wap-cp-userpin", 0)],
        "CL-WAP-CP", "USERPIN", "High", "Block", "Settings push (APN or proxy), user PIN",
        "A settings push without a network PIN came from an unknown sender.", pre="senderNotInKnownSenders")
pattern("pattern-sim-browser-sat", "Pattern: SIM browser command (S@T)", [("sim-ota-sat", 0)],
        "CL-SIM-DD", "SIM-OTA-SAT", "High", "Block", "SIM update from the network: sends a command to the SIM browser (S@T)",
        "A message sent a command to the S@T browser on the SIM.")
pattern("pattern-sim-browser-wib", "Pattern: SIM browser command (WIB)", [("sim-ota-wib", 0)],
        "CL-SIM-DD", "SIM-OTA-WIB", "High", "Block", "SIM update from the network: sends a command to the SIM browser (WIB)",
        "A message sent a command to the WIB browser on the SIM.")
pattern("pattern-sim-sends-after-update", "Pattern: the SIM sends a message after a SIM update",
        [("sim-ota-rfm", 0), ("mo-sim-send", 120)], "CL-MO-STK", "SEND-SMS", "High", "Notify", "The SIM sent a message",
        "The SIM sent a message after it received an update. This is the Simjacker pattern.")
pattern("pattern-unknown-port-repeat", "Pattern: repeated messages to an unknown port",
        [("data-port-16001", 0), ("data-port-16001", 3600), ("data-port-16001", 3600)],
        "CL-DATA-PORT", "UNKNOWN", "Suspicious", "Notify", "Data message to port 16001",
        "The same sender sent 3 messages to an unknown port in 24 hours.")
pattern("pattern-malformed-burst", "Pattern: malformed burst",
        [("anomaly-addr-trunc", 0), ("anomaly-addr-trunc", 120)], "CL-MALFORMED-SMS", "AN-ADDR-TRUNC", "High", "Block",
        "Malformed SMS: address cut off", "The same phone received 2 malformed messages in 10 minutes.")
pattern("pattern-replace-no-first", "Pattern: replacement without a first message", [("replace-type1", 0)],
        "CL-REPLACE", "TYPE1", "Suspicious", "Hold", "Replacement message (type 1)",
        "No earlier message of this type came from this sender.", pre="noEarlierMessageOfType")

out = {"version": 1, "anomalyNames": ANOMALY_NAMES,
       "anomalyPolicy": {"hard": HARD, "wspHard": WSP_HARD, "soft": SOFT}, "scenarios": SCEN}
with open(sys.argv[1], "w", encoding="utf-8", newline="\n") as f:
    json.dump(out, f, indent=2, ensure_ascii=True)
    f.write("\n")
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
    json.dump(INTENT, f, indent=1)
print("scenarios:", len(SCEN), "intents:", len(INTENT))
