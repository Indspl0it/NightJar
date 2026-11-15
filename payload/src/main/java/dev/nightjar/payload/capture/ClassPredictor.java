package dev.nightjar.payload.capture;

/**
 * Class prediction at the head filter.
 *
 * <p>HK-08 runs before the VVM, carrier, missed-call and app-token consumers, so at that point the
 * payload can only know what the PDU says: prediction is the provisional class. Which actions a predicted class may
 * get is decided only by {@code rules.EnforcementMatrix}.
 *
 * <p>Thread: any (stateless).
 */
public final class ClassPredictor {
    private ClassPredictor() {}

    /**
     * The class and subtype the head filter may assume for this message (subtype rules). The head filter
     * classifies once and passes both to the enforcer. Fails open as {@link ProvisionalClassifier#result}.
     */
    public static ProvisionalClassifier.Result predictResult(PduFacts facts, boolean fromSim, boolean injected,
            String contentType) {
        return ProvisionalClassifier.result(facts, fromSim, injected, contentType);
    }
}
