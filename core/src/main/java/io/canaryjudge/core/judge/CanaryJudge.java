package io.canaryjudge.core.judge;

import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.model.MetricSetPair;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed-horizon judge: classifies every metric with {@link MetricClassifier}, then scores the canary
 * with Kayenta's weighted-sum rule.
 *
 * <ul>
 *   <li>A group's score is the share of its Pass metrics among those that are Pass, High, Low or
 *       NodataFailMetric. A group with none of those counts as 100.</li>
 *   <li>The summary score is the weighted sum of group scores; groups without a configured weight split
 *       whatever weight is left of 100. It is rounded to two decimals.</li>
 *   <li>Any critical metric that is not Pass sets the score to 0, as does Nodata on half or more of the
 *       metrics. Muted metrics are left out of the score but still reported.</li>
 *   <li>The score is Pass at or above the pass threshold, Marginal at or above the marginal threshold,
 *       and Fail below it.</li>
 * </ul>
 */
public final class CanaryJudge {
    public static final String NAME = "NetflixACAJudge-v1.0";
    static final double NODATA_THRESHOLD = 50;

    private final MetricClassifier classifier;

    public CanaryJudge() { this(new MetricClassifier()); }

    public CanaryJudge(MetricClassifier classifier) { this.classifier = classifier; }

    public JudgeResult judge(CanaryConfig config, double passThreshold, double marginalThreshold, List<MetricSetPair> pairs) {
        List<MetricResult> results = new ArrayList<>();
        for (MetricSetPair p : pairs) {
            CanaryConfig.MetricConfig mc = config.metric(p.name());
            MetricResult r = classifier.classify(p.name(), mc.groups(), mc.settings(), p.control(), p.experiment());
            results.add(new MetricResult(r.name(), p.id() == null ? r.id() : p.id(), r.classification(), r.classificationReason(),
                    r.groups(), p.tags() == null ? Map.of() : p.tags(), r.experimentMetadata(), r.controlMetadata(),
                    r.resultMetadata(), r.critical(), r.muted()));
        }
        return score(config, passThreshold, marginalThreshold, results);
    }

    public static JudgeResult score(CanaryConfig config, double passThreshold, double marginalThreshold, List<MetricResult> results) {
        List<MetricResult> scored = results.stream().filter(r -> !r.muted()).toList();
        Map<String, Double> weights = config.classifier() == null || config.classifier().groupWeights() == null
                ? Map.of() : config.classifier().groupWeights();

        Map<String, List<String>> labelsByGroup = new LinkedHashMap<>();
        for (MetricResult r : scored)
            for (String g : r.groups()) labelsByGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(r.classification());

        List<JudgeResult.GroupScore> groups = new ArrayList<>();
        Map<String, double[]> groupScore = new LinkedHashMap<>(); // name -> {score, noData}
        for (var e : labelsByGroup.entrySet()) {
            int pass = 0, total = 0;
            for (String l : e.getValue()) {
                if (l.equals("Pass")) pass++;
                if (l.equals("Pass") || l.equals("High") || l.equals("Low") || l.equals("NodataFailMetric")) total++;
            }
            boolean noData = total == 0;
            double s = noData ? 0.0 : pass * 100.0 / total;
            groupScore.put(e.getKey(), new double[]{s, noData ? 1 : 0});
            groups.add(new JudgeResult.GroupScore(e.getKey(), s, "", ""));
        }

        String reason = null;
        double summary;
        MetricResult firstCritical = scored.stream().filter(r -> r.critical() && !r.classification().equals("Pass")).findFirst().orElse(null);
        long nodata = scored.stream().filter(r -> r.classification().equals("Nodata")).count();
        if (firstCritical != null) {
            summary = 0.0;
            reason = "Canary Failed: " + firstCritical.classificationReason();
        } else if (!scored.isEmpty() && nodata * 100.0 / scored.size() >= NODATA_THRESHOLD) {
            summary = 0.0;
            reason = "Canary Failed: " + (int) NODATA_THRESHOLD + "% or more metrics returned NODATA";
        } else {
            double weightSum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            long unweighted = groupScore.keySet().stream().filter(g -> !weights.containsKey(g)).count();
            double calculated = unweighted > 0 ? (100 - weightSum) / unweighted : 0.0;
            double s = 0;
            for (var e : groupScore.entrySet()) {
                double effective = e.getValue()[1] == 1 ? 100.0 : e.getValue()[0];
                double w = weights.getOrDefault(e.getKey(), calculated);
                s += effective * (w / 100);
            }
            summary = BigDecimal.valueOf(s).setScale(2, RoundingMode.HALF_UP).doubleValue();
        }
        String classification = summary >= passThreshold ? "Pass" : summary >= marginalThreshold ? "Marginal" : "Fail";
        return new JudgeResult(NAME, results, groups, new JudgeResult.Score(summary, classification, reason == null ? "" : reason));
    }
}
