package io.canaryjudge.core.config;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The judge settings of one metric, read from {@code analysisConfigurations.canary} with the defaults
 * Kayenta documents: direction {@code either}, NaN strategy {@code remove}, outliers kept (factor 3),
 * effect size measured as the ratio of means with every threshold at 1 (0.5 for CLES).
 *
 * <p>CanaryJudge's own sequential settings live beside Kayenta's, under
 * {@code analysisConfigurations.canaryjudge}, which Kayenta ignores.
 */
public record AnalysisSettings(
        Direction direction,
        NanStrategy nanStrategy,
        boolean removeOutliers,
        double outlierFactor,
        boolean critical,
        boolean mustHaveData,
        boolean muted,
        String effectSizeMeasure,
        double allowedIncrease,
        double allowedDecrease,
        double criticalIncrease,
        double criticalDecrease,
        Sequential sequential) {

    public enum Direction { INCREASE, DECREASE, EITHER }

    public enum NanStrategy { REMOVE, REPLACE }

    /**
     * How the sequential judge treats this metric. {@code mean} tests paired per-interval values with a
     * sequential t-test; {@code rate} tests event counts against request counts with a rate-ratio e-process,
     * reading the cumulative counters named by {@code countSeries} and {@code totalSeries}. {@code margin} is a
     * shift the canary may have before it counts as harm (a ratio for log-scale metrics, so 1.25 tolerates +25%,
     * matching an {@code effectSize.allowedIncrease} of 1.25); the test then asks whether the shift exceeds it.
     */
    public record Sequential(String type, String countSeries, String totalSeries, boolean logScale, double margin) {}

    public static AnalysisSettings from(JsonNode analysisConfigurations) {
        JsonNode c = analysisConfigurations == null ? null : analysisConfigurations.get("canary");
        Direction direction = switch (text(c, "either", "direction")) {
            case "increase" -> Direction.INCREASE;
            case "decrease" -> Direction.DECREASE;
            default -> Direction.EITHER;
        };
        NanStrategy nan = "replace".equals(text(c, "remove", "nanStrategy")) ? NanStrategy.REPLACE : NanStrategy.REMOVE;
        boolean removeOutliers = "remove".equals(text(c, "keep", "outliers", "strategy"));
        double outlierFactor = number(c, 3.0, "outliers", "outlierFactor");
        String measure = text(c, "meanRatio", "effectSize", "measure");
        double def = "cles".equals(measure) ? 0.5 : 1.0;
        JsonNode cj = analysisConfigurations == null ? null : analysisConfigurations.get("canaryjudge");
        Sequential seq = new Sequential(
                text(cj, "mean", "sequential", "type"),
                text(cj, null, "sequential", "countSeries"),
                text(cj, null, "sequential", "totalSeries"),
                bool(cj, false, "sequential", "logScale"),
                number(cj, 0.0, "sequential", "margin"));
        return new AnalysisSettings(direction, nan, removeOutliers, outlierFactor,
                bool(c, false, "critical"), bool(c, false, "mustHaveData"), bool(c, false, "muted"),
                measure,
                number(c, def, "effectSize", "allowedIncrease"),
                number(c, def, "effectSize", "allowedDecrease"),
                number(c, def, "effectSize", "criticalIncrease"),
                number(c, def, "effectSize", "criticalDecrease"),
                seq);
    }

    private static JsonNode at(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            if (n == null || n.isNull() || !n.isObject()) return null;
            n = n.get(p);
        }
        return n == null || n.isNull() ? null : n;
    }

    private static String text(JsonNode node, String def, String... path) {
        JsonNode n = at(node, path);
        return n == null ? def : n.asText();
    }

    private static double number(JsonNode node, double def, String... path) {
        JsonNode n = at(node, path);
        if (n == null) return def;
        return n.isNumber() ? n.asDouble() : Double.parseDouble(n.asText());
    }

    private static boolean bool(JsonNode node, boolean def, String... path) {
        JsonNode n = at(node, path);
        if (n == null) return def;
        return n.isBoolean() ? n.asBoolean() : Boolean.parseBoolean(n.asText());
    }
}
