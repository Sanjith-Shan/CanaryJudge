package io.canaryjudge.bench;

import io.canaryjudge.core.baseline.FixedHorizonTest;
import io.canaryjudge.core.baseline.StaticThresholds;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.judge.CanaryJudge;
import io.canaryjudge.core.judge.JudgeResult;
import io.canaryjudge.core.model.Recording;
import io.canaryjudge.core.sequential.SequentialJudge;
import io.canaryjudge.core.stats.Descriptive;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * exp1 to exp3 from the recorded runs. Every run is replayed through every judge on the same points:
 *
 * <ul>
 *   <li>{@code kayenta_style}: the fixed-horizon Mann-Whitney judge with Kayenta's semantics, once, on the
 *       whole window. The canary is caught when the verdict is not Pass (the final score must reach the pass
 *       threshold to promote).</li>
 *   <li>{@code fixed_mw}: one-sided Mann-Whitney per metric at alpha / m, once on the whole window.</li>
 *   <li>{@code sequential}: the always-valid judge, checked after every interval.</li>
 *   <li>{@code peeking_mw}: {@code fixed_mw} re-run after every interval (naive repeated checks).</li>
 *   <li>{@code peeking_kayenta}: the Kayenta-style judge re-run after every interval, failing on the first Fail.</li>
 *   <li>{@code static_flagger}: fixed limits with Flagger's documented defaults (success rate at least 99%,
 *       p99 at most 500 ms), rollback after 5 failed checks.</li>
 *   <li>{@code static_tuned}: fixed limits calibrated on separate healthy runs (worst healthy value + 10%) on
 *       p99 latency, error rate, CPU and heap, rollback after 5 failed checks.</li>
 * </ul>
 */
public final class Evaluate {
    static final double ALPHA = 0.05;

    static void main(Map<String, String> a) throws Exception {
        CanaryConfig config = CanaryConfig.read(Path.of(a.getOrDefault("config", "configs/canary-config.json")));
        List<Recording> all = Recordings.read(Path.of(a.getOrDefault("trials", "results/trials.jsonl")));
        Path outDir = Path.of(a.getOrDefault("out-dir", "results"));
        int points = Integer.parseInt(a.getOrDefault("points", "36"));
        double r = Double.parseDouble(a.getOrDefault("r", Double.toString(SequentialJudge.DEFAULT_R)));
        List<Recording> calibration = all.stream().filter(x -> x.scenario().equals("calibration")).toList();
        List<Recording> recs = all.stream().filter(x -> !x.scenario().equals("calibration")).toList();
        StaticThresholds flagger = new StaticThresholds("static_flagger", Map.of("latency_p99", 0.5, "error_rate", 0.01), Map.of(), 5);
        StaticThresholds tuned = tuned(calibration, points);
        Files.writeString(outDir.resolve("static_tuned_thresholds.json"), TrialRunner.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("calibration_trials", calibration.stream().map(Recording::trial).toList(), "thresholds", tuned)));

        SequentialJudge seq = new SequentialJudge(ALPHA, r);
        FixedHorizonTest mw = new FixedHorizonTest(ALPHA);
        CanaryJudge kayenta = new CanaryJudge();
        double minutesPerPoint = recs.isEmpty() ? 1.0 / 6 : recs.get(0).stepMillis() / 60000.0;

