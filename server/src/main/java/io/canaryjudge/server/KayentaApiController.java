package io.canaryjudge.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.judge.CanaryJudge;
import io.canaryjudge.core.judge.JudgeResult;
import io.canaryjudge.core.model.MetricSetPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The parts of Kayenta's REST API a canary pipeline uses, with the same paths and JSON shapes, so a client
 * written against Kayenta can point at CanaryJudge instead:
 *
 * <pre>
 *   POST /canaryConfig                      store a canary config            -> {canaryConfigId}
 *   GET  /canaryConfig[/{id}]               list or read configs
 *   POST /metricSetPairList                 store metric series              -> {metricSetPairListId}
 *   GET  /metricSetPairList/{id}
 *   POST /judges/judge?canaryConfigId&amp;metricSetPairListId&amp;passThreshold&amp;marginalThreshold
 *                                           judge stored series directly     -> CanaryJudgeResult
 *   POST /canary[/{canaryConfigId}]         fetch from Prometheus and judge  -> {canaryExecutionId}
 *   GET  /canary/{canaryExecutionId}        execution status and result
 * </pre>
 *
 * Storage is in memory, like Kayenta's in-memory store. Account-name parameters are accepted and ignored.
 */
@RestController
public class KayentaApiController {
    private static final Logger log = LoggerFactory.getLogger(KayentaApiController.class);

    private final Map<String, CanaryConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, List<MetricSetPair>> pairLists = new ConcurrentHashMap<>();
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();
    private final MetricFetcher fetcher;
    private final CanaryJudge judge = new CanaryJudge();

    public KayentaApiController(MetricFetcher fetcher) { this.fetcher = fetcher; }

    @PostMapping("/canaryConfig")
    public Map<String, String> storeConfig(@RequestBody CanaryConfig config) {
        String id = config.id() != null && !config.id().isBlank() ? config.id() : UUID.randomUUID().toString();
        configs.put(id, config.withId(id));
        return Map.of("canaryConfigId", id);
    }

    @GetMapping("/canaryConfig/{id}")
    public CanaryConfig getConfig(@PathVariable String id) {
        CanaryConfig c = configs.get(id);
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no canary config " + id);
        return c;
    }

    @GetMapping("/canaryConfig")
    public List<Map<String, Object>> listConfigs() {
        return configs.values().stream().map(c -> Map.<String, Object>of("id", c.id(), "name", c.name() == null ? "" : c.name())).toList();
    }

    @DeleteMapping("/canaryConfig/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable String id) {
        configs.remove(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/metricSetPairList")
    public Map<String, String> storePairs(@RequestBody List<MetricSetPair> pairs) {
        String id = UUID.randomUUID().toString();
        pairLists.put(id, pairs);
        return Map.of("metricSetPairListId", id);
    }

    @GetMapping("/metricSetPairList/{id}")
    public List<MetricSetPair> getPairs(@PathVariable String id) {
        List<MetricSetPair> p = pairLists.get(id);
        if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no metric set pair list " + id);
        return p;
    }

    @PostMapping("/judges/judge")
    public JudgeResult judge(@RequestParam String canaryConfigId, @RequestParam String metricSetPairListId,
                             @RequestParam double passThreshold, @RequestParam double marginalThreshold) {
        return judge.judge(getConfig(canaryConfigId), passThreshold, marginalThreshold, getPairs(metricSetPairListId));
    }

    // ---- /canary: fetch from Prometheus, then judge ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScopeSpec(String scope, String location, Instant start, Instant end, Long step,
                            Map<String, String> extendedScopeParams) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScopePair(ScopeSpec controlScope, ScopeSpec experimentScope) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Thresholds(double pass, double marginal) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecutionRequest(Map<String, ScopePair> scopes, Thresholds thresholds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdhocRequest(CanaryConfig canaryConfig, ExecutionRequest executionRequest) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Execution(String id, boolean complete, String status, String canaryConfigId,
                            String metricSetPairListId, Map<String, Object> result, String exception,
                            ExecutionRequest canaryExecutionRequest, Long startTimeMillis, Long endTimeMillis) {}

    @PostMapping("/canary")
    public Map<String, String> adhoc(@RequestBody AdhocRequest req) {
        String configId = storeConfig(req.canaryConfig()).get("canaryConfigId");
        return start(configId, req.executionRequest());
    }

    @PostMapping("/canary/{canaryConfigId}")
    public Map<String, String> canary(@PathVariable String canaryConfigId, @RequestBody ExecutionRequest req) {
        getConfig(canaryConfigId);
        return start(canaryConfigId, req);
    }

    private Map<String, String> start(String configId, ExecutionRequest req) {
        String id = UUID.randomUUID().toString();
        long t0 = System.currentTimeMillis();
        executions.put(id, new Execution(id, false, "running", configId, null, null, null, req, t0, null));
        Thread.startVirtualThread(() -> {
            try {
                CanaryConfig config = getConfig(configId);
                ScopePair sp = req.scopes().getOrDefault("default", req.scopes().values().iterator().next());
                ScopeSpec c = sp.controlScope(), e = sp.experimentScope();
                long step = c.step() == null ? 60 : c.step();
                List<MetricSetPair> pairs = fetcher.fetch(config, new MetricFetcher.Scope(c.scope(), c.location()),
                        new MetricFetcher.Scope(e.scope(), e.location()), c.start().getEpochSecond(), c.end().getEpochSecond(), step);
                String listId = storePairs(pairs).get("metricSetPairListId");
                Thresholds th = req.thresholds() == null ? new Thresholds(95, 75) : req.thresholds();
                JudgeResult r = judge.judge(config, th.pass(), th.marginal(), pairs);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("judgeResult", r);
                result.put("canaryDuration", null);
                executions.put(id, new Execution(id, true, "succeeded", configId, listId, result, null, req, t0, System.currentTimeMillis()));
            } catch (Exception ex) {
                log.warn("canary execution {} failed", id, ex);
                executions.put(id, new Execution(id, true, "terminal", configId, null, null, String.valueOf(ex.getMessage()), req, t0, System.currentTimeMillis()));
            }
        });
        return Map.of("canaryExecutionId", id);
    }

    @GetMapping("/canary/{id}")
    public Execution execution(@PathVariable String id) {
        Execution e = executions.get(id);
        if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no canary execution " + id);
        return e;
    }
}
