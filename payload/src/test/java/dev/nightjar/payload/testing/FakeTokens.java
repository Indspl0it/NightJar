package dev.nightjar.payload.testing;

import dev.nightjar.payload.capture.InjectionTokens;
import java.util.HashMap;
import java.util.Map;

/** One-shot token registry for the release and replay tests. */
public final class FakeTokens implements InjectionTokens {
    private final Map<String, Match> tokens = new HashMap<>();

    public FakeTokens add(String key, Match match) {
        tokens.put(key, match);
        return this;
    }

    @Override
    public Match consume(String tpduKey) {
        return tpduKey == null ? null : tokens.remove(tpduKey);
    }

    public int remaining() {
        return tokens.size();
    }
}
