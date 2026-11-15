"""Round-trip check for scenarios.json. Independent parser, shares no code with gen_lib.
Usage: python -I check_scenarios.py <scenarios.json> <intent.json>"""
import json
import re
import sys

ERR = []
# Expected action names. Notify shows the message and warns, as Allow with a notice.
ACTIONS = ("Allow", "Notify", "Hold", "Block")


def err(sid, msg):
    ERR.append("%s: %s" % (sid, msg))


GSM = ("@£$¥èéùìòÇ\nØø\rÅå"
       "Δ_ΦΓΛΩΠΨΣΘΞ\x1bÆæßÉ"
       " !\"#¤%&'()*+,-./0123456789:;<=>?"
       "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§"
       "¿abcdefghijklmnopqrstuvwxyzäöñüà")

# WINA WSP content types (own copy of the table, used for the check only)
CT = {0x06: "text/x-vcalendar", 0x07: "text/x-vcard", 0x08: "text/vnd.wap.wml", 0x14: "application/vnd.wap.wmlc",
      0x20: "image/png", 0x2D: "text/vnd.wap.si", 0x2E: "application/vnd.wap.sic", 0x2F: "text/vnd.wap.sl",
      0x30: "application/vnd.wap.slc", 0x31: "text/vnd.wap.co", 0x32: "application/vnd.wap.coc",
      0x36: "application/vnd.wap.connectivity-wbxml", 0x3E: "application/vnd.wap.mms-message",
      0x42: "application/vnd.syncml.dm+wbxml", 0x44: "application/vnd.syncml.notification",
      0x46: "application/vnd.wv.csp.cir", 0x4A: "application/vnd.oma.drm.rights+xml",
      0x4B: "application/vnd.oma.drm.rights+wbxml", 0x30A: "application/vnd.wap.emn+wbxml",
      0x312: "application/vnd.omaloc-supl-init"}
WBXML_DTD_TAGS = {"application/vnd.wap.sic": {5, 6, 7, 8}, "application/vnd.wap.slc": {5},
                  "application/vnd.wap.coc": {5, 6, 7}, "application/vnd.wap.connectivity-wbxml": {5, 6, 7}}
VALID_IEI = set(range(0x00, 0x1B)) | {0x20, 0x21, 0x22, 0x23, 0x24, 0x25} | set(range(0x70, 0xA0)) | set(range(0xC0, 0xE0))
SEC = {0x80: "NETWPIN", 0x81: "USERPIN", 0x82: "USERNETWPIN", 0x83: "USERPINMAC"}


class Stop(Exception):
    pass


def unbcd(b):
    out = ""
    for x in b:
        out += "%x%x" % (x & 15, x >> 4)
    return out


def parse_addr(d, p, an):
    if p + 2 > len(d):
        an.append("AN-ADDR-TRUNC")
        raise Stop
    n = d[p]
    toa = d[p + 1]
    octets = (n + 1) // 2
    if n > 20:
        an.append("AN-ADDR-LEN")
        raise Stop
    if p + 2 + octets > len(d):
        an.append("AN-ADDR-TRUNC")
        raise Stop
    if (toa & 0x80) == 0 or ((toa >> 4) & 7) == 7:
        an.append("AN-ADDR-TOA")
    return n, toa, p + 2 + octets


def uintvar(d, p):
    v = 0
    while True:
        if p >= len(d):
            raise ValueError
        b = d[p]
        p += 1
        v = (v << 7) | (b & 0x7F)
        if not b & 0x80:
            return v, p


def parse_wbxml(body, ctype, an):
    """Walk a WBXML document. Appends AN-WBXML-* codes."""
    if len(body) < 4:
        an.append("AN-WBXML-TOK")
        return
    if body[0] > 3:
        an.append("AN-WBXML-VER")
        return
    p = 1
    pid = body[p]
    p += 1
    if pid == 0:
        p += 1
    p += 1  # charset
    stl = body[p]
    p += 1
    if p + stl > len(body):
        an.append("AN-WBXML-STR")
        return
    p += stl
    tags = WBXML_DTD_TAGS.get(ctype)
    stack = 0
    state = "content"
    seen_root = False
    while p < len(body):
        b = body[p]
        p += 1
        if b == 0:
            p += 1
        elif b == 1:
            if state == "attr":
                state = "content"
                continue
            if stack == 0:
                an.append("AN-WBXML-TOK")
                return
            stack -= 1
        elif b == 2:
            _, p = uintvar(body, p)
        elif b == 3 or b in (0x40, 0x41, 0x42):
            e = body.index(b"\0", p)
            p = e + 1
        elif b in (0x80, 0x81, 0x82, 0x83):
            _, p = uintvar(body, p)
        elif b == 0xC3:
            ln, p = uintvar(body, p)
            p += ln
        elif b in (4, 0x44, 0x84, 0xC4):
            _, p = uintvar(body, p)
        elif b in (0xC0, 0xC1, 0xC2, 0x43):
            if b == 0x43:
                p = body.index(b"\0\x01", p) + 2  # not used by scenarios
        elif state == "attr":
            pass  # attribute start (<0x80) or value token (>=0x80): one byte
        else:
            tag = b & 0x3F
            if tags is not None and tag not in tags:
                an.append("AN-WBXML-TOK")
                return
            seen_root = True
            if b & 0x80:
                state = "attr"
            if b & 0x40:
                stack += 1
            elif b & 0x80:
                pass
    if state == "attr" or stack != 0:
        an.append("AN-WBXML-TOK")