        StringBuilder judgements = new StringBuilder();
        Map<String, List<Map<String, Object>>> byScenario = new TreeMap<>();
        for (Recording rec : recs) {
            int n = Math.min(points, rec.length());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("exp", "judgements");
            row.put("trial", rec.trial());
            row.put("scenario", rec.scenario());
            row.put("condition", rec.condition());
            row.put("points", n);
            row.put("minutes", n * minutesPerPoint);
            JudgeResult kr = kayenta.judge(config, 95, 75, rec.pairs(config, n));
            row.put("kayenta_style", outcome(!kr.score().classification().equals("Pass"), n, minutesPerPoint,
                    Map.of("score", kr.score().score(), "classification", kr.score().classification(),
                            "failed_metrics", kr.results().stream().filter(m -> !m.classification().equals("Pass")).map(m -> m.name() + ":" + m.classification()).toList())));
            FixedHorizonTest.Result fm = mw.test(config, rec, n);
            row.put("fixed_mw", outcome(fm.failed(), n, minutesPerPoint, Map.of("min_p", fm.pValue(), "metric", String.valueOf(fm.metric()))));
            SequentialJudge.Decision sd = seq.replay(config, rec, n);
            row.put("sequential", outcome(sd.failed(), sd.pointsToFail(), minutesPerPoint,
                    Map.of("metric", String.valueOf(sd.metric()), "final_log_evidence", sd.finalLogEvidence(), "log_threshold", sd.logThreshold())));
            int peek = mw.peek(config, rec, 3, n);
            row.put("peeking_mw", outcome(peek > 0, peek, minutesPerPoint, Map.of()));
            int pk = peekKayenta(kayenta, config, rec, 6, n);
            row.put("peeking_kayenta", outcome(pk > 0, pk, minutesPerPoint, Map.of()));
            int sf = flagger.evaluate(rec, n);
            row.put("static_flagger", outcome(sf > 0, sf, minutesPerPoint, Map.of()));
            int st = tuned.evaluate(rec, n);
            row.put("static_tuned", outcome(st > 0, st, minutesPerPoint, Map.of()));
            row.put("lag1_autocorrelation", lag1(config, rec, n));
            row.put("load_before", rec.meta().get("load_before"));
            row.put("load_after", rec.meta().get("load_after"));
            judgements.append(TrialRunner.JSON.writeValueAsString(row)).append('\n');
            byScenario.computeIfAbsent(rec.scenario(), k -> new ArrayList<>()).add(row);
        }
        Files.writeString(outDir.resolve("judgements.jsonl"), judgements.toString(), StandardCharsets.UTF_8);

