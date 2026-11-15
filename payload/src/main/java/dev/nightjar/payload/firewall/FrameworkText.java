package dev.nightjar.payload.firewall;

import android.telephony.SmsMessage;
import dev.nightjar.payload.capture.PduFacts;

/**
 * {@link TextSource} over the framework decoder.
 *
 * <p>Text conditions are rare, so decoding happens only when a rule that matched every other condition needs the body.
 * The payload never stores the text: it is used for the comparison and dropped.
 *
 * <p>Device only; JVM tests pass a lambda. {@code PayloadRuntime.wire()} gives it to the {@link Enforcer}.
 *
 * <p>Thread: the hook thread that evaluates the verdict (main looper for HK-08).
 */
public final class FrameworkText implements TextSource {

    private static final String FORMAT_3GPP = "3gpp";

    /**
     * @param pdu the PDU as the enforcement point has it: a radio PDU with the SMSC prefix (HK-08) or a bare TPDU (HK-15).
     *     The 3GPP parser expects the SMSC prefix, so a bare TPDU gets an empty one ({@code 0x00}).
     */
    @Override
    public CharSequence decode(byte[] pdu) {
        if (pdu == null || pdu.length == 0) return null;
        try {
            byte[] radio = pdu;
            if (!PduFacts.parseRadio(pdu).valid() && PduFacts.parse(pdu).valid()) {
                radio = new byte[pdu.length + 1];
                System.arraycopy(pdu, 0, radio, 1, pdu.length);
            }
            SmsMessage message = SmsMessage.createFromPdu(radio, FORMAT_3GPP);
            return message == null ? null : message.getMessageBody();
        } catch (Throwable t) {
            return null;
        }
    }
}
