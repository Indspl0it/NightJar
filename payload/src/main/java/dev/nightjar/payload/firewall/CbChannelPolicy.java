package dev.nightjar.payload.firewall;

import java.util.ArrayList;
import java.util.List;

/**
 * "Receive all channels" as pure logic.
 *
 * <p>The CB receiver app re-programs the modem table on every config event, so a one-off widening is lost. HK-18
 * therefore widens every {@code setGsmConfig} call to a single range covering {@code 0…65535} (3GPP service ids) and
 * forces {@code setActivation} on for the GSM RAN type, so the widening persists.
 *
 * <p>The framework's {@code setGsmConfig} takes {@code List<CellBroadcastIdRange>} and casts every element, so the range
 * built here must be that type: an {@code SmsBroadcastConfigInfo} would make the original throw
 * {@code ClassCastException} on the telephony thread. Everything here is fail-open: a range factory that cannot build
 * the framework object returns {@code null} and the original list is left untouched.
 *
 * <p>Thread: {@code CellBroadcastConfigTracker} handler (hook thread). One small list per call.
 */
public final class CbChannelPolicy {

    /** First 3GPP cell-broadcast service id. */
    public static final int MIN_CHANNEL = 0;

    /** Last 3GPP cell-broadcast service id. */
    public static final int MAX_CHANNEL = 0xFFFF;

    /** {@code CellBroadcastIdRange.RAN_TYPE_GSM}: the ranges {@code setGsmConfig} programs. */
    public static final int RAN_TYPE_GSM = 1;

    private CbChannelPolicy() {}

    /** Builds one range object of the framework's type, or {@code null} when the build does not have it. */
    @FunctionalInterface
    public interface RangeFactory {
        Object create(int fromServiceId, int toServiceId);
    }

    /**
     * A one-element list holding the {@code 0…65535} range, to replace the list the tracker is about to send.
     *
     * @return the widened list, or {@code null} when the factory cannot build a range (fail open: the caller keeps
     *     the original list)
     */
    public static List<Object> widen(RangeFactory factory) {
        if (factory == null) return null;
        Object range = factory.create(MIN_CHANNEL, MAX_CHANNEL);
        if (range == null) return null;
        List<Object> widened = new ArrayList<>(1);
        widened.add(range);
        return widened;
    }
}
