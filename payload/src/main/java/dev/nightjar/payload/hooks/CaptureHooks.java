package dev.nightjar.payload.hooks;

import dev.nightjar.contract.CbChannels;
import dev.nightjar.contract.Hashes;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.env.Clock;

/**
 * Hook bodies for the raw-capture stages: HK-01/01b, HK-02/02b, HK-03,
 * HK-03b, HK-04, HK-05 and HK-21. {@link CbHooks} owns HK-17 and uses the CB header read here ({@code CbFields}).
 *
 * <p>Every body copies what it needs, records one {@link Observation} and returns. No I/O, no
 * binder, no JSON, no locks; the only allocation is the observation and the byte copy.
 *
 * <p>Threads: HAL binder (HK-01…03), IMS binder (HK-04/05), main looper (HK-03b), SmsController binder (HK-21).
 */
public final class CaptureHooks {
    private final Observations out;
    private final Clock clock;

    private volatile dev.nightjar.payload.capture.CrashJournal journal;

    public void setJournal(dev.nightjar.payload.capture.CrashJournal journal) {
        this.journal = journal;
    }

    public CaptureHooks(Observations out, Clock clock) {
        this.out = out;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ radio (HK-01/01b/02/02b)

    /**
     * HK-01 {@code MessagingIndication#newSms(int, byte[])} (AIDL radio). The first argument is the
     * indication type and is ignored; the phone id comes from {@link #phoneId(Object)}.
     */
    public void onNewSms(dev.nightjar.payload.hook.Param p) {
        byte[] pdu = bytes(p.arg(1));
        if (pdu == null) return;
        int phoneId = phoneId(p.thisObject());
        pushRaw(pdu, phoneId, -1, false, dev.nightjar.contract.Transport.CS, false);
    }

    /** HK-01b HIDL {@code RadioIndication#newSms(int, ArrayList<Byte>)}; NOT_APPLICABLE on A17. */
    public void onNewSmsHidl(dev.nightjar.payload.hook.Param p) {
        byte[] pdu = listBytes(p.arg(1));
        if (pdu == null) return;
        pushRaw(pdu, phoneId(p.thisObject()), -1, false, dev.nightjar.contract.Transport.CS, true);
    }

    /** HK-02 {@code newSmsStatusReport(int, byte[])} → CL-STATUS, sealed immediately. */
    public void onSmsStatusReport(dev.nightjar.payload.hook.Param p) {
        byte[] pdu = bytes(p.arg(1));
        if (pdu == null) return;
        pushRaw(pdu, phoneId(p.thisObject()), -1, true, dev.nightjar.contract.Transport.CS, false);
    }

    /** HK-02b HIDL twin; NOT_APPLICABLE on A17. */
    public void onSmsStatusReportHidl(dev.nightjar.payload.hook.Param p) {
        byte[] pdu = listBytes(p.arg(1));
        if (pdu == null) return;
        pushRaw(pdu, phoneId(p.thisObject()), -1, true, dev.nightjar.contract.Transport.CS, true);
    }

    /** HK-03 {@code newSmsOnSim(int, int)}: record index only. */
    public void onNewSmsOnSim(dev.nightjar.payload.hook.Param p) {
        int recordIndex = p.arg(1) instanceof Number ? ((Number) p.arg(1)).intValue() : -1;
        int phoneId = phoneId(p.thisObject());
        if (recordIndex < 0) return;
        out.offer(Observation.simOnSim(phoneId, recordIndex, clock.elapsedNs()));
    }

    // ------------------------------------------------------------------ IMS (HK-04/05)

    /**
     * HK-04 {@code onSmsReceived(int token, String format, byte[] pdu)}. A non-3gpp format is
     * counted and dropped; the payload never classifies CDMA.
     */
    public void onImsSmsReceived(dev.nightjar.payload.hook.Param p) {
        String format = p.arg(1) instanceof String s ? s : null;
        if (format != null && !"3gpp".equalsIgnoreCase(format)) return;
        byte[] pdu = bytes(p.arg(2));
        if (pdu == null) return;
        int token = p.arg(0) instanceof Number ? ((Number) p.arg(0)).intValue() : -1;
        int phoneId = phoneId(p.thisObject());
        pushRaw(pdu, phoneId, token, false, dev.nightjar.contract.Transport.IMS, false);
    }

    /** HK-05 {@code onSmsStatusReportReceived(int, String, byte[])}. */
    public void onImsStatusReport(dev.nightjar.payload.hook.Param p) {
        byte[] pdu = bytes(p.arg(2));
        if (pdu == null) return;
        int token = p.arg(0) instanceof Number ? ((Number) p.arg(0)).intValue() : -1;
        pushRaw(pdu, phoneId(p.thisObject()), token, true, dev.nightjar.contract.Transport.IMS, false);
    }

    // ------------------------------------------------------------------ SIM (HK-03b) and inject (HK-21)

    /** HK-03b {@code SIMRecords#handleSms(byte[])}: the next dispatch of this TPDU came from the SIM. */
    public void onSimRecordsHandleSms(dev.nightjar.payload.hook.Param p) {
        byte[] pdu = bytes(p.arg(0));
        if (pdu == null) return;
        out.offer(Observation.simMark(keyFor(pdu), clock.elapsedNs()));
    }

    /**
     * HK-21 {@code injectSmsPduForSubscriber(int, byte[], String, PendingIntent)}: remember the
     * injecting uid so the following source-2 dispatch is {@code CL-INJECTED} with a package
     * . The package lookup happens on the worker.
     */
    public void onInjectSmsPdu(dev.nightjar.payload.hook.Param p) {
        byte[] pdu = bytes(p.arg(1));
        if (pdu == null) return;
        int uid = callingUid();
        out.offer(Observation.inject(keyFor(pdu), uid, clock.elapsedNs()));
    }

    // ------------------------------------------------------------------ helpers

    private void pushRaw(byte[] pdu, int phoneId, int token, boolean statusReport,
            dev.nightjar.contract.Transport transport, boolean hidl) {
        String key = keyFor(pdu);
        String alt = Hashes.tpduKey(pdu);
        long t = clock.elapsedNs();
        dev.nightjar.payload.capture.CrashJournal j = journal;
        if (j != null && !statusReport) j.append(pdu, transport, phoneId, clock.wallMs());
        out.offer(Observation.raw(key, alt, pdu, "3gpp", transport, phoneId, -1, -1, token,
                statusReport, t, clock.wallMs()));
    }

    /** The correlation key: the stripped TPDU when the PDU carries an SMSC prefix. */
    public static String keyFor(byte[] pdu) {
        return Hashes.tpduKey(Hashes.stripSmsc(pdu));
    }

    private static byte[] bytes(Object v) {
        if (v instanceof byte[] b) return b.clone();
        return null;
    }

    private static byte[] listBytes(Object v) {
        if (!(v instanceof java.util.List<?> l) || l.isEmpty()) return null;
        byte[] out = new byte[l.size()];
        for (int i = 0; i < out.length; i++) {
            Object o = l.get(i);
            if (!(o instanceof Byte b)) return null;
            out[i] = b;
        }
        return out;
    }

    /**
     * Phone id of the IMS indication, −1 when unreadable.
     *
     * <p>The hook target is the anonymous {@code ImsSmsDispatcher$4}, which has no {@code mRil}:
     * the dispatcher is the outer instance ({@code this$0}) and the phone hangs off its
     * {@code mPhone} field. Reading {@code mRil} there always returned −1, which turned every IMS
     * raw event into a dual-SIM wildcard in the correlator.
     */
    private static int phoneId(Object indication) {
        Object dispatcher = Reflect.get(indication, "this$0", null);
        Object phone = dispatcher == null ? null : Reflect.get(dispatcher, "mPhone", null);
        if (phone != null) {
            int id = Reflect.callInt(phone, "getPhoneId", Integer.MIN_VALUE);
            if (id != Integer.MIN_VALUE) return id;
            int field = Reflect.getInt(phone, "mPhoneId", Integer.MIN_VALUE);
            if (field != Integer.MIN_VALUE) return field;
        }
        // Some builds expose the phone directly on the indication's enclosing object.
        Object ril = Reflect.get(indication, "mRil", null);
        return ril == null ? -1 : Reflect.getInt(ril, "mPhoneId", -1);
    }

    /** {@code Binder.getCallingUid()} without a hard dependency on android.os in unit tests. */
    private static int callingUid() {
        try {
            return android.os.Binder.getCallingUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Minimal CB header read for the event fields, for both layouts of TS 23.041: the GSM page (§9.4.1.2, at most 88
     * octets) and the UMTS message (message type 1 and longer than one GSM page, {@link Observation#umtsCb}).
     */
    static final class CbFields {
        /** A GSM CB page is at most 88 octets (TS 23.041). */
        private static final int GSM_CB_PAGE = 88;
        /** An ETWS primary notification is at most 56 octets and has no page parameter (TS 23.041). */
        private static final int ETWS_PRIMARY_MAX = 56;

        final int serial;
        final int messageId;
        final int page;
        final int pages;
        final boolean emergency;

        private CbFields(int serial, int messageId, int page, int pages, boolean emergency) {
            this.serial = serial;
            this.messageId = messageId;
            this.page = page;
            this.pages = pages;
            this.emergency = emergency;
        }

        /**
         * GSM: {@code serial_number(2), message_identifier(2), dcs(1), page_parameter(1)}. An ETWS primary
         * notification carries the warning type where the page parameter sits, so it is always one page. A page
         * parameter with a zero field or a page index above the total counts as one page, as in the framework.
         *
         * <p>UMTS: {@code message_type(1), message_identifier(2), serial_number(2), dcs(1), number_of_pages(1)}. One
         * UMTS message carries all its pages, so the event is page 1 of 1.
         *
         * <p>When the layout is not clear (longer than a GSM page but not message type 1) or the message
         * identifier cannot be read, the broadcast counts as an emergency broadcast, so it is never blocked.
         */
        static CbFields of(byte[] pdu) {
            if (pdu == null) return new CbFields(-1, -1, 1, 1, true);
            if (Observation.umtsCb(pdu)) return umts(pdu);
            boolean unclear = pdu.length > GSM_CB_PAGE || pdu.length < 4;
            int serial = -1;
            int messageId = -1;
            int page = 1;
            int pages = 1;
            if (pdu.length >= 4) {
                serial = ((pdu[0] & 0xFF) << 8) | (pdu[1] & 0xFF);
                messageId = ((pdu[2] & 0xFF) << 8) | (pdu[3] & 0xFF);
            }
            boolean etwsPrimary = messageId >= CbChannels.ETWS_FIRST
                    && messageId <= CbChannels.ETWS_FIRST + 4
                    && pdu.length <= ETWS_PRIMARY_MAX;
            if (!etwsPrimary && pdu.length >= 6) {
                int pageField = pdu[5] & 0xFF;
                int total = pageField & 0x0F;
                int index = (pageField >> 4) & 0x0F;
                if (total > 0 && index > 0 && index <= total) {
                    pages = total;
                    page = index;
                }
            }
            boolean emergency = unclear || CbChannels.isEmergency(messageId);
            return new CbFields(serial, messageId, page, pages, emergency);
        }

        private static CbFields umts(byte[] pdu) {
            int messageId = ((pdu[1] & 0xFF) << 8) | (pdu[2] & 0xFF);
            int serial = ((pdu[3] & 0xFF) << 8) | (pdu[4] & 0xFF);
            return new CbFields(serial, messageId, 1, 1, CbChannels.isEmergency(messageId));
        }
    }
}