def parse_wsp(d, an):
    """Returns dict of WSP facts. Hard errors append codes and return what is known."""
    f = {}
    if len(d) < 3:
        an.append("AN-WSP-HLEN")
        return f
    if d[1] not in (6, 7):
        an.append("AN-WSP-TYPE")
        return f
    try:
        hl, p = uintvar(d, 2)
    except ValueError:
        an.append("AN-WSP-HLEN")
        return f
    if p + hl > len(d) or hl < 1:
        an.append("AN-WSP-HLEN")
        return f
    hdr = d[p:p + hl]
    body = d[p + hl:]
    b = hdr[0]
    q = 1
    params = b""
    ct = None
    if b >= 0x80:
        ct = CT.get(b & 0x7F, "unknown-%02x" % (b & 0x7F))
    elif b <= 0x1F:
        if b == 0x1F:
            try:
                ln, q = uintvar(hdr, 1)
            except ValueError:
                an.append("AN-WSP-CTYPE")
                return f
        else:
            ln, q = b, 1
        if q + ln > hl:
            an.append("AN-WSP-CTYPE")
            return f
        v = hdr[q:q + ln]
        q = q + ln
        if v[0] >= 0x80:
            ct = CT.get(v[0] & 0x7F, "unknown")
            params = v[1:]
        elif v[0] <= 0x1E:
            n = v[0]
            ct = CT.get(int.from_bytes(v[1:1 + n], "big"), "unknown-long")
            params = v[1 + n:]
        else:
            e = v.find(b"\0")
            ct = v[:e].decode()
            params = v[e + 1:]
    elif b >= 0x20:
        e = hdr.find(b"\0")
        if e < 0:
            an.append("AN-WSP-CTYPE")
            return f
        ct = hdr[:e].decode()
        q = e + 1
    else:
        an.append("AN-WSP-CTYPE")
        return f
    f["ctype"] = ct
    if ct == "application/vnd.wap.connectivity-wbxml":
        f["cp_sec"] = "NOSEC"
    # parameters: SEC 0x91 + value, MAC 0x92 + text
    i = 0
    while i < len(params):
        if params[i] == 0x91:
            f["cp_sec"] = SEC.get(params[i + 1], "?")
            i += 2
        elif params[i] == 0x92:
            i = params.index(b"\0", i) + 1
        else:
            break
    # headers after content type
    rest = hdr[q:]
    if rest[:1] == b"\xaf":
        v = rest[1:]
        ok = False
        if v and v[0] >= 0x80:
            ok = True
        elif v and 0x20 <= v[0] < 0x7F and b"\0" in v:
            ok = True
        elif v and v[0] <= 0x1E and 1 + v[0] <= len(v):
            ok = True
        if not ok:
            an.append("AN-WSP-APPID")
    f["body"] = body
    if ct == "application/vnd.wap.mms-message":
        f["mms"] = body[1] if len(body) > 1 and body[0] == 0x8C else None
    if ct == "application/vnd.omaloc-supl-init":
        f["supl"] = supl_type(body)
    if ct in WBXML_DTD_TAGS or ct in ("application/vnd.syncml.dm+wbxml", "application/vnd.wap.emn+wbxml",
                                      "application/vnd.oma.drm.rights+wbxml", "application/vnd.wap.wmlc"):
        f["wbxml"] = True
        parse_wbxml(body, ct, an)
    return f


