package dev.nightjar.payload.outbound;

import dev.nightjar.contract.ClassId;

/**
 * The outbound class table. One row per {@link OutboundKind}; the
 * STK column wins when HK-22 marked the send.
 *
 * <table>
 *   <caption>Outbound classification</caption>
 *   <tr><th>kind</th><th>STK mark</th><th>persistMessage</th><th>class</th></tr>
 *   <tr><td>DATA</td><td>ignored</td><td>—</td><td>{@code CL-MO-DATA}</td></tr>
 *   <tr><td>RAW</td><td>no</td><td>—</td><td>{@code CL-MO-RAW}</td></tr>
 *   <tr><td>TEXT / MULTIPART</td><td>no</td><td>{@code false}</td>
 *       <td>{@code CL-MO-UNPERSISTED}</td></tr>
 *   <tr><td>TEXT / MULTIPART</td><td>no</td><td>{@code true} / absent</td>
 *       <td>{@code CL-MO-VISIBLE}</td></tr>
 *   <tr><td>STORED</td><td>no</td><td>—</td><td>{@code CL-MO-VISIBLE}</td></tr>
 *   <tr><td>VVM</td><td>ignored</td><td>—</td><td>{@code CL-MO-VVM}</td></tr>
 *   <tr><td>any except DATA/VVM</td><td>yes</td><td>—</td><td>{@code CL-MO-STK}</td></tr>
 * </table>
 *
 * <p>Two rules then decide whether anything is stored at all:
 * <ul>
 *   <li>a send by the <b>default SMS app</b> is a counter only — it is the user's own message, not
 *       a hidden one;
 *   <li>{@code CL-MO-VISIBLE} is a counter only unless {@code outgoing.logVisible} is on, and its
 *  body is never stored while the setting is off (for the outbound side).
 * </ul>
 *
 * <p>Thread: worker only (reads one {@code settings.json} snapshot).
 */
public final class OutboundClassification {

    /**
     * @param classId the outbound class
     * @param counterOnly true when the event becomes a counter and no row is stored
     * @param body true when the dispatcher PDU may be stored with the event
     */
    public record Result(ClassId classId, boolean counterOnly, boolean body) {}

    private record Row(ClassId base, ClassId stk) {}

    /** Indexed by {@link OutboundKind#ordinal()}. */
    private static final Row[] ROWS = {
        // DATA: an STK SEND SMS never arrives as a data SMS, so the STK mark does not change it.
        new Row(ClassId.MO_DATA, ClassId.MO_DATA),
        // RAW
        new Row(ClassId.MO_RAW, ClassId.MO_STK),
        // TEXT
        new Row(ClassId.MO_VISIBLE, ClassId.MO_STK),
        // MULTIPART
        new Row(ClassId.MO_VISIBLE, ClassId.MO_STK),
        // STORED: re-sent from the Sent box, so it is persisted by definition.
        new Row(ClassId.MO_VISIBLE, ClassId.MO_STK),
        // VVM: the voicemail client's own setup message.
        new Row(ClassId.MO_VVM, ClassId.MO_VVM)
    };

    private OutboundClassification() {}

    /**
     * @param kind what the caller asked for
     * @param stk HK-22 marked this send as SIM-toolkit originated
     * @param persist {@code persistMessage} of the call, null when the call has none
     * @param defaultSmsApp the calling package is the user's default SMS app
     * @param logVisible {@code outgoing.logVisible}
     */
    public static Result classify(OutboundKind kind, boolean stk, Boolean persist,
            boolean defaultSmsApp, boolean logVisible) {
        if (kind == null) kind = OutboundKind.TEXT;
        Row row = ROWS[kind.ordinal()];
        ClassId classId = stk ? row.stk() : row.base();
        if (!stk && (kind == OutboundKind.TEXT || kind == OutboundKind.MULTIPART)
                && Boolean.FALSE.equals(persist)) {
            classId = ClassId.MO_UNPERSISTED;
        }
        boolean counterOnly = defaultSmsApp
                || (classId == ClassId.MO_VISIBLE && !logVisible);
        return new Result(classId, counterOnly, !counterOnly);
    }
}
