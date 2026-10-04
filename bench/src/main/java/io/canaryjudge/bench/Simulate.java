package io.canaryjudge.bench;

import io.canaryjudge.core.sequential.SequentialTTest;
import io.canaryjudge.core.stats.MannWhitney;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Monte Carlo A/A canaries: how often each way of checking a healthy canary raises a false alarm, as a
 * function of how many times it is checked. Baseline and canary get per-interval values from the same
 * distribution (log-normal, like a latency quantile) with a shared load term, so pairing by time matters, and
 * optionally AR(1) noise, which breaks the independence the sequential t-test assumes.
 *
 * <ul>
 *   <li>{@code fixed}: one Mann-Whitney test at the horizon.</li>
 *   <li>{@code peeking}: the same test after every interval from the third on, stopping at the first rejection.</li>
 *   <li>{@code sequential}: the always-valid t-test on paired log differences, checked every interval.</li>
 *   <li>{@code sequential_batched}: the same on averages of 3 consecutive intervals (a remedy for autocorrelation).</li>
 * </ul>
 * One metric, one-sided, alpha = 0.05.
 */
public final class Simulate {
    static final double ALPHA = 0.05;

    static void main(Map<String, String> a) throws Exception {
        int runs = Integer.parseInt(a.getOrDefault("runs", "2000"));
        int maxLooks = Integer.parseInt(a.getOrDefault("looks", "144"));
        long seed = Long.parseLong(a.getOrDefault("seed", "20261004"));
        Path out = Path.of(a.getOrDefault("out", "results/sim_aa.jsonl"));
        int[] checkpoints = {6, 12, 18, 36, 72, 144};
        double[] phis = {0.0, 0.3, 0.6};
        StringBuilder sb = new StringBuilder();
        for (double phi : phis) {
            int[][] firstAlarm = new int[4][runs]; // look index of first alarm (1-based), 0 = never
            SplittableRandom rnd = new SplittableRandom(seed + (long) (phi * 1000));
            for (int run = 0; run < runs; run++) {
                double[] b = new double[maxLooks], c = new double[maxLooks];
                double nb = 0, nc = 0;
                for (int i = 0; i < maxLooks; i++) {
                    double load = 0.15 * gauss(rnd); // shared by both, like box contention
                    nb = phi * nb + Math.sqrt(1 - phi * phi) * gauss(rnd);
                    nc = phi * nc + Math.sqrt(1 - phi * phi) * gauss(rnd);
                    b[i] = 0.025 * Math.exp(load + 0.1 * nb);
                    c[i] = 0.025 * Math.exp(load + 0.1 * nc);
                }
                SequentialTTest st = new SequentialTTest(1.0), sb3 = new SequentialTTest(1.0);
                double thr = Math.log(1 / ALPHA);
                for (int i = 0; i < maxLooks; i++) {
                    int look = i + 1;
                    st.add(Math.log(c[i]) - Math.log(b[i]));
                    if (firstAlarm[2][run] == 0 && st.mean() > 0 && st.logBayesFactor() >= thr) firstAlarm[2][run] = look;
                    if (look % 3 == 0) {
                        double mb = (Math.log(b[i]) + Math.log(b[i - 1]) + Math.log(b[i - 2])) / 3;
                        double mc = (Math.log(c[i]) + Math.log(c[i - 1]) + Math.log(c[i - 2])) / 3;
                        sb3.add(mc - mb);
                        if (firstAlarm[3][run] == 0 && sb3.mean() > 0 && sb3.logBayesFactor() >= thr) firstAlarm[3][run] = look;
                    }
                    if (look >= 3 && firstAlarm[1][run] == 0) {
                        double p = MannWhitney.asymptotic(java.util.Arrays.copyOf(c, look), java.util.Arrays.copyOf(b, look),
                                MannWhitney.Alternative.GREATER, true).pValue();
                        if (p <= ALPHA) firstAlarm[1][run] = look;
                    }
                }
            }
            String[] names = {"fixed", "peeking", "sequential", "sequential_batched"};
            for (int cp : checkpoints) {
                for (int m = 1; m < names.length; m++) {
                    int hits = 0;
                    for (int run = 0; run < runs; run++) if (firstAlarm[m][run] > 0 && firstAlarm[m][run] <= cp) hits++;
                    sb.append(row(phi, names[m], cp, hits, runs, seed));
                }
            }
            // fixed horizon at each checkpoint, simulated separately so each horizon is a single look
            SplittableRandom r2 = new SplittableRandom(seed + 7 + (long) (phi * 1000));
            for (int cp : checkpoints) {
                int hits = 0;
                for (int run = 0; run < runs; run++) {
                    double[] b = new double[cp], c = new double[cp];
                    double nb = 0, nc = 0;
                    for (int i = 0; i < cp; i++) {
                        double load = 0.15 * gauss(r2);
                        nb = phi * nb + Math.sqrt(1 - phi * phi) * gauss(r2);
                        nc = phi * nc + Math.sqrt(1 - phi * phi) * gauss(r2);
                        b[i] = 0.025 * Math.exp(load + 0.1 * nb);
                        c[i] = 0.025 * Math.exp(load + 0.1 * nc);
                    }
                    if (MannWhitney.asymptotic(c, b, MannWhitney.Alternative.GREATER, true).pValue() <= ALPHA) hits++;
                }
                sb.append(row(phi, "fixed", cp, hits, runs, seed));
            }
            System.out.printf("phi=%.1f done%n", phi);
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.print(sb);
    }

    static String row(double phi, String method, int looks, int hits, int runs, long seed) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exp", "sim_aa");
        m.put("phi", phi);
        m.put("method", method);
        m.put("looks", looks);
        m.put("runs", runs);
        m.put("false_alarms", hits);
        m.put("rate", hits / (double) runs);
        m.put("rate_ci95", Evaluate.wilson(hits, runs));
        m.put("alpha", ALPHA);
        m.put("seed", seed);
        m.put("note", "synthetic A/A, one metric, one-sided; not live data");
        return TrialRunner.JSON.writeValueAsString(m) + "\n";
    }

    static double gauss(SplittableRandom r) {
        // Box-Muller
        double u = 1 - r.nextDouble(), v = r.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }
}