def supl_type(body):
    """NotificationType of a SUPL INIT ULP-PDU (ITU-T X.691 UPER, OMA-TS-ULP), read independently of the generator.
    0..4 notification type, 8 no notification element, -1 unreadable or not SUPL INIT."""
    bits = "".join(format(x, "08b") for x in body)
    pos = [0]

    def get(n):
        if pos[0] + n > len(bits):
            raise ValueError("short")
        v = int(bits[pos[0]:pos[0] + n], 2) if n else 0
        pos[0] += n
        return v

    def open_type():
        if get(1):
            return False                        # index not normally small: unsupported
        get(6)
        if get(1) == 0:
            length = get(7)
        elif get(1) == 0:
            length = get(14)
        else:
            return False
        get(8 * length)
        return True

    def ip_address():
        get(32 if get(1) == 0 else 128)

    try:
        get(16)                                 # length
        get(24)                                 # version
        has_set, has_slp = get(1), get(1)
        if has_set:
            get(16)                             # SetSessionID.sessionId
            if get(1):
                if not open_type():
                    return -1
            else:
                idx = get(3)
                if idx in (0, 1, 3):
                    get(64)
                elif idx == 2:
                    get(34)
                elif idx == 4:
                    get(7 * (get(10) + 1))
                elif idx == 5:
                    ip_address()
                else:
                    return -1
        if has_slp:
            get(32)                             # SlpSessionID.sessionID
            if get(1):
                if not open_type():
                    return -1
            elif get(1) == 0:
                ip_address()
            else:
                get(6 * (get(8) + 1))           # FQDN
        if get(1) or get(3) != 0:               # UlpMessage must be msSUPLINIT
            return -1
        get(1)                                  # SUPLINIT extension bit
        has_notification = get(1)
        get(4)                                  # sLPAddress, qoP, mAC, keyIdentity
        if get(1):                              # PosMethod extension
            if get(1):
                return -1
            get(6)
        elif get(4) > 9:
            return -1
        if not has_notification:
            return 8
        get(1); get(5)                          # Notification extension bit and presence bits
        if get(1):
            return -1
        value = get(3)
        return value if value <= 4 else -1
    except ValueError:
        return -1


def parse_deliver(hexs):
    d = bytes.fromhex(hexs)
    an = []
    f = {"anomalies": an}
    try:
        p = 0
        if len(d) < 1:
            an.append("AN-PDU-SHORT")
            raise Stop
        sl = d[0]
        p = 1 + sl
        if p >= len(d):
            an.append("AN-PDU-SHORT")
            raise Stop
        fo = d[p]
        p += 1
        mti = fo & 3
        f["mti"] = mti
        f["udhi"] = bool(fo & 0x40)
        if mti == 3:
            an.append("AN-MTI")
            raise Stop
        if mti == 2:
            p += 1  # MR
            _, _, p = parse_addr(d, p, an)
            p += 14
            f["st"] = d[p]
            return f
        n, toa, p = parse_addr(d, p, an)
        f["toa"] = toa
        if len(d) - p < 10:
            an.append("AN-PDU-SHORT")
            raise Stop
        pid, dcs = d[p], d[p + 1]
        f["pid"], f["dcs"] = pid, dcs
        p += 9  # pid dcs scts(7)
        udl = d[p]
        p += 1
        data = d[p:]
        f["udl"] = udl
        if 0x48 <= pid <= 0x5E or 0x60 <= pid <= 0x7B or 0x80 <= pid <= 0xBF:
            an.append("AN-PID-RSV")
        grp = dcs >> 4
        alpha = "gsm7"
        if grp <= 7:
            a = (dcs >> 2) & 3
            if a == 3:
                an.append("AN-DCS-RSV")
            alpha = ["gsm7", "8bit", "ucs2", "rsv"][a]
            f["compressed"] = bool(dcs & 0x20)
            f["cls"] = dcs & 3 if dcs & 0x10 else None
        elif grp <= 0xB:
            an.append("AN-DCS-RSV")
        elif grp in (0xC, 0xD):
            alpha = "gsm7"
            f["mwi"] = (grp, dcs & 8, dcs & 3)
        elif grp == 0xE:
            alpha = "ucs2"
            f["mwi"] = (grp, dcs & 8, dcs & 3)
        else:
            if dcs & 8:
                an.append("AN-DCS-RSV")
            alpha = "8bit" if dcs & 4 else "gsm7"
            f["cls"] = dcs & 3
        f["alpha"] = alpha
        need = (udl * 7 + 7) // 8 if alpha == "gsm7" else udl
        if need > len(data):
            an.append("AN-UDL-LONG")
            raise Stop
        if len(data) > need:
            an.append("AN-UDL-SHORT")
        ud = data[:need]
        hdr_end = 0
        f["iei"] = []
        if f["udhi"]:
            udhl = ud[0] if ud else 0
            if not ud or udhl + 1 > len(ud):
                an.append("AN-UDH-LEN")
                raise Stop
            q = 1
            ports = []
            while q < 1 + udhl:
                if q + 2 > 1 + udhl:
                    an.append("AN-IE-OVERRUN")
                    raise Stop
                iei, iel = ud[q], ud[q + 1]
                if q + 2 + iel > 1 + udhl:
                    an.append("AN-IE-OVERRUN")
                    raise Stop
                v = ud[q + 2:q + 2 + iel]
                f["iei"].append(iei)
                if iei not in VALID_IEI:
                    an.append("AN-IE-RSV")
                if iei == 0x05 and iel == 4:
                    ports.append(int.from_bytes(v[:2], "big"))
                elif iei == 0x04 and iel == 2:
                    ports.append(v[0])
                elif iei in (0x00, 0x08):
                    ref = v[0] if iei == 0 else int.from_bytes(v[:2], "big")
                    tot, seq = v[-2], v[-1]
                    f["concat"] = [ref, tot, seq]
                    if tot == 0 or seq == 0 or seq > tot:
                        an.append("AN-CONCAT-BAD")
                q += 2 + iel
            if len(ports) > 1:
                an.append("AN-IE-DUP")
            if ports:
                # The last port element wins, as in the Android SmsHeader.
                f["port"] = ports[-1]
                if ports[-1] == 0:
                    an.append("AN-PORT-ZERO")
            hdr_end = 1 + udhl
            body = ud[hdr_end:]
            if alpha == "gsm7":
                fill = (7 - (hdr_end * 8) % 7) % 7
                # decode septets from the full stream, skipping header septets
                allbits = int.from_bytes(ud, "little")
                hs = (hdr_end * 8 + fill) // 7
                sept = [(allbits >> (7 * i)) & 0x7F for i in range(hs, udl)]
                f["text"] = "".join(GSM[s] for s in sept)
            elif alpha == "ucs2":
                f["text"] = body.decode("utf-16-be")
        else:
            body = ud
            if alpha == "gsm7":
                allbits = int.from_bytes(ud, "little")
                f["text"] = "".join(GSM[(allbits >> (7 * i)) & 0x7F] for i in range(udl))
            elif alpha == "ucs2":
                f["text"] = body.decode("utf-16-be")
        f["body"] = body
        # SIM OTA command packet
        if 0x70 in f["iei"] or 0x71 in f["iei"]:
            if len(body) < 3:
                an.append("AN-OTA-HDR")
            else:
                cpl = int.from_bytes(body[:2], "big")
                chl = body[2]
                if cpl != len(body) - 2 or chl < 13 or chl > cpl - 1 or 3 + chl > len(body):
                    an.append("AN-OTA-HDR")
                else:
                    f["ota"] = {"spi": body[3:5].hex().upper(), "tar": body[7:10].hex().upper()}
        if f.get("port") == 2948 and alpha == "8bit":
            w = parse_wsp(body, an)
            f["wsp"] = w
            if "ctype" in w:
                f["ctype"] = w["ctype"]
            for k in ("cp_sec", "mms", "supl", "wbxml"):
                if k in w:
                    f[k] = w[k]
    except Stop:
        pass
    return f


