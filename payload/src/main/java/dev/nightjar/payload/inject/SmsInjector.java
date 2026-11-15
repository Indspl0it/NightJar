package dev.nightjar.payload.inject;

/**
 * HK-R1 {@code SmsDispatchersController#injectSmsPdu(SmsMessage, String, SmsInjectionCallback, boolean ignoreClass,
 * boolean isOverIms, int token)} ("Called, not hooked").
 *
 * <p>Release, replay and canaries all go through this port. Production
 * ({@code SmsDispatchersInjector}) posts the call to the phone main looper, waits for the callback (≤ 10 s) and maps the
 * framework result to a {@link dev.nightjar.contract.Control.ReleaseResult} code; tests fake it.
 *
 * <p>Thread: control-binder thread for a release, worker for a self-test. Implementations must be synchronous.
 */
public interface SmsInjector {

    /**
     * @param radioPdu complete radio PDU (SMSC prefix included)
     * @param format {@code "3gpp"}
     * @param subId subscription the message belongs to
     * @return {@link dev.nightjar.contract.Control.ReleaseResult#HANDLED} on success
     */
    int inject(byte[] radioPdu, String format, int subId);

    /** No injection path (before the phone objects exist); every call fails with "no dispatcher". */
    SmsInjector NONE = (pdu, format, subId) -> dev.nightjar.contract.Control.ReleaseResult.NO_DISPATCHER;
}
