package android.telephony;

/**
 * Test double of the framework's {@code CellBroadcastIdRange} (API 31+), which the SDK jar :payload compiles against
 * does not ship.
 *
 * <p>Same name, constructor and validation as AOSP, so {@code CbChannelHooks} can be exercised through its reflective
 * lookup: a hook that built any other type would fail this test instead of crashing com.android.phone on the telephony
 * thread.
 */
public final class CellBroadcastIdRange {

    public static final int RAN_TYPE_GSM = 1;
    public static final int RAN_TYPE_CDMA = 2;

    private final int startId;
    private final int endId;
    private final int ranType;
    private final boolean enable;

    public CellBroadcastIdRange(int startId, int endId, int ranType, boolean enable) {
        if (startId < 0 || endId < 0 || startId > 0xFFFF || endId > 0xFFFF) {
            throw new IllegalArgumentException("range outside 0..0xFFFF");
        }
        if (ranType != RAN_TYPE_GSM && ranType != RAN_TYPE_CDMA) {
            throw new IllegalArgumentException("unknown RAN type " + ranType);
        }
        this.startId = startId;
        this.endId = endId;
        this.ranType = ranType;
        this.enable = enable;
    }

    public int getStartId() {
        return startId;
    }

    public int getEndId() {
        return endId;
    }

    public int getRanType() {
        return ranType;
    }

    public boolean isEnabled() {
        return enable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CellBroadcastIdRange other)) return false;
        return startId == other.startId && endId == other.endId && ranType == other.ranType
                && enable == other.enable;
    }

    @Override
    public int hashCode() {
        return ((startId * 31 + endId) * 31 + ranType) * 31 + (enable ? 1 : 0);
    }

    @Override
    public String toString() {
        return "CellBroadcastIdRange{" + startId + ".." + endId + " ran=" + ranType + " enabled=" + enable + "}";
    }
}
