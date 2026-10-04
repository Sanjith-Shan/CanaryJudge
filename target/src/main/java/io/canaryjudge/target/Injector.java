package io.canaryjudge.target;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The regression injector. One regression at a time, with a size and an onset (seconds after start):
 *
 * <ul>
 *   <li>{@code latency}: the simulated dependency call takes (1 + size) times as long, so size 0.1 is +10%.</li>
 *   <li>{@code errors}: an extra share of requests fails with HTTP 500; size 0.02 is 2% of requests.</li>
 *   <li>{@code leak}: every request keeps size bytes reachable forever.</li>
 *   <li>{@code cpu}: every request burns size milliseconds of CPU.</li>
 * </ul>
 *
 * Configured from the environment at start ({@code INJECT_TYPE}, {@code INJECT_SIZE}, {@code INJECT_ONSET_S})
 * or changed at run time through {@code POST /admin/inject}.
 */
@Component
public class Injector {
    public enum Type { NONE, LATENCY, ERRORS, LEAK, CPU }

    private final long startNanos = System.nanoTime();
    private volatile Type type;
    private volatile double size;
    private volatile double onsetSeconds;
    private final List<byte[]> leaked = new ArrayList<>();
    private long leakedBytes;

    public Injector(MeterRegistry registry) {
        set(env("INJECT_TYPE", "none"), Double.parseDouble(env("INJECT_SIZE", "0")), Double.parseDouble(env("INJECT_ONSET_S", "0")));
        Gauge.builder("cj_injected_size", this, i -> i.active() ? i.size : 0).tag("type", "any").register(registry);
        Gauge.builder("cj_leaked_bytes", this, i -> i.leakedBytes()).register(registry);
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    public synchronized void set(String type, double size, double onsetSeconds) {
        this.type = Type.valueOf(type.toUpperCase(Locale.ROOT));
        this.size = size;
        this.onsetSeconds = onsetSeconds;
    }

    public boolean active() {
        return type != Type.NONE && (System.nanoTime() - startNanos) / 1e9 >= onsetSeconds;
    }

    public boolean active(Type t) {
        return type == t && active();
    }

    public double latencyMultiplier() {
        return active(Type.LATENCY) ? 1 + size : 1.0;
    }

    public double extraErrorRate() {
        return active(Type.ERRORS) ? size : 0.0;
    }

    public void maybeLeak() {
        if (!active(Type.LEAK)) return;
        int n = (int) size;
        synchronized (leaked) {
            leaked.add(new byte[n]);
            leakedBytes += n;
        }
    }

    public void maybeBurn() {
        if (!active(Type.CPU)) return;
        long until = System.nanoTime() + (long) (size * 1_000_000);
        double x = 1.0;
        while (System.nanoTime() < until) {
            for (int i = 0; i < 200; i++) x = Math.sqrt(x + i) * 1.0000001;
        }
        if (x == 42) System.out.print(""); // keep the loop from being optimized away
    }

    public long leakedBytes() {
        synchronized (leaked) {
            return leakedBytes;
        }
    }

    public String describe() {
        return String.format(Locale.ROOT, "%s size=%s onset=%ss active=%s", type.name().toLowerCase(Locale.ROOT), size, onsetSeconds, active());
    }
}
