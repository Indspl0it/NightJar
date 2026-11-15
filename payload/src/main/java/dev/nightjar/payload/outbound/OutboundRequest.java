package dev.nightjar.payload.outbound;

/**
 * One {@code SmsController#send*} call as the HK-19 before-hook saw it.
 *
 * <p>Built on the binder thread from the method arguments only: nothing here reads the PDU (that
 * is HK-20's job) and nothing here is written back to the caller — outbound capture never modifies
 * an argument.
 *
 * @param method {@code SmsController} method name, for the trace and the UI
 * @param kind which classification row applies
 * @param callingPackage the argument, or null when the framework resolves it itself
 * @param attributionTag {@code callingAttributionTag}, often null
 * @param subId subscription id ({@code sendVisualVoicemailSmsForSubscriber} puts it second)
 * @param destAddr destination address as the caller typed it; null for VVM, the stored forms and raw PDUs
 * @param scAddr SMSC override, or null
 * @param destPort data/VVM destination port, −1 when the call has none
 * @param textLen characters of text, −1 when the call carries none
 * @param dataLen bytes of data, −1 when the call carries none
 * @param persist {@code persistMessage} when the call has one, null otherwise
 * @param stk HK-22 marked this send as SIM-toolkit originated
 * @param uid {@code Binder.getCallingUid()} at the hook, −1 when unreadable
 * @param sentIntent the caller's sent {@code PendingIntent} (framework type), for the outbound
 *  firewall; null when absent
 * @param deliveryIntent the caller's delivery {@code PendingIntent}, or null
 */
public record OutboundRequest(
        String method,
        OutboundKind kind,
        String callingPackage,
        String attributionTag,
        int subId,
        String destAddr,
        String scAddr,
        int destPort,
        int textLen,
        int dataLen,
        Boolean persist,
        boolean stk,
        int uid,
        Object sentIntent,
        Object deliveryIntent) {
}
