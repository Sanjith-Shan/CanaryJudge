package io.canaryjudge.core.judge;

import io.canaryjudge.core.config.AnalysisSettings;
import io.canaryjudge.core.config.AnalysisSettings.Direction;
import io.canaryjudge.core.config.AnalysisSettings.NanStrategy;
import io.canaryjudge.core.stats.Descriptive;
import io.canaryjudge.core.stats.MannWhitney;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Classifies one metric as Pass, High, Low or Nodata with the Mann-Whitney rule Kayenta's documented
 * judge (NetflixACAJudge-v1.0) applies:
 *
 * <ol>
 *   <li>NaNs are removed or replaced by zero; outliers are optionally removed with an IQR fence.</li>
 *   <li>No data on either side is Nodata (or a pass when NaNs are replaced, or NodataFailMetric when the
 *       metric must have data). Identical samples, or a single distinct value overall, pass.</li>
 *   <li>A 98% confidence interval for the shift canary - baseline comes from inverting the rank-sum
 *       test. The canary is High when the interval's lower end exceeds a tolerance band of 25% of the
 *       shift estimate and the effect size (ratio of means, or CLES) reaches {@code allowedIncrease};
 *       Low is the mirror image. The direction setting decides which of the two can fail.</li>
 *   <li>A critical metric that is High or Low beyond its critical effect size fails the canary outright.</li>
 * </ol>
 */
public final class MetricClassifier {
    public static final double TOLERANCE = 0.25;
    public static final double CONFIDENCE = 0.98;

    private final double tolerance;
    private final double confidence;

    public MetricClassifier() { this(TOLERANCE, CONFIDENCE); }

    public MetricClassifier(double tolerance, double confidence) {
        this.tolerance = tolerance;
        this.confidence = confidence;
    }

    /** The outcome before it is wrapped in a result record. */
    public record Outcome(Classification classification, String reason, double deviation, boolean critical,
                          double effectSize, MannWhitney.Interval interval) {}

    public MetricResult classify(String name, java.util.List<String> groups, AnalysisSettings s,
                                 double[] controlRaw, double[] experimentRaw) {
        double[] control = transform(controlRaw, s);
        double[] experiment = transform(experimentRaw, s);
        Map<String, Object> resultMeta = new LinkedHashMap<>();
        Outcome o;
        try {
            o = classify(name, s, control, experiment);
            resultMeta.put("ratio", o.deviation());
            if (o.interval() != null) {
                resultMeta.put("lowerConfidence", o.interval().lower());
                resultMeta.put("upperConfidence", o.interval().upper());
                resultMeta.put("estimate", o.interval().estimate());
                resultMeta.put("effectSize", o.effectSize());
            }
        } catch (RuntimeException e) {
            o = new Outcome(Classification.Error, "Metric Classification Failed", Double.NaN, false, Double.NaN, null);
        }
        return new MetricResult(name, name, o.classification().name(), o.reason(), groups, Map.of(),
                Map.of("stats", Descriptive.summary(experiment).toMap()),
                Map.of("stats", Descriptive.summary(control).toMap()),
                resultMeta, o.critical(), s.muted());
    }

    public static double[] transform(double[] values, AnalysisSettings s) {
        double[] v = s.nanStrategy() == NanStrategy.REMOVE
                ? Arrays.stream(values).filter(x -> !Double.isNaN(x)).toArray()
                : Arrays.stream(values).map(x -> Double.isNaN(x) ? 0.0 : x).toArray();
        if (s.removeOutliers() && v.length > 0) v = Descriptive.removeOutliers(v, s.outlierFactor());
        return v;
    }

    public Outcome classify(String name, AnalysisSettings s, double[] control, double[] experiment) {
        if (experiment.length == 0 || control.length == 0) {
            if (s.nanStrategy() == NanStrategy.REMOVE) {
                String reason = "Missing data for " + name;
                if (s.mustHaveData() && !s.critical())
                    return new Outcome(Classification.NodataFailMetric, reason, 1.0, false, Double.NaN, null);
                return new Outcome(Classification.Nodata, reason, 1.0, s.critical(), Double.NaN, null);
            }
            return new Outcome(Classification.Pass, null, 1.0, false, Double.NaN, null);
        }
        double[] cs = control.clone(), es = experiment.clone();
        Arrays.sort(cs);
        Arrays.sort(es);
        if (Arrays.equals(cs, es))
            return new Outcome(Classification.Pass, "The Canary and Baseline data are identical", 1.0, false, Double.NaN, null);
        if (cs[0] == cs[cs.length - 1] && es[0] == es[es.length - 1] && cs[0] == es[0])
            return new Outcome(Classification.Pass, null, 1.0, false, Double.NaN, null);

        double deviation = Descriptive.meanRatio(control, experiment);
        double effect = "cles".equals(s.effectSizeMeasure()) ? MannWhitney.cles(experiment, control) : deviation;
        MannWhitney.Interval ci;
        if (cs[0] == cs[cs.length - 1] && es[0] == es[es.length - 1]) {
            // Both samples constant but different. Kayenta adds tiny Gaussian noise here so the rank test can
            // run; the samples are then completely separated, so the interval is the difference itself.
            double diff = es[0] - cs[0];
            ci = new MannWhitney.Interval(diff, diff, diff);
        } else {
            ci = MannWhitney.shiftInterval(experiment, control, confidence);
        }
        double bound = tolerance * Math.abs(ci.estimate());
        boolean high = (s.direction() == Direction.INCREASE || s.direction() == Direction.EITHER)
                && ci.lower() > bound
                && (Double.isNaN(effect) || effect >= s.allowedIncrease());
        boolean low = (s.direction() == Direction.DECREASE || s.direction() == Direction.EITHER)
                && ci.upper() < -bound
                && (Double.isNaN(effect) || effect <= s.allowedDecrease());
        Classification c = high ? Classification.High : low ? Classification.Low : Classification.Pass;
        String reason = c == Classification.Pass ? null : name + " was classified as " + c;
        boolean criticalHigh = s.critical() && c == Classification.High
                && (Double.isNaN(effect) || effect >= s.criticalIncrease());
        boolean criticalLow = s.critical() && c == Classification.Low
                && (Double.isNaN(effect) || effect <= s.criticalDecrease());
        if (criticalHigh || criticalLow)
            reason = "The metric " + name + " was classified as " + c + " (Critical)";
        return new Outcome(c, reason, deviation, criticalHigh || criticalLow, effect, ci);
    }
}
