"""Builders for the PDUs of testdata/scenarios.json. Synthetic data only."""

SMSC_DIGITS = "15550100"
OA_DEFAULT = "15550142"
SCTS = bytes.fromhex("62115021436522")

GSM = ("@£$¥èéùìòÇ\nØø\rÅå"
       "Δ_ΦΓΛΩΠΨΣΘΞ\x1bÆæßÉ"
       " !\"#¤%&'()*+,-./0123456789:;<=>?"
       "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§"
       "¿abcdefghijklmnopqrstuvwxyzäöñüà")
assert len(GSM) == 128


def septets(text):
    return [GSM.index(c) for c in text]


def pack7(sept, fill=0):
    val = 0
    for i, s in enumerate(sept):
        val |= s << (fill + 7 * i)
    nbits = fill + 7 * len(sept)
    return val.to_bytes((nbits + 7) // 8, "little") if nbits else b""


def bcd(digits):
    d = digits + ("F" if len(digits) % 2 else "")
    return bytes(int(d[i + 1] + d[i], 16) for i in range(0, len(d), 2))


def smsc():
    v = bcd(SMSC_DIGITS)
    return bytes([1 + len(v), 0x91]) + v


def oa_num(digits=OA_DEFAULT, toa=0x91):
    return bytes([len(digits), toa]) + bcd(digits)


def uintvar(n):
    out = [n & 0x7F]
    n >>= 7
    while n:
        out.append(0x80 | (n & 0x7F))
        n >>= 7
    return bytes(reversed(out))


def ie(iei, data):
    return bytes([iei, len(data)]) + bytes(data)


def port_ie(dest, src=0, wide=True):
    if wide:
        return ie(0x05, [dest >> 8, dest & 255, src >> 8, src & 255])
    return ie(0x04, [dest, src])


def with_udh(udh_ies):
    return bytes([len(udh_ies)]) + udh_ies


def ud7(text, udh=b""):
    s = septets(text)
    if not udh:
        return len(s), pack7(s)
    full = with_udh(udh)
    fill = (7 - (len(full) * 8) % 7) % 7
    hs = (len(full) * 8 + fill) // 7
    return hs + len(s), full + pack7(s, fill)


def ud_bytes(data, udh=b""):
    full = (with_udh(udh) if udh else b"") + data
    return len(full), full


def deliver(oa=None, pid=0x00, dcs=0x00, udl=0, ud=b"", udhi=False, first=None, scts=SCTS):
    if oa is None:
        oa = oa_num()
    if first is None:
        first = 0x04 | (0x40 if udhi else 0)
    return smsc() + bytes([first]) + oa + bytes([pid, dcs]) + scts + bytes([udl]) + ud


def status_report(st, mr=1):
    return smsc() + bytes([0x06, mr]) + oa_num() + SCTS + SCTS + bytes([st, 0x00])


def hx(b):
    return b.hex().upper()


# ---- WSP / WBXML / bodies ----------------------------------------------------------------------
def ct_short(v):
    return bytes([0x80 | v])


def ct_long(v):
    n = (v.bit_length() + 7) // 8
    media = bytes([n]) + v.to_bytes(n, "big")
    return bytes([len(media)]) + media


def ct_text(s):
    return s.encode() + b"\0"


def ct_params(media_short, params):
    body = bytes([0x80 | media_short]) + params
    return (bytes([len(body)]) if len(body) <= 30 else b"\x1f" + uintvar(len(body))) + body


def app_id(n):
    return bytes([0xAF, 0x80 | n])


def wsp(ct, headers=b"", body=b"", tid=1, ptype=6, hlen=None):
    hdr = ct + headers
    return bytes([tid, ptype]) + uintvar(len(hdr) if hlen is None else hlen) + hdr + body


def push_ud(wsp_bytes, dest=2948, src=9200):
    return ud_bytes(wsp_bytes, port_ie(dest, src))


def H(s):
    return bytes.fromhex(s)


SI_WBXML = H("02056A0045C60C03") + b"example.test/a\0" + H("01") + b"\x03Test message 1\0" + H("0101")
SI_DELETE = H("02056A0045C60C03") + b"example.test/a\0" + H("0901") + H("0101")
SL_WBXML = H("02066A00850903") + b"example.test/a\0" + H("01")
CO_WBXML = H("02076A0045860503") + b"foo.wml\0" + H("01") + H("8705032F626172000101")
CP_WBXML = H("020B6A0045C65501870706") + b"\x03Test APN\0" + H("010101")
SI_TEXT = b'<?xml version="1.0"?><si><indication href="http://example.test/a">Test message 1</indication></si>'
SL_TEXT = b'<?xml version="1.0"?><sl href="http://example.test/a"/>'
CO_TEXT = b'<?xml version="1.0"?><co><invalidate-object uri="http://example.test/a"/></co>'
WML_TEXT = b'<?xml version="1.0"?><wml><card id="c"><p>Test message 1</p></card></wml>'
WMLC = H("01046A00") + H("7F6760") + b"\x03Test\0" + H("010101")
DM_BOOT = H("020C6A004501")
EMN_WBXML = H("020A6A00850803") + b"example.test\0" + H("01")
DRM_WBXML = H("020E6A004501")
DRM_XML = b'<o-ex:rights xmlns:o-ex="http://odrl.net/1.1/ODRL-EX"></o-ex:rights>'
VCARD = b"BEGIN:VCARD\r\nVERSION:2.1\r\nFN:Test User\r\nEND:VCARD"
VCAL = b"BEGIN:VCALENDAR\r\nVERSION:1.0\r\nBEGIN:VEVENT\r\nSUMMARY:Test event\r\nEND:VEVENT\r\nEND:VCALENDAR"
IMPS = H("0102030405")
PNG = H("89504E470D0A1A0A")

MMS_NOTIFY = H("8C82") + b"\x98T1\0" + H("8D908A808E020FA088048102A8C0") + b"\x83http://example.test/m1\0"
MMS_DELIVERY = H("8C868D90") + b"\x8BT1\0" + b"\x97" + b"15550142/TYPE=PLMN\0" + H("85045F5E10009580")
MMS_READ = H("8C888D90") + b"\x8BT1\0" + b"\x97" + b"15550142/TYPE=PLMN\0" + H("85045F5E10009B80")


class BitWriter:
    def __init__(self):
        self.bits = []

    def put(self, value, count):
        for i in range(count - 1, -1, -1):
            self.bits.append((value >> i) & 1)

    def raw(self, data):
        for b in data:
            self.put(b, 8)

    def build(self):
        out = bytearray((len(self.bits) + 7) // 8)
        for i, b in enumerate(self.bits):
            if b:
                out[i // 8] |= 1 << (7 - i % 8)
        return bytes(out)


def supl_init(ntype):
    """ULP-PDU with a SUPL INIT, ITU-T X.691 unaligned PER (OMA-TS-ULP), the layout of the shared payload and decoder
    reader. Reference: the SUPL vectors in decoder/src/test/resources/corpus/supl.
    ntype 0 gives init_v1_silent and ntype 2 gives init_v3_verify_allowed byte for byte; ntype 1 (NOTIFY) is the same
    body with NotificationType 1 (not init_v2_notify_fqdn_mac, which is another vector). Synthetic data: SLP session
    ID 1, SLP address 192.0.2.1 (TEST-NET-1), no SET session ID."""
    w = BitWriter()
    w.put(0, 16)                                # ULP-PDU.length, set below
    w.put(2, 8); w.put(0, 8); w.put(0, 8)       # Version 2.0.0
    w.put(0, 1); w.put(1, 1)                    # SessionID: setSessionID absent, slpSessionID present
    w.raw(bytes([0, 0, 0, 1]))                  # slpSessionID.sessionID OCTET STRING(SIZE 4)
    w.put(0, 1); w.put(0, 1)                    # SLPAddress: extension bit, index 0 = iPAddress
    w.put(0, 1); w.raw(bytes([192, 0, 2, 1]))   # IPAddress index 0 = ipv4Address
    w.put(0, 1); w.put(0, 3)                    # UlpMessage: extension bit, index 0 = msSUPLINIT
    w.put(0, 1)                                 # SUPLINIT extension bit
    w.put(1, 1)                                 # notification present
    w.put(0, 1); w.put(0, 1); w.put(0, 1); w.put(0, 1)  # sLPAddress, qoP, mAC, keyIdentity absent
    w.put(0, 1); w.put(1, 4)                    # PosMethod: extension bit, 1
    w.put(0, 1)                                 # Notification extension bit
    for _ in range(5):
        w.put(0, 1)                             # encodingType, requestorId, requestorIdType, clientName, clientNameType
    w.put(0, 1); w.put(ntype, 3)                # NotificationType: extension bit, value
    w.put(0, 1)                                 # SLPMode = proxy
    out = bytearray(w.build())                  # padded with 0 bits to an octet
    out[0] = (len(out) >> 8) & 255
    out[1] = len(out) & 255
    return bytes(out)


def dm_notification():
    server = b"dm.example.test"
    reason = b"Update"
    w = BitWriter()
    w.put(12, 10); w.put(1, 2); w.put(1, 1); w.put(1, 2); w.put(1, 2); w.put(1, 1)
    w.put(0, 16); w.put(0, 1); w.put(0, 1); w.put(1, 1); w.put(0, 4)
    w.put(0x1234, 16)
    w.put(len(server), 16); w.raw(server)
    w.put(len(reason), 16); w.raw(reason)
    return bytes(range(1, 21)) + w.build()


def ds_notification():
    return bytes([0x11] * 16) + bytes([0x0C, 0x20, 0x00, 0x00, 0x00, 0x12, 0x34, 0x05]) + b"ds.te"


def ota_packet(tar, spi, cc=b"", data=b"\x00\x00\x00\x00", kic=0x00, kid=0x00, cntr=1):
    hdr = spi + bytes([kic, kid]) + tar + cntr.to_bytes(5, "big") + b"\x00" + cc
    chl = len(hdr)
    cpl = 1 + chl + len(data)
    return cpl.to_bytes(2, "big") + bytes([chl]) + hdr + data


def cb_page(serial, mid, dcs, page, text):
    s = septets(text) + [0x0D] * (93 - len(text))
    body = (pack7(s) + bytes(82))[:82]
    return serial.to_bytes(2, "big") + mid.to_bytes(2, "big") + bytes([dcs, page]) + body
