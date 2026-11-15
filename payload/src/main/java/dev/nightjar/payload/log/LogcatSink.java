package dev.nightjar.payload.log;

import android.util.Log;

/** {@link LogSink} over logcat, tag {@code Nightjar}. Thread: any. */
public final class LogcatSink implements LogSink {
    public static final String TAG = "Nightjar";

    @Override
    public void write(String level, String tag, String line) {
        if ("E".equals(level)) {
            Log.e(TAG, line);
        } else {
            Log.w(TAG, line);
        }
    }
}
