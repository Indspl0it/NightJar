package dev.nightjar.payload.firewall;

/**
 * What the head filter needs from the phone framework (HK-08, HK-R3, HK-13b).
 *
 * <p>Split out so {@link SmsFilterCore} runs in JVM tests: {@link NjSmsFilter} is the only class that touches
 * {@code com.android.internal.telephony}.
 *
 * <p>Thread: main looper.
 */
public interface SmsFilterHost {

    /**
     * HK-R3 {@code dropFilteredSms(tracker, receiver, blocked)}: deletes the message from the raw table and finishes it
     * without delivery. The ACK was already sent by the radio layer, so this never changes it.
     */
    void dropFilteredSms();

    /** Publishes the verdict (the correlator turns it into the {@code fw.headFilter} stage). */
    void publishVerdict(VerdictRecord record, long tNs);

    /** Publishes a stage without a verdict. */
    void publishStage(dev.nightjar.contract.TraceStage stage, String detail, long tNs);

    /** Counts an error so the hook registry can degrade the tier. */
    void onError(Throwable t);
}
