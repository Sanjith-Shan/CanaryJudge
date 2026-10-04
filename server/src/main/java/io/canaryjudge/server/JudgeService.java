package io.canaryjudge.server;

import com.fasterxml.jackson.core.type.TypeReference;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.judge.CanaryJudge;
import io.canaryjudge.core.judge.JudgeResult;
import io.canaryjudge.core.model.Recording;
import io.canaryjudge.core.sequential.SequentialJudge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Judges a live canary over a window: the fixed-horizon Mann-Whitney judge on the whole window, and the
 * sequential judge replayed over the same points. Configs are read by name from the config directory
 * ({@code <name>.json}); {@code recording-extra-series.json} there names the counters rate metrics need.
 */
@Service
public class JudgeService {
    private final MetricFetcher fetcher;
    private final Path configDir;
    private final MeterRegistry registry;
    private final Map<String, CanaryConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, Double> evidenceGauges = new ConcurrentHashMap<>();

    public JudgeService(MetricFetcher fetcher, CanaryJudgeServer.Settings settings, MeterRegistry registry) {
        this.fetcher = fetcher;
        this.configDir = Path.of(settings.configDir() == null ? "configs" : settings.configDir());
        this.registry = registry;
    }

    public CanaryConfig config(String name) {
        return configs.computeIfAbsent(name, n -> {
            try {
                return CanaryConfig.read(configDir.resolve(n + ".json"));
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read config " + n + " from " + configDir, e);
            }
        });
    }

    public Map<String, String> extraSeries() {
        Path p = configDir.resolve("recording-extra-series.json");
        if (!Files.exists(p)) return Map.of();
        try {
            return CanaryConfig.MAPPER.readValue(Files.readString(p), new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Verdict(String verdict, String mode, int points, long startSec, long endSec,
                          double score, String scoreClassification,
                          boolean sequentialFailed, String sequentialMetric, int sequentialPointsToFail,
                          double logThreshold, Map<String, Double> logEvidence, JudgeResult judgeResult, String note) {
        static Verdict pending(String mode, String note) {
            return new Verdict("pending", mode, 0, 0, 0, Double.NaN, "", false, null, -1, Double.NaN, Map.of(), null, note);
        }
    }

    public Verdict judge(String configName, MetricFetcher.Scope baseline, MetricFetcher.Scope canary,
                         long startSec, long endSec, long stepSec, String mode, double alpha,
                         double pass, double marginal) throws IOException, InterruptedException {
        CanaryConfig config = config(configName);
        if (endSec < startSec) endSec = startSec;
        Recording rec = fetcher.record(config, extraSeries(), baseline, canary, startSec, endSec, stepSec);
        int n = rec.length();
        JudgeResult fixed = new CanaryJudge().judge(config, pass, marginal, rec.pairs(config, n));
        SequentialJudge.Decision seq = new SequentialJudge(alpha).replay(config, rec, n);
        for (var e : seq.finalLogEvidence().entrySet()) gauge(canary.scope(), e.getKey(), e.getValue() / seq.logThreshold());
        String verdict = switch (mode) {
            case "fixed" -> fixed.score().classification().equals("Fail") ? "fail"
                    : fixed.score().classification().equals("Pass") ? "pass" : "marginal";
            default -> seq.failed() ? "fail" : "pass";
        };
        registry.counter("canaryjudge_judgements_total", "mode", mode, "verdict", verdict).increment();
        return new Verdict(verdict, mode, n, startSec, endSec, fixed.score().score(), fixed.score().classification(),
                seq.failed(), seq.metric(), seq.pointsToFail(), seq.logThreshold(), seq.finalLogEvidence(), fixed, null);
    }

    /** Evidence as a share of the rejection threshold (1.0 = the sequential judge fails the canary). */
    private void gauge(String scope, String metric, double share) {
        String key = scope + "|" + metric;
        if (!evidenceGauges.containsKey(key)) {
            evidenceGauges.put(key, share);
            registry.gauge("canaryjudge_evidence_share", io.micrometer.core.instrument.Tags.of("canary", scope, "metric", metric),
                    evidenceGauges, m -> m.getOrDefault(key, 0.0));
        } else {
            evidenceGauges.put(key, share);
        }
    }

    /** Seconds since the canary process started, from its own uptime gauge; NaN if it is not scraped yet. */
    public double canaryUptime(String scope) throws IOException, InterruptedException {
        return fetcher.scalar("max(process_uptime_seconds{cj_scope=\"" + scope + "\"})");
    }
}