def classify(f, pdu_hex):
    """Mini classifier in the order of the app classifier. Returns (class, subtype)."""
    an = set(f["anomalies"])
    hard = {"AN-ADDR-TRUNC", "AN-ADDR-LEN", "AN-PDU-SHORT", "AN-UDL-LONG", "AN-UDH-LEN", "AN-IE-OVERRUN"}
    h = sorted(an & hard)
    if h:
        return "CL-MALFORMED-SMS", h[0]
    # The wrong message type without known damage is CL-UNREADABLE-SMS.
    if "AN-MTI" in an:
        return "CL-UNREADABLE-SMS", "AN-MTI"
    if f["mti"] == 2:
        return "CL-STATUS", {0x00: "DELIVERED", 0x20: "PENDING", 0x40: "FAILED"}[f["st"]]
    pid, dcs = f["pid"], f["dcs"]
    port = f.get("port")
    if pid == 0x40:
        return "CL-SILENT", "TYPE0-PORT" if port is not None else "TYPE0"
    if 0x41 <= pid <= 0x47:
        return "CL-REPLACE", "TYPE%d" % (pid - 0x40)
    if pid == 0x5F:
        return "CL-RETURN-CALL", None
    if pid == 0x7D:
        return "CL-ME-DOWNLOAD", None
    if pid == 0x7E:
        return "CL-ME-DEPERSO", None
    if pid == 0x7F or ("ota" in f or 0x70 in f["iei"]):
        return "CL-SIM-DD", ota_sub(f)
    if 0x20 <= pid <= 0x3F:
        m = {0x21: "TELEX", 0x22: "FAX", 0x23: "FAX", 0x24: "VOICE", 0x25: "ERMES", 0x26: "PAGING", 0x31: "X400", 0x32: "EMAIL"}
        return "CL-TELEMATIC", m.get(pid, "OTHER")
    if f.get("cls") == 2:
        return "CL-SIM-STORED", "CLASS2"
    if f.get("cls") == 3:
        return "CL-CLASS3", None
    if f.get("cls") == 0:
        return "CL-FLASH", "CLASS0-EMPTY" if f["udl"] == 0 else "CLASS0"
    if "mwi" in f:
        g, act, t = f["mwi"]
        if g == 0xC and not act and f["udl"] == 0:
            return "CL-MWI", "DISCARD-SILENT"
        return "CL-MWI", ["VOICEMAIL", "FAX", "EMAIL", "OTHER"][t]
    if f.get("compressed"):
        return "CL-COMPRESSED", None
    if 0x23 in f["iei"]:
        return "CL-MWI", "EXTENDED"
    if 0x01 in f["iei"]:
        return "CL-MWI", "VOICEMAIL"
    if port is not None:
        return port_class(f, port)
    if f["udl"] == 0 and not f["udhi"]:
        return "CL-EMPTY", None
    if f["alpha"] == "8bit":
        return "CL-BINARY", None
    if f.get("concat") and f["concat"][2] == 1 and f["concat"][1] == 2 and f["concat"][0] == 0x2B:
        return "CL-MULTIPART-INCOMPLETE", None
    return "CL-NORMAL", None


