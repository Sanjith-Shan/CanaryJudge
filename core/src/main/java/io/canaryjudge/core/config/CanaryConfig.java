package io.canaryjudge.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.json.JsonReadFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A canary configuration in Kayenta's JSON format (see Kayenta's docs/canary-config.md). Unknown fields
 * are ignored, so a config written for Kayenta loads unchanged. Per-metric judge settings stay as the raw
 * {@code analysisConfigurations} object and are read through {@link AnalysisSettings}, which applies
 * Kayenta's defaults.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanaryConfig(
        String id,
        String name,
        String description,
        List<String> applications,
        Judge judge,
        List<MetricConfig> metrics,
        Map<String, String> templates,
        Classifier classifier,
        Long createdTimestamp,
        Long updatedTimestamp,
        String configVersion) {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .build();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Judge(String name, Map<String, Object> judgeConfigurations) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Classifier(Map<String, Double> groupWeights) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricConfig(
            String name,
            JsonNode query,
            List<String> groups,
            JsonNode analysisConfigurations,
            String scopeName) {

        public AnalysisSettings settings() {
            return AnalysisSettings.from(analysisConfigurations);
        }

        /** The PromQL template of a Prometheus query with {@code customInlineTemplate: "PromQL:..."}, or null. */
        public String promQlTemplate() {
            if (query == null) return null;
            JsonNode t = query.get("customInlineTemplate");
            if (t == null || !t.isTextual()) return null;
            String s = t.asText();
            return s.startsWith("PromQL:") ? s.substring(7) : null;
        }
    }

    public MetricConfig metric(String metricName) {
        for (MetricConfig m : metrics) if (m.name().equals(metricName)) return m;
        throw new IllegalArgumentException("Could not find metric config for " + metricName);
    }

    public static CanaryConfig read(Path path) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(path), CanaryConfig.class);
    }

    public static CanaryConfig parse(String json) throws IOException {
        return MAPPER.readValue(json, CanaryConfig.class);
    }

    /** The same config under a new id and name (Kayenta refuses a second config with an existing name). */
    public CanaryConfig renamed(String suffix) {
        return new CanaryConfig(id + suffix, name + suffix, description, applications, judge, metrics, templates, classifier,
                createdTimestamp, updatedTimestamp, configVersion);
    }

    public CanaryConfig withId(String newId) {
        return new CanaryConfig(newId, name, description, applications, judge, metrics, templates, classifier,
                createdTimestamp, updatedTimestamp, configVersion);
    }
}
