package io.canaryjudge.core.sequential;

import io.canaryjudge.core.config.AnalysisSettings;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.model.Recording;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches a canary interval by interval and fails it as soon as any metric shows evidence of harm.
 *
 * <p>Each non-muted metric gets an always-valid test: {@link SequentialTTest} on paired per-interval values
 * (logs, for positive scale metrics such as latency), or {@link RateRatioEProcess} on event and request
 * counts. Each runs at alpha / m for m metrics, so the chance that any of them ever fires on a healthy
 * canary is at most alpha (Bonferroni over metrics, Ville's inequality over time). A metric only fails in
 * the direction its config marks as harmful.
 */
public final class SequentialJudge {
    public static final double DEFAULT_R = 1.0;

    private final double alpha;
    private final double r;

    public SequentialJudge(double alpha) { this(alpha, DEFAULT_R); }

    public SequentialJudge(double alpha, double r) {
        this.alpha = alpha;
        this.r = r;
    }

    public double alpha() { return alpha; }

    public record Decision(boolean failed, int step, String metric, double logThreshold,
                           Map<String, Double> finalLogEvidence, List<Map<String, Double>> trace) {
        /** Points consumed when the canary failed (step index + 1), or -1. */
        public int pointsToFail() { return failed ? step + 1 : -1; }
    }

    /** A live, incremental view: feed one interval at a time. */
    public final class Run {
        private final List<CanaryConfig.MetricConfig> metrics = new ArrayList<>();
        private final Map<String, SequentialTTest> tTests = new LinkedHashMap<>();
        private final Map<String, RateRatioEProcess> rates = new LinkedHashMap<>();
        private final Map<String, double[]> lastCounters = new LinkedHashMap<>();
        private final double logThreshold;
        private Map<String, Double> lastEvidence = Map.of();
        private int step = -1;
        private String failedMetric;
        private int failedStep = -1;

        Run(CanaryConfig config) {
            for (CanaryConfig.MetricConfig m : config.metrics()) {
                AnalysisSettings s = m.settings();
                if (s.muted()) continue;
                metrics.add(m);
                if ("rate".equals(s.sequential().type())) rates.put(m.name(), new RateRatioEProcess(side(s.direction())));
                else tTests.put(m.name(), new SequentialTTest(r));
            }
            logThreshold = Math.log(Math.max(1, metrics.size()) / alpha);
        }

        /**
         * One interval. {@code values} maps a metric name to {baseline, canary}; for rate metrics it must hold
         * the cumulative counters named in the metric's sequential settings, again as {baseline, canary}.
         */
        public Map<String, Double> add(Map<String, double[]> values) {
            step++;
            Map<String, Double> evidence = new LinkedHashMap<>();
            for (CanaryConfig.MetricConfig m : metrics) {
                AnalysisSettings s = m.settings();
                double logE;
                boolean harmful;
                RateRatioEProcess rate = rates.get(m.name());
                if (rate != null) {
                    double[] cnt = values.get(s.sequential().countSeries());
                    double[] tot = values.get(s.sequential().totalSeries());
                    if (cnt != null && tot != null && !anyNaN(cnt) && !anyNaN(tot)) {
                        double[] prev = lastCounters.get(m.name());
                        if (prev != null) rate.add(cnt[1] - prev[1], tot[1] - prev[3], cnt[0] - prev[0], tot[0] - prev[2]);
                        lastCounters.put(m.name(), new double[]{cnt[0], cnt[1], tot[0], tot[1]});
                    }
                    logE = rate.logE();
                    harmful = true; // the mixture only puts prior mass on harmful ratios
                } else {
                    SequentialTTest t = tTests.get(m.name());
                    double[] v = values.get(m.name());
                    if (v != null) {
                        double b = v[0], c = v[1];
                        if (s.nanStrategy() == AnalysisSettings.NanStrategy.REPLACE) {
                            if (Double.isNaN(b)) b = 0;
                            if (Double.isNaN(c)) c = 0;
                        }
                        if (!Double.isNaN(b) && !Double.isNaN(c)) {
                            if (s.sequential().logScale()) {
                                if (b > 0 && c > 0) t.add(Math.log(c) - Math.log(b));
                            } else {
                                t.add(c - b);
                            }
                        }
                    }
                    logE = t.logBayesFactor();
                    harmful = switch (s.direction()) {
                        case INCREASE -> t.mean() > 0;
                        case DECREASE -> t.mean() < 0;
                        case EITHER -> true;
                    };
                }
                evidence.put(m.name(), logE);
                if (failedMetric == null && harmful && logE >= logThreshold) {
                    failedMetric = m.name();
                    failedStep = step;
                }
            }
            lastEvidence = evidence;
            return evidence;
        }

        public boolean failed() { return failedMetric != null; }

        public String failedMetric() { return failedMetric; }

        public int failedStep() { return failedStep; }

        public double logThreshold() { return logThreshold; }

        public Map<String, Double> lastEvidence() { return lastEvidence; }
    }

    public Run start(CanaryConfig config) { return new Run(config); }

    /** Replays a recording through the first maxPoints intervals, stopping at the first failure. */
    public Decision replay(CanaryConfig config, Recording rec, int maxPoints) {
        Run run = start(config);
        List<Map<String, Double>> trace = new ArrayList<>();
        int n = Math.min(maxPoints, rec.length());
        for (int i = 0; i < n; i++) {
            Map<String, double[]> values = new LinkedHashMap<>();
            for (var e : rec.series().entrySet()) {
                Recording.Pair p = e.getValue();
                values.put(e.getKey(), new double[]{at(p.control(), i), at(p.experiment(), i)});
            }
            trace.add(run.add(values));
            if (run.failed()) break;
        }
        return new Decision(run.failed(), run.failedStep(), run.failedMetric(), run.logThreshold(), run.lastEvidence(), trace);
    }

    private static double at(double[] a, int i) { return i < a.length ? a[i] : Double.NaN; }

    private static boolean anyNaN(double[] a) {
        for (double d : a) if (Double.isNaN(d)) return true;
        return false;
    }

    private static RateRatioEProcess.Side side(AnalysisSettings.Direction d) {
        return switch (d) {
            case INCREASE -> RateRatioEProcess.Side.INCREASE;
            case DECREASE -> RateRatioEProcess.Side.DECREASE;
            case EITHER -> RateRatioEProcess.Side.EITHER;
        };
    }
}
