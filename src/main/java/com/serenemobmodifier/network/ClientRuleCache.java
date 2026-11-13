package com.serenemobmodifier.network;

import java.util.List;

/**
 * Holds the rule list most recently received from the server. Plain data only (no client classes), so it is
 * safe to reference from common code. {@code null} means "nothing received" (e.g. not connected, or the
 * server did not send one); the GUI then falls back to the local config.
 */
public final class ClientRuleCache {

    private static volatile List<String> rules;

    private ClientRuleCache() {}

    public static void set(List<String> newRules) {
        rules = List.copyOf(newRules);
    }

    public static List<String> get() {
        return rules;
    }

    public static void clear() {
        rules = null;
    }
}
