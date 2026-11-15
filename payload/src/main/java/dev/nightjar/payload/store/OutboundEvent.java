package dev.nightjar.payload.store;

/**
 * A sealed event as the IPC layer sees it: identity for acknowledgement plus the serialized
 * object. Immutable so the ring can hand it out without copying.
 *
 * @param eventId stable event id
 * @param revision revision the JSON represents
 * @param json IPC event object, one line
 * @param bytes UTF-8 size of {@code json}, used for the ring's byte cap
 */
public record OutboundEvent(String eventId, int revision, String json, int bytes) {

    /** An event whose {@link #bytes()} is the UTF-8 length of {@code json}: the one size every producer uses. */
    public static OutboundEvent of(String eventId, int revision, String json) {
        return new OutboundEvent(eventId, revision, json, json == null ? 0 : utf8Length(json));
    }

    /** UTF-8 length of {@code s} without allocating the encoded array. */
    public static int utf8Length(String s) {
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                len += 1;
            } else if (c < 0x800) {
                len += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                len += 4;
                i++;
            } else {
                len += 3;
            }
        }
        return len;
    }
}
