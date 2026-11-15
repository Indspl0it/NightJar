package dev.nightjar.payload.outbound;

import dev.nightjar.payload.hook.Param;
import java.util.List;

/**
 * Per-method parameter index map for the nine HK-19 {@code SmsController#send*} entry points
 * . Both reference builds agree on every index; the only build difference is that
 * {@code sendRawPduForSubscriber} does not exist on OnePlus, so {@link #RAW} is absent there and
 * the profile marks it {@code NA} (the raw-PDU outbound path is still captured at HK-20).
 *
 * <table>
 *   <caption>Index map, 0-based, excluding {@code this}. Verified 2026-11-05 on Pixel 8a (A17) and
 *   OnePlus 13 (A16/ColorOS).</caption>
 *   <tr><th>method</th><th>arity</th><th>subId</th><th>pkg</th><th>attrTag</th><th>dest</th>
 *       <th>sc</th><th>port</th><th>text</th><th>data</th><th>persist</th><th>sent</th>
 *       <th>delivery</th></tr>
 *   <tr><td>{@code sendTextForSubscriber}</td><td>12</td><td>0</td><td>1</td><td>2</td><td>3</td>
 *       <td>4</td><td>—</td><td>5</td><td>—</td><td>8</td><td>6</td><td>7</td></tr>
 *   <tr><td>{@code sendTextForSubscriberWithOptions}</td><td>12</td><td>0</td><td>1</td><td>2</td>
 *       <td>3</td><td>4</td><td>—</td><td>5</td><td>—</td><td>8</td><td>6</td><td>7</td></tr>
 *   <tr><td>{@code sendDataForSubscriber}</td><td>9</td><td>0</td><td>1</td><td>2</td><td>3</td>
 *       <td>4</td><td>5</td><td>—</td><td>6</td><td>—</td><td>7</td><td>8</td></tr>
 *   <tr><td>{@code sendRawPduForSubscriber}</td><td>7</td><td>0</td><td>1</td><td>2</td><td>—</td>
 *       <td>3</td><td>—</td><td>—</td><td>4</td><td>—</td><td>5</td><td>6</td></tr>
 *   <tr><td>{@code sendMultipartTextForSubscriber}</td><td>10</td><td>0</td><td>1</td><td>2</td>
 *       <td>3</td><td>4</td><td>—</td><td>5</td><td>—</td><td>8</td><td>6</td><td>7</td></tr>
 *   <tr><td>{@code sendMultipartTextForSubscriberWithOptions}</td><td>12</td><td>0</td><td>1</td>
 *       <td>2</td><td>3</td><td>4</td><td>—</td><td>5</td><td>—</td><td>8</td><td>6</td>
 *       <td>7</td></tr>
 *   <tr><td>{@code sendStoredText}</td><td>7</td><td>0</td><td>1</td><td>2</td><td>—</td>
 *       <td>4</td><td>—</td><td>—</td><td>—</td><td>—</td><td>5</td><td>6</td></tr>
 *   <tr><td>{@code sendStoredMultipartText}</td><td>7</td><td>0</td><td>1</td><td>2</td><td>—</td>
 *       <td>4</td><td>—</td><td>—</td><td>—</td><td>—</td><td>5</td><td>6</td></tr>
 *   <tr><td>{@code sendVisualVoicemailSmsForSubscriber}</td><td>8</td><td>3</td><td>0</td><td>2</td>
 *       <td>4</td><td>—</td><td>5</td><td>6</td><td>—</td><td>—</td><td>7</td><td>—</td></tr>
 * </table>
 *
 * <p>The 10-argument {@code sendTextForSubscriber} and the 8-argument
 * {@code sendDataForSubscriber} are {@code @Deprecated} overloads that delegate to the ones above,
 * so only the delegating form is hooked and one send is recorded exactly once. The stored forms
 * carry no destination argument: it lives in the content {@code Uri}, so it is left empty here and
 * filled from the tracker's {@code mDestAddress} at HK-20. {@code sendRawPduForSubscriber} has no
 * destination argument either: parameter 3 is the SMSC address string (Pixel 8a decomp:
 * {@code SmsDispatchersController#sendRawPdu} reads it as {@code scAddr}), and the destination is in
 * the caller's TPDU.
 *
 * <p>Thread: any (immutable constants).
 */
public final class OutboundRequests {

    /**
     * One row of the table. Every index is 0-based over the method parameters and −1 when the
     * method has no such parameter.
     */
    public record Map(
            String method,
            int arity,
            OutboundKind kind,
            int subId,
            int pkg,
            int attr,
            int dest,
            int sc,
            int port,
            int text,
            int data,
            int persist,
            int sent,
            int delivery) {}

