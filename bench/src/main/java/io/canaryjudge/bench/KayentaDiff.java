package io.canaryjudge.bench;

import com.fasterxml.jackson.databind.JsonNode;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.judge.CanaryJudge;
import io.canaryjudge.core.judge.JudgeResult;
import io.canaryjudge.core.model.MetricSetPair;
import io.canaryjudge.core.model.Recording;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * exp4: the differential test. Every recorded canary run is judged twice on exactly the same series: by
 * Kayenta itself (its {@code /judges/judge} endpoint, Kayenta's own image) and by CanaryJudge (in process, or
 * through CanaryJudge's Kayenta-compatible API with {@code --canaryjudge URL}). Each run is judged over several
 * window lengths, so short and long windows are both covered. One row per run and window, then a summary row.
 */
public final class KayentaDiff {
    static void main(Map<String, String> a) throws Exception {
        CanaryConfig config = CanaryConfig.read(Path.of(a.getOrDefault("config", "configs/canary-config.json")));
        KayentaClient kayenta = new KayentaClient(a.getOrDefault("kayenta", "http://localhost:18091"));
        String cjUrl = a.get("canaryjudge");
        KayentaClient cjApi = cjUrl == null ? null : new KayentaClient(cjUrl);
        Path out = Path.of(a.getOrDefault("out", "results/exp4.jsonl"));
        int[] windows = java.util.Arrays.stream(a.getOrDefault("windows", "12,24,36").split(",")).mapToInt(Integer::parseInt).toArray();
        double pass = 95, marginal = 75;
        // a fresh id per run, so a second run against the same Kayenta does not collide with the first
        config = config.renamed("-" + Long.toString(System.currentTimeMillis(), 36));
        String kConfigId = kayenta.storeConfig(config);
        String cjConfigId = cjApi == null ? null : cjApi.storeConfig(config);
        List<Recording> recs = Recordings.read(Path.of(a.getOrDefault("trials", "results/trials.jsonl")));
        String kayentaVersion = a.getOrDefault("kayenta-version", "2026.0.4");
        int metricsCompared = 0, metricsAgree = 0, verdictsAgree = 0, judgements = 0;
        double maxScoreDiff = 0;
        List<String> disagreements = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (Recording rec : recs) {
            for (int w : windows) {
                if (w > rec.length()) continue;
                List<MetricSetPair> pairs = rec.pairs(config, w);
                JsonNode k = kayenta.judge(kConfigId, kayenta.storePairs(pairs), pass, marginal);
                JsonNode ours;
                if (cjApi != null) {
                    ours = cjApi.judge(cjConfigId, cjApi.storePairs(pairs), pass, marginal);
                } else {
                    JudgeResult r = new CanaryJudge().judge(config, pass, marginal, pairs);
                    ours = CanaryConfig.MAPPER.valueToTree(r);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("exp", "exp4");
                row.put("trial", rec.trial());
                row.put("scenario", rec.scenario());
                row.put("points", w);
                List<Map<String, Object>> metrics = new ArrayList<>();
                for (JsonNode km : k.get("results")) {
                    String name = km.get("name").asText();
                    JsonNode om = find(ours.get("results"), name);
                    String kc = km.get("classification").asText(), oc = om == null ? "missing" : om.get("classification").asText();
                    boolean agree = kc.equals(oc);
                    metricsCompared++;
                    if (agree) metricsAgree++;
                    else disagreements.add(rec.trial() + "/" + w + "/" + name + ": kayenta " + kc + " canaryjudge " + oc);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("metric", name);
                    m.put("kayenta", kc);
                    m.put("canaryjudge", oc);
                    m.put("agree", agree);
                    m.put("kayenta_ratio", km.path("resultMetadata").path("ratio").asDouble(Double.NaN));
                    m.put("canaryjudge_ratio", om == null ? Double.NaN : om.path("resultMetadata").path("ratio").asDouble(Double.NaN));
                    metrics.add(m);
                }
                double ks = k.path("score").path("score").asDouble(), os = ours.path("score").path("score").asDouble();
                String kv = k.path("score").path("classification").asText(), ov = ours.path("score").path("classification").asText();
                maxScoreDiff = Math.max(maxScoreDiff, Math.abs(ks - os));
                judgements++;
                if (kv.equals(ov)) verdictsAgree++;
                row.put("metrics", metrics);
                row.put("kayenta_score", ks);
                row.put("canaryjudge_score", os);
                row.put("score_diff", Math.abs(ks - os));
                row.put("kayenta_verdict", kv);
                row.put("canaryjudge_verdict", ov);
                row.put("canaryjudge_via", cjApi == null ? "in-process" : "kayenta-compatible API");
                sb.append(TrialRunner.JSON.writeValueAsString(row)).append('\n');
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("exp", "exp4_summary");
        summary.put("ts", java.time.OffsetDateTime.now().toString());
        summary.put("kayenta_image", "us-docker.pkg.dev/spinnaker-community/docker/kayenta:" + kayentaVersion + "-slim");
        summary.put("runs", recs.size());
        summary.put("windows", windows);
        summary.put("judgements", judgements);
        summary.put("metric_verdicts_compared", metricsCompared);
        summary.put("metric_verdicts_agree", metricsAgree);
        summary.put("canary_verdicts_agree", verdictsAgree);
        summary.put("max_score_diff", maxScoreDiff);
        summary.put("disagreements", disagreements);
        summary.put("canaryjudge_via", cjApi == null ? "in-process" : "kayenta-compatible API");
        summary.put("machine", Machine.describe());
        sb.append(TrialRunner.JSON.writeValueAsString(summary)).append('\n');
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.printf("exp4: %d of %d metric verdicts agree, %d of %d canary verdicts, max score diff %.2f%n",
                metricsAgree, metricsCompared, verdictsAgree, judgements, maxScoreDiff);
        disagreements.stream().limit(20).forEach(d -> System.out.println("  " + d));
    }

    static JsonNode find(JsonNode results, String name) {
        if (results == null) return null;
        for (JsonNode r : results) if (r.get("name").asText().equals(name)) return r;
        return null;
    }
}
