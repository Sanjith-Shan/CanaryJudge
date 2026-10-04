package io.canaryjudge.core.judge;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The verdict on one metric, shaped like Kayenta's CanaryAnalysisResult so clients can read either.
 * {@code resultMetadata.ratio} is the ratio of means (canary over baseline); CanaryJudge adds the
 * confidence interval and estimate it used.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricResult(
        String name,
        String id,
        String classification,
        String classificationReason,
        List<String> groups,
        Map<String, String> tags,
        Map<String, Object> experimentMetadata,
        Map<String, Object> controlMetadata,
        Map<String, Object> resultMetadata,
        boolean critical,
        boolean muted) {
}
