package com.android.internal.telephony;

import android.os.Handler;

/**
 * Stub. Base of {@code InboundSmsHandler} on both reference builds.
 *
 * <p>The class lives in {@code com.android.internal.telephony}, not
 * {@code com.android.internal.util} — {@code com.android.internal.util.StateMachine} is a different
 * type on the device, so declaring the stub in the wrong package made the hierarchy depend on how
 * javac qualified a call and would have failed verification the moment a hook body assigned the
 * handler to a {@code StateMachine} variable.
 */
public class StateMachine {
    public final Handler getHandler() { throw new RuntimeException("stub"); }
}