    private static Map row(String method, int arity, OutboundKind kind, int subId, int pkg, int attr,
            int dest, int sc, int port, int text, int data, int persist, int sent, int delivery) {
        return new Map(method, arity, kind, subId, pkg, attr, dest, sc, port, text, data, persist,
                sent, delivery);
    }

    /** Every hooked entry point; {@link #RAW} is Pixel-only. */
    public static final List<Map> ALL = List.of(
            row("sendTextForSubscriber", 12, OutboundKind.TEXT, 0, 1, 2, 3, 4, -1, 5, -1, 8, 6, 7),
            row("sendTextForSubscriberWithOptions", 12, OutboundKind.TEXT, 0, 1, 2, 3, 4, -1, 5, -1,
                    8, 6, 7),
            row("sendDataForSubscriber", 9, OutboundKind.DATA, 0, 1, 2, 3, 4, 5, -1, 6, -1, 7, 8),
            row("sendRawPduForSubscriber", 7, OutboundKind.RAW, 0, 1, 2, -1, 3, -1, -1, 4, -1, 5, 6),
            row("sendMultipartTextForSubscriber", 10, OutboundKind.MULTIPART, 0, 1, 2, 3, 4, -1, 5,
                    -1, 8, 6, 7),
            row("sendMultipartTextForSubscriberWithOptions", 12, OutboundKind.MULTIPART, 0, 1, 2, 3,
                    4, -1, 5, -1, 8, 6, 7),
            row("sendStoredText", 7, OutboundKind.STORED, 0, 1, 2, -1, 4, -1, -1, -1, -1, 5, 6),
            row("sendStoredMultipartText", 7, OutboundKind.STORED, 0, 1, 2, -1, 4, -1, -1, -1, -1, 5,
                    6),
            row("sendVisualVoicemailSmsForSubscriber", 8, OutboundKind.VVM, 3, 0, 2, 4, -1, 5, 6, -1,
                    -1, 7, -1));

    /** The Pixel-only entry point, named so the profile and the tests can refer to it. */
    public static final String RAW = "sendRawPduForSubscriber";

    private OutboundRequests() {}

    /** The row for {@code method} at {@code arity}, or null when this build does not declare it. */
    public static Map map(String method, int arity) {
        for (Map m : ALL) {
            if (m.method().equals(method) && m.arity() == arity) return m;
        }
        return null;
    }

    /**
     * Builds the request from the hook arguments. Reads only; nothing is written back.
     *
     * @param stk HK-22 marked this send as SIM-toolkit originated
     */
    public static OutboundRequest of(Map m, Param p, boolean stk) {
        int uid = callingUid();
        return new OutboundRequest(
                m.method(),
                m.kind(),
                string(p, m.pkg()),
                string(p, m.attr()),
                number(p, m.subId(), -1),
                string(p, m.dest()),
                string(p, m.sc()),
                number(p, m.port(), -1),
                textLength(p, m.text()),
                dataLength(p, m.data()),
                bool(p, m.persist()),
                stk,
                uid,
                object(p, m.sent()),
                object(p, m.delivery()));
    }

    private static Object object(Param p, int index) {
        return index < 0 ? null : p.arg(index);
    }

    private static String string(Param p, int index) {
        Object v = object(p, index);
        return v instanceof String s && !s.isEmpty() ? s : null;
    }

    private static int number(Param p, int index, int fallback) {
        Object v = object(p, index);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static Boolean bool(Param p, int index) {
        Object v = object(p, index);
        return v instanceof Boolean b ? b : null;
    }

    /** Characters of a text parameter, or of every part of a multipart list; −1 when absent. */
    private static int textLength(Param p, int index) {
        Object v = object(p, index);
        if (v instanceof String s) return s.length();
        if (v instanceof List<?> l) {
            int n = 0;
            for (Object o : l) {
                if (o instanceof String s) n += s.length();
            }
            return l.isEmpty() ? -1 : n;
        }
        return -1;
    }

    /** Bytes of a data parameter; −1 when absent. */
    private static int dataLength(Param p, int index) {
        Object v = object(p, index);
        return v instanceof byte[] b ? b.length : -1;
    }

    /** {@code Binder.getCallingUid()} without a hard dependency on android.os in unit tests. */
    private static int callingUid() {
        try {
            return android.os.Binder.getCallingUid();
        } catch (Throwable t) {
            return -1;
        }
    }
}
