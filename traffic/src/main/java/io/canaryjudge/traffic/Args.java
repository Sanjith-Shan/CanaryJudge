package io.canaryjudge.traffic;

import java.util.HashMap;
import java.util.Map;

/** {@code --key value} pairs with environment-variable fallbacks ({@code --lane-rps} reads {@code LANE_RPS}). */
public record Args(Map<String, String> values) {
    public static Args parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("expected --key, got " + args[i]);
            String k = args[i].substring(2);
            String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            m.put(k, v);
        }
        return new Args(m);
    }

    public String get(String key, String def) {
        String v = values.get(key);
        if (v != null) return v;
        String env = System.getenv(key.toUpperCase().replace('-', '_'));
        return env != null && !env.isBlank() ? env : def;
    }

    public int getInt(String key, int def) { return Integer.parseInt(get(key, Integer.toString(def))); }

    public long getLong(String key, long def) { return Long.parseLong(get(key, Long.toString(def))); }

    public double getDouble(String key, double def) { return Double.parseDouble(get(key, Double.toString(def))); }
}