        String[] judges = {"kayenta_style", "fixed_mw", "sequential", "peeking_mw", "peeking_kayenta", "static_flagger", "static_tuned"};
        StringBuilder exp1 = new StringBuilder(), exp2 = new StringBuilder(), exp3 = new StringBuilder();
        Map<String, Object> machine = Machine.describe();
        for (var e : byScenario.entrySet()) {
            List<Map<String, Object>> rows = e.getValue();
            boolean aa = e.getKey().equals("aa");
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("exp", aa ? "exp2" : "exp1");
            s.put("scenario", e.getKey());
            s.put("condition", rows.get(0).get("condition"));
            s.put("runs", rows.size());
            s.put("window_minutes", points * minutesPerPoint);
            s.put("alpha", ALPHA);
            for (String j : judges) {
                long hits = rows.stream().filter(x -> (Boolean) ((Map<?, ?>) x.get(j)).get("failed")).count();
                Map<String, Object> js = new LinkedHashMap<>();
                js.put("failed", hits);
                js.put("rate", hits / (double) rows.size());
                double[] ci = wilson(hits, rows.size());
                js.put("rate_ci95", ci);
                double[] mins = rows.stream().map(x -> (Map<?, ?>) x.get(j)).filter(x -> (Boolean) x.get("failed"))
                        .mapToDouble(x -> ((Number) x.get("minutes_to_fail")).doubleValue()).sorted().toArray();
                js.put("median_minutes_to_fail", mins.length == 0 ? null : Descriptive.percentile(mins, 50));
                js.put("p90_minutes_to_fail", mins.length == 0 ? null : Descriptive.percentile(mins, 90));
                s.put(j, js);
            }
            s.put("machine", machine);
            s.put("load_range", loadRange(rows));
            String line = TrialRunner.JSON.writeValueAsString(s) + "\n";
            if (aa) {
                Map<String, Object> ac = new LinkedHashMap<>();
                for (Map<String, Object> x : rows) {
                    @SuppressWarnings("unchecked") Map<String, Double> l = (Map<String, Double>) x.get("lag1_autocorrelation");
                    l.forEach((k, v) -> ac.merge(k, v / rows.size(), (p, q) -> (Double) p + (Double) q));
                }
                s.put("mean_lag1_autocorrelation_of_differences", ac);
                exp2.append(TrialRunner.JSON.writeValueAsString(s)).append('\n');
            } else {
                exp1.append(line);
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("exp", "exp3");
                t.put("scenario", e.getKey());
                t.put("condition", rows.get(0).get("condition"));
                t.put("runs", rows.size());
                t.put("fixed_horizon_minutes", points * minutesPerPoint);
                t.put("fixed_horizon_kayenta_style", s.get("kayenta_style"));
                t.put("fixed_horizon_mw", s.get("fixed_mw"));
                t.put("sequential", s.get("sequential"));
                t.put("machine", machine);
                exp3.append(TrialRunner.JSON.writeValueAsString(t)).append('\n');
            }
        }
        Files.writeString(outDir.resolve("exp1.jsonl"), exp1.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("exp2.jsonl"), exp2.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("exp3.jsonl"), exp3.toString(), StandardCharsets.UTF_8);
        System.out.printf("evaluated %d runs (%d calibration runs set the tuned thresholds)%n", recs.size(), calibration.size());
        System.out.print(exp2);
    }

    static Map<String, Object> outcome(boolean failed, int pointsToFail, double minutesPerPoint, Map<String, Object> detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("failed", failed);
        m.put("points_to_fail", failed ? pointsToFail : null);
        m.put("minutes_to_fail", failed ? pointsToFail * minutesPerPoint : null);
        m.putAll(detail);
        return m;
    }

    static int peekKayenta(CanaryJudge judge, CanaryConfig config, Recording rec, int min, int n) {
        for (int k = min; k <= n; k++)
            if (judge.judge(config, 95, 75, rec.pairs(config, k)).score().classification().equals("Fail")) return k;
        return -1;
    }

    /** Lag-1 autocorrelation of the paired differences per metric; the sequential test assumes zero. */
    static Map<String, Double> lag1(CanaryConfig config, Recording rec, int n) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (CanaryConfig.MetricConfig m : config.metrics()) {
            Recording.Pair p = rec.get(m.name()).prefix(n);
            double[] d = new double[p.control().length];
            int k = 0;
            for (int i = 0; i < d.length; i++) {
                double c = p.control()[i], e = p.experiment()[i];
                if (!Double.isNaN(c) && !Double.isNaN(e)) d[k++] = e - c;
            }
            d = Arrays.copyOf(d, k);
            if (k < 4) continue;
            double mean = Descriptive.mean(d), num = 0, den = 0;
            for (int i = 0; i < k; i++) {
                den += (d[i] - mean) * (d[i] - mean);
                if (i > 0) num += (d[i] - mean) * (d[i - 1] - mean);
            }
            out.put(m.name(), den == 0 ? 0 : num / den);
        }
        return out;
    }

    /** Thresholds a team would pick from healthy runs: the worst value seen, plus 10%. */
    static StaticThresholds tuned(List<Recording> healthy, int points) {
        Map<String, Double> max = new LinkedHashMap<>();
        for (String metric : List.of("latency_p99", "error_rate", "cpu", "heap_after_gc")) {
            double worst = 0;
            for (Recording r : healthy) {
                Recording.Pair p = r.get(metric).prefix(points);
                for (double v : p.control()) if (!Double.isNaN(v)) worst = Math.max(worst, v);
                for (double v : p.experiment()) if (!Double.isNaN(v)) worst = Math.max(worst, v);
            }
            max.put(metric, worst * 1.1);
        }
        if (max.get("error_rate") < 0.01) max.put("error_rate", 0.01);
        return new StaticThresholds("static_tuned", max, Map.of(), 5);
    }

    static double[] wilson(long k, long n) {
        if (n == 0) return new double[]{0, 1};
        double z = 1.959963984540054, p = k / (double) n;
        double denom = 1 + z * z / n, centre = (p + z * z / (2 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom;
        return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadRange(List<Map<String, Object>> rows) {
        double lo = Double.MAX_VALUE, hi = 0, wlo = Double.MAX_VALUE, whi = 0;
        for (Map<String, Object> r : rows) {
            for (String k : List.of("load_before", "load_after")) {
                Map<String, Object> l = (Map<String, Object>) r.get(k);
                if (l == null) continue;
                Object h = l.get("windows_host_cpu_pct"), w = l.get("wsl_load1");
                if (h instanceof Number x) { lo = Math.min(lo, x.doubleValue()); hi = Math.max(hi, x.doubleValue()); }
                if (w instanceof Number x) { wlo = Math.min(wlo, x.doubleValue()); whi = Math.max(whi, x.doubleValue()); }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("windows_host_cpu_pct", lo == Double.MAX_VALUE ? null : new double[]{lo, hi});
        m.put("wsl_load1", wlo == Double.MAX_VALUE ? null : new double[]{wlo, whi});
        return m;
    }
}