def ota_sub(f):
    # The S@T push and WIB data shapes are not checked here: the
    # catalogue has dummy secured data.
    o = f.get("ota")
    if not o:
        return "SIM-OTA-OTHER"
    tar = int(o["tar"], 16)
    spi1 = int(o["spi"], 16) >> 8
    if tar in (0x505348, 0x534054):
        return "SIM-OTA-SAT"
    if 0x000001 <= tar <= 0x000009:
        return "SIM-OTA-WIB"
    if (spi1 & 0x03) < 0x02 and not spi1 & 0x04:
        return "SIM-OTA-NOSEC"
    if 0xB00000 <= tar <= 0xB0FFFF:
        return "SIM-OTA-RFM"
    if tar in (0x000000, 0xB20100):
        return "SIM-OTA-RAM"
    return "SIM-OTA-OTHER"


def port_class(f, port):
    smart = {5505: "RINGTONE", 5506: "OPLOGO", 5507: "CLILOGO", 5514: "PICTURE", 9204: "VCARD", 9205: "VCAL"}
    names = {2949: "WAP-PUSH-SECURE", 9200: "WAP-CONNECTIONLESS-SESSION", 9201: "WAP-SESSION",
             9202: "WAP-CONNECTIONLESS-SESSION-SECURE", 9203: "WAP-SESSION-SECURE", 9206: "WAP-VCARD-VCALENDAR",
             9207: "WAP-VCARD-VCALENDAR",
             # JSR 120 (WMA 1.1) Appendix A restricted ports (PortNames).
             2805: "WAP-WTA-CONNECTIONLESS-SESSION-SECURE", 2923: "WAP-WTA-SESSION-SECURE", 5502: "SERVICE-CARD",
             5503: "INTERNET-ACCESS-CONFIG", 5508: "DYNAMIC-MENU-CONTROL", 5511: "MESSAGE-ACCESS",
             5512: "EMAIL-NOTIFICATION", 49996: "SYNCML-OTA-CONFIG", 49999: "WAP-OTA-CONFIG"}
    an = set(f["anomalies"])
    if port == 0:
        return "CL-DATA-PORT", "PORT0"
    if port == 2948:
        if an & {"AN-WSP-TYPE", "AN-WSP-HLEN", "AN-WSP-CTYPE"}:
            return "CL-WAP-UNREADABLE", sorted(an & {"AN-WSP-TYPE", "AN-WSP-HLEN", "AN-WSP-CTYPE"})[0]
        return wap_class(f)
    if port in smart:
        return "CL-SMART", smart[port]
    if port == 7275:
        return "CL-LOC-SUPL", "SMS"
    if port in names:
        return "CL-DATA-PORT", names[port]
    return "CL-DATA-PORT", "UNKNOWN"


def wap_class(f):
    ct = f["ctype"]
    if ct == "application/vnd.wap.mms-message":
        return "CL-WAP-MMS", {0x82: "NOTIFY", 0x86: "DELIVERY-REPORT", 0x88: "READ-REPORT"}[f["mms"]]
    if ct in ("application/vnd.wap.sic", "text/vnd.wap.si"):
        return "CL-WAP-SISL", "SI-DELETE" if b"\x09\x01" in f["wsp"]["body"] else "SI"
    if ct in ("application/vnd.wap.slc", "text/vnd.wap.sl"):
        return "CL-WAP-SISL", "SL"
    if ct in ("application/vnd.wap.coc", "text/vnd.wap.co"):
        return "CL-WAP-CO", None
    if ct == "application/vnd.wap.connectivity-wbxml":
        return "CL-WAP-CP", f.get("cp_sec", "NOSEC")
    if ct == "application/vnd.syncml.notification":
        return "CL-WAP-DM", "NOTIFY"
    if ct == "application/vnd.syncml.dm+wbxml":
        return "CL-WAP-DM", "BOOTSTRAP"
    if ct == "application/vnd.syncml.ds.notification":
        return "CL-WAP-DS", None
    if ct == "application/vnd.omaloc-supl-init":
        return "CL-WAP-SUPL", {0: "SILENT", 1: "NOTIFY", 2: "VERIFY", 3: "VERIFY", 4: "SILENT", 8: "SILENT"}.get(f["supl"], "UNREADABLE")
    if ct == "application/vnd.wap.emn+wbxml":
        return "CL-WAP-EMN", None
    if ct.startswith("application/vnd.oma.drm.rights"):
        return "CL-WAP-DRM", None
    if ct == "text/x-vcard":
        return "CL-WAP-VCARD", "VCARD"
    if ct == "text/x-vcalendar":
        return "CL-WAP-VCARD", "VCAL"
    if ct == "application/vnd.wv.csp.cir":
        return "CL-WAP-IMPS", None
    return "CL-WAP-OTHER", ct


