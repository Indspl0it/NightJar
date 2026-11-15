package dev.nightjar.payload.inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The caller's sent intent is failed with a generic failure. */
class SentFailureTest {

    private static final class RecordingSender implements SentFailure.Sender {
        final List<Object> intents = new ArrayList<>();
        final List<Integer> codes = new ArrayList<>();
        boolean throwOnSecond;

        @Override
        public void send(Object pendingIntent, int resultCode) throws Exception {
            if (throwOnSecond && !intents.isEmpty()) throw new IllegalStateException("already sent");
            intents.add(pendingIntent);
            codes.add(resultCode);
        }
    }

    @Test
    void aSingleIntentIsFailedWithGenericFailure() {
        RecordingSender sender = new RecordingSender();
        Object intent = new Object();

        assertTrue(SentFailure.fail(intent, SentFailure.RESULT_ERROR_GENERIC_FAILURE, sender));
        assertEquals(List.of(intent), sender.intents);
        assertEquals(List.of(1), sender.codes);
    }

    @Test
    void everyPartOfAMultipartSendIsFailed() {
        RecordingSender sender = new RecordingSender();
        Object[] intents = {new Object(), new Object(), new Object()};

        assertTrue(SentFailure.fail(intents, SentFailure.RESULT_ERROR_GENERIC_FAILURE, sender));
        assertEquals(3, sender.intents.size());
        assertEquals(List.of(1, 1, 1), sender.codes);
    }

    @Test
    void aListOfIntentsIsFailedToo() {
        RecordingSender sender = new RecordingSender();
        List<Object> intents = List.of(new Object(), new Object());

        assertTrue(SentFailure.fail(intents, SentFailure.RESULT_ERROR_GENERIC_FAILURE, sender));
        assertEquals(2, sender.intents.size());
    }

    @Test
    void nothingHappensWithoutAnIntent() {
        RecordingSender sender = new RecordingSender();
        assertFalse(SentFailure.fail(null, SentFailure.RESULT_ERROR_GENERIC_FAILURE, sender));
        assertTrue(sender.intents.isEmpty());
    }

    @Test
    void aSenderThatThrowsIsReportedNotPropagated() {
        SentFailure.Sender sender =
                (intent, code) -> {
                    throw new SecurityException("not allowed");
                };
        assertFalse(SentFailure.fail(new Object(), SentFailure.RESULT_ERROR_GENERIC_FAILURE, sender));
    }

    @Test
    void oneFailingIntentDoesNotHideTheOthers() {
        RecordingSender sender = new RecordingSender();
        sender.throwOnSecond = true;
        Object[] intents = {new Object(), new Object()};

        // The first succeeded, so the call reports success even though the second threw.
        assertTrue(SentFailure.fail(intents, SentFailure.RESULT_ERROR_GENERIC_FAILURE, sender));
        assertEquals(1, sender.intents.size());
    }

    @Test
    void theDefaultSenderIsReflectiveAndFailsOpenOnTheJvmStub() {
        // On the JVM the framework class is a stub: the call must return false, never throw.
        assertFalse(SentFailure.fail(new Object()));
    }
}
