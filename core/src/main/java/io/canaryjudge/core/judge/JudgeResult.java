package io.canaryjudge.core.judge;

import java.util.List;

/** The judge's answer for one canary interval, shaped like Kayenta's CanaryJudgeResult. */
public record JudgeResult(
        String judgeName,
        List<MetricResult> results,
        List<GroupScore> groupScores,
        Score score) {

    public record GroupScore(String name, double score, String classification, String classificationReason) {}

    public record Score(double score, String classification, String classificationReason) {}

    public MetricResult result(String metricName) {
        for (MetricResult r : results) if (r.name().equals(metricName)) return r;
        return null;
    }
}