def cb_parse(hexs):
    d = bytes.fromhex(hexs)
    assert len(d) == 88, len(d)
    mid = int.from_bytes(d[2:4], "big")
    bits = int.from_bytes(d[6:], "little")
    text = "".join(GSM[(bits >> (7 * i)) & 0x7F] for i in range(93)).rstrip("\r")
    return {"mid": mid, "dcs": d[4], "page": d[5], "text": text}


def cb_class(mid):
    if 4370 <= mid <= 4399:
        names = {4370: "PRESIDENTIAL", 4371: "EXTREME", 4373: "SEVERE", 4379: "AMBER", 4380: "TEST", 4381: "EXERCISE"}
        return "CL-CB", names[mid]
    if 4352 <= mid <= 4359:
        return "CL-CB", {4352: "ETWS-EQ", 4353: "ETWS-TSUNAMI", 4354: "ETWS-BOTH", 4355: "ETWS-TEST"}[mid]
    if mid == 50:
        return "CL-CB", "AREA-INFO"
    if mid == 0x1000:
        return "CL-CB-SIM", None
    return "CL-CB", "CHANNEL"


SYNTHETIC_NUMBER = re.compile(r"^\+?1?55501\d*$")
HEX_KEYS = ("pdu", "payload")


def privacy_scan(doc, phone):
    """CONTRIBUTING: no real phone number in a fixture. Every string value outside the hex fields (pdu, payload,
    parts[].pdu) must not hold 7 or more digits, unless it is a synthetic 555-01xx number."""
    def walk(value, path):
        if isinstance(value, dict):
            for k, v in value.items():
                if k not in HEX_KEYS:
                    walk(v, path + "." + k)
        elif isinstance(value, list):
            for i, v in enumerate(value):
                walk(v, "%s[%d]" % (path, i))
        elif isinstance(value, str):
            for m in phone.finditer(value):
                if not SYNTHETIC_NUMBER.match(m.group(0)):
                    err(path, "privacy: %d-digit string that is not a synthetic 555-01xx number" % len(m.group(0)))
    walk(doc, "$")


def main():
    doc = json.load(open(sys.argv[1], encoding="utf-8"))
    intent = json.load(open(sys.argv[2], encoding="utf-8"))
    scen = doc["scenarios"]
    ids = [s["id"] for s in scen]
    if len(ids) != len(set(ids)):
        err("-", "duplicate ids")
    byid = {s["id"]: s for s in scen}
    names = doc["anomalyNames"]
    phone = re.compile(r"\+?\d{7,}")
    for s in scen:
        sid = s["id"]
        for k in ("id", "family", "title", "tier", "expect", "spec"):
            if k not in s:
                err(sid, "missing " + k)
        e = s["expect"]
        for k in ("class", "subtype", "severity", "action", "title"):
            if k not in e:
                err(sid, "expect missing " + k)
        if e["severity"] not in ("Info", "Notice", "Suspicious", "High") or e["action"] not in ACTIONS:
            err(sid, "bad severity or action")
        if not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", sid):
            err(sid, "id is not kebab-case")
        if "sequence" in s:
            for st in s["sequence"]:
                if st["ref"] not in byid:
                    err(sid, "unknown ref " + st["ref"])
            continue
        method = s.get("method")
        if method in ("inject-provider", "stk-send", "consumer-observed", "app-send"):
            continue
        if method == "cb-pdu":
            c = cb_parse(s["pdu"])
            exp = intent[sid]["cb"]
            if c != exp:
                err(sid, "cb mismatch %s vs %s" % (c, exp))
            if cb_class(c["mid"]) != (e["class"], e["subtype"]):
                err(sid, "cb class mismatch")
            continue
        pdus = [(sid, s["pdu"], intent[sid])] if "pdu" in s else []
        if "parts" in s:
            pdus = [("%s#%d" % (sid, i + 1), p["pdu"], intent[sid]["parts"][i]) for i, p in enumerate(s["parts"])]
        if not pdus:
            err(sid, "no pdu")
            continue
        for pid_, hexs, it in pdus:
            f = parse_deliver(hexs)
            for k, v in it.items():
                if k == "anomalies":
                    if sorted(f["anomalies"]) != sorted(v):
                        err(pid_, "anomalies %s != intent %s" % (f["anomalies"], v))
                elif k == "ota":
                    if f.get("ota") != v:
                        err(pid_, "ota %s != %s" % (f.get("ota"), v))
                elif k in ("ctype", "mms", "supl", "cp_sec", "wbxml"):
                    if f.get(k) != v:
                        err(pid_, "%s %s != %s" % (k, f.get(k), v))
                elif k == "iei":
                    if not set(v) <= set(f["iei"]):
                        err(pid_, "iei %s not in %s" % (v, f["iei"]))
                else:
                    if f.get(k) != v:
                        err(pid_, "%s %s != intent %s" % (k, f.get(k), v))
            # tier 1 extras
            if "port" in s and "payload" in s and "port" in f:
                if f["body"].hex().upper() != s["payload"]:
                    err(pid_, "payload differs from pdu body")
                if f.get("port") != s["port"]:
                    err(pid_, "port differs")
            if "text" in s and "parts" not in s and "text" in f and f["text"] != s["text"]:
                err(pid_, "text differs")
            if "parts" in s and f.get("text") != intent[sid]["parts"][int(pid_.split("#")[1]) - 1]["text"]:
                err(pid_, "part text differs")
            if "parts" not in s:
                got = classify(f, hexs)
                if got != (e["class"], e["subtype"]):
                    err(pid_, "mini classifier %s != expect %s" % (got, (e["class"], e["subtype"])))
                # evidence floor and title for malformed
                if f["anomalies"] and e["severity"] in ("Info", "Notice"):
                    err(pid_, "anomaly without severity floor")
                if e["class"] == "CL-UNREADABLE-SMS" and e["title"] != "Unreadable SMS":
                    err(pid_, "title %r != 'Unreadable SMS'" % e["title"])
                if e["class"] in ("CL-MALFORMED-SMS", "CL-WAP-UNREADABLE"):
                    want = ("Malformed SMS: " if e["class"] == "CL-MALFORMED-SMS" else "Malformed WAP push: ") + names[e["subtype"]]
                    if e["title"] != want:
                        err(pid_, "title %r != %r" % (e["title"], want))
                if sorted(e.get("anomalies", [])) != sorted(f["anomalies"]):
                    err(pid_, "expect.anomalies %s != parsed %s" % (e.get("anomalies"), f["anomalies"]))
    # privacy scan: no digit string of 7 or more digits outside the hex fields, except synthetic 555-01xx numbers
    privacy_scan(doc, phone)
    coverage(doc, scen, byid)
    print("checked %d scenarios" % len(scen))
    if ERR:
        print("FAILURES (%d):" % len(ERR))
        for x in ERR:
            print("  " + x)
        sys.exit(1)
    print("ALL PASS")


PLAN_8_3 = ["application/vnd.wap.sic", "application/vnd.wap.slc", "text/vnd.wap.si", "text/vnd.wap.sl",
            "application/vnd.wap.connectivity-wbxml", "application/vnd.syncml.notification",
            "application/vnd.syncml.dm+wbxml", "application/vnd.omaloc-supl-init", "application/vnd.wap.mms-message",
            "text/vnd.wap.wml", "application/vnd.wap.wmlc", "application/vnd.wap.coc", "text/vnd.wap.co"]
ROWS_8_4 = [
    ("CL-SILENT", ["TYPE0", "TYPE0-PORT"]), ("CL-FLASH", ["CLASS0", "CLASS0-EMPTY"]),
    ("CL-REPLACE", ["TYPE%d" % i for i in range(1, 8)]), ("CL-RETURN-CALL", [None]), ("CL-ME-DOWNLOAD", [None]),
    ("CL-ME-DEPERSO", [None]),
    ("CL-SIM-DD", ["SIM-OTA-RFM", "SIM-OTA-RAM", "SIM-OTA-SAT", "SIM-OTA-WIB", "SIM-OTA-NOSEC", "SIM-OTA-OTHER"]),
    ("CL-SIM-STORED", ["CLASS2"]), ("CL-CLASS3", [None]),
    ("CL-TELEMATIC", ["FAX", "TELEX", "PAGING", "VOICE", "ERMES", "X400", "EMAIL", "OTHER"]),
    ("CL-MWI", ["VOICEMAIL", "FAX", "EMAIL", "OTHER", "EXTENDED", "DISCARD-SILENT"]),
    ("CL-STATUS", ["DELIVERED", "PENDING", "FAILED"]), ("CL-EMPTY", [None]), ("CL-BINARY", [None]),
    ("CL-COMPRESSED", [None]), ("CL-MULTIPART-INCOMPLETE", [None]),
    ("CL-DATA-PORT", ["UNKNOWN", "PORT0", "WAP-PUSH-SECURE", "WAP-CONNECTIONLESS-SESSION"]),
    ("CL-SMART", ["RINGTONE", "OPLOGO", "CLILOGO", "PICTURE", "VCARD", "VCAL"]), ("CL-LOC-SUPL", ["SMS"]),
    ("CL-WAP-MMS", ["NOTIFY", "DELIVERY-REPORT", "READ-REPORT"]), ("CL-WAP-SISL", ["SI", "SI-DELETE", "SL"]),
    ("CL-WAP-CO", [None]), ("CL-WAP-CP", ["NETWPIN", "USERPIN", "USERNETWPIN", "USERPINMAC", "NOSEC"]),
    ("CL-WAP-DM", ["NOTIFY", "BOOTSTRAP"]), ("CL-WAP-DS", [None]), ("CL-WAP-SUPL", ["NOTIFY", "VERIFY", "SILENT"]),
    ("CL-WAP-EMN", [None]), ("CL-WAP-DRM", [None]), ("CL-WAP-VCARD", ["VCARD", "VCAL"]), ("CL-WAP-IMPS", [None]),
    ("CL-WAP-OTHER", ["image/png", "text/vnd.wap.wml", "application/vnd.wap.wmlc"]),
    ("CL-CB", ["PRESIDENTIAL", "EXTREME", "SEVERE", "AMBER", "TEST", "EXERCISE", "ETWS-EQ", "ETWS-TSUNAMI", "ETWS-BOTH",
               "ETWS-TEST", "AREA-INFO", "CHANNEL"]), ("CL-CB-SIM", [None]),
    ("CL-INJECTED", ["com.example.sender"]), ("CL-MO-STK", ["SEND-SMS"]), ("CL-MO-VISIBLE", ["com.example.sender"]),
    ("CL-MO-DATA", ["16001"]), ("CL-VVM", [None]), ("CL-MISSED-CALL", [None]), ("CL-CARRIER", [None]),
    ("CL-RCS-PROV", [None]), ("CL-APP-TOKEN", [None]), ("CL-OEM", [None]), ("CL-NORMAL", [None]),
]
PATTERNS = ["pattern-silent-burst", "pattern-silent-unknown-sender", "pattern-supl-unknown-sender",
            "pattern-cp-nosec-unknown-sender", "pattern-sim-browser-sat", "pattern-sim-sends-after-update",
            "pattern-unknown-port-repeat", "pattern-malformed-burst", "pattern-replace-no-first"]


def coverage(doc, scen, byid):
    print("=== coverage ===")
    gaps = []
    an_cov = {}
    for s in scen:
        for a in s["expect"].get("anomalies", []):
            an_cov.setdefault(a, []).append(s["id"])
    for code in doc["anomalyNames"]:
        ok = code in an_cov
        print("8.2 %-16s %s %s" % (code, "OK " if ok else "GAP", ",".join(an_cov.get(code, []))))
        if not ok:
            gaps.append(code)
    ctypes = {}
    for s in scen:
        if s["id"] in INTENT_G:
            ct = INTENT_G[s["id"]].get("ctype")
            if ct and not s["id"].startswith("anomaly-"):
                ctypes.setdefault(ct, []).append(s["id"])
    for ct in PLAN_8_3:
        ok = ct in ctypes
        print("8.3 %-42s %s %s" % (ct, "OK " if ok else "GAP", ",".join(ctypes.get(ct, []))))
        if not ok:
            gaps.append(ct)
    have = {}
    for s in scen:
        have.setdefault((s["expect"]["class"], s["expect"]["subtype"]), []).append(s["id"])
    for cls, subs in ROWS_8_4:
        for sub in subs:
            ok = (cls, sub) in have
            print("8.4 %-24s %-34s %s %s" % (cls, sub, "OK " if ok else "GAP", len(have.get((cls, sub), []))))
            if not ok:
                gaps.append((cls, sub))
    # CL-MALFORMED-SMS and CL-WAP-UNREADABLE subtypes
    for cls, codes in (("CL-MALFORMED-SMS", ["AN-ADDR-TRUNC", "AN-ADDR-LEN", "AN-PDU-SHORT", "AN-UDL-LONG", "AN-UDH-LEN", "AN-IE-OVERRUN"]),
                       ("CL-UNREADABLE-SMS", ["AN-MTI"]),
                       ("CL-WAP-UNREADABLE", ["AN-WSP-TYPE", "AN-WSP-HLEN", "AN-WSP-CTYPE"])):
        for c in codes:
            ok = (cls, c) in have
            print("8.4 %-24s %-34s %s" % (cls, c, "OK " if ok else "GAP"))
            if not ok:
                gaps.append((cls, c))
    for p in PATTERNS + ["pattern-cp-userpin-unknown-sender", "pattern-sim-browser-wib"]:
        ok = p in byid and "sequence" in byid[p]
        print("8.6 %-40s %s" % (p, "OK " if ok else "GAP"))
        if not ok:
            gaps.append(p)
    for sub in ("SIM-OTA-RFM", "SIM-OTA-RAM", "SIM-OTA-SAT", "SIM-OTA-WIB", "SIM-OTA-NOSEC", "SIM-OTA-OTHER"):
        ok = ("CL-SIM-DD", sub) in have
        print("8.5 %-16s %s" % (sub, "OK " if ok else "GAP"))
        if not ok:
            gaps.append(sub)
    for need in ("text-7bit", "text-multipart", "text-ucs2", "data-port-2948-raw", "data-port-2949", "data-port-9200",
                 "data-port-16001", "data-port-0"):
        ok = need in byid
        print("min %-22s %s" % (need, "OK " if ok else "GAP"))
        if not ok:
            gaps.append(need)
    print("gaps:", gaps if gaps else "none")
    if gaps:
        ERR.append("coverage gaps: %s" % gaps)


INTENT_G = {}

if __name__ == "__main__":
    INTENT_G.update(json.load(open(sys.argv[2], encoding="utf-8")))
    main()
