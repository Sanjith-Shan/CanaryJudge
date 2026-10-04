package io.canaryjudge.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rollout controller. It moves a canary through traffic steps (by default 1%, 5%, 25%, then 100%), with a
 * baseline of the old version taking the same share as the canary, and asks the judge as it goes:
 *
 * <ul>
 *   <li>{@code sequential}: every check interval, the sequential judge looks at everything since the analysis
 *       started (all steps so far) and the rollout is rolled back the moment it fails.</li>
 *   <li>{@code fixed}: at the end of each step, the fixed-horizon judge scores that step's window, and anything
 *       but Pass rolls back.</li>
 * </ul>
 *
 * Rolling back sends every user to the primary. The splitter counts distinct users per role from the start
 * of the rollout, which is how many users the canary reached before it was stopped.
 */
@Service
public class RolloutService {
    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JudgeService judge;
    private final String splitter;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Rollout> rollouts = new ConcurrentHashMap<>();
    private final Map<String, double[]> laneGauges = new ConcurrentHashMap<>();
    private final MeterRegistry registry;

    public RolloutService(JudgeService judge, CanaryJudgeServer.Settings settings, MeterRegistry registry) {
        this.judge = judge;
        this.splitter = settings.splitterUrl();
        this.registry = registry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(String lane, String baselineScope, String canaryScope, String config, List<Double> steps,
                          Long stepSeconds, Long warmupSeconds, Long checkSeconds, String mode, Double alpha,
                          Double pass, Double marginal) {
        Request withDefaults() {
            return new Request(lane, baselineScope, canaryScope, config == null ? "canary-config" : config,
                    steps == null ? List.of(0.01, 0.05, 0.25) : steps, stepSeconds == null ? 120L : stepSeconds,
                    warmupSeconds == null ? 30L : warmupSeconds, checkSeconds == null ? 10L : checkSeconds,
                    mode == null ? "sequential" : mode, alpha == null ? 0.05 : alpha, pass == null ? 95.0 : pass,
                    marginal == null ? 75.0 : marginal);
        }
    }

    public record Event(long timeMillis, String type, double weight, String detail) {}

    public static final class Rollout {
        public final String id;
        public final Request request;
        public volatile String status = "running";
        public volatile double weight;
        public volatile long startedMillis, analysisStartSec, endedMillis;
        public volatile String reason;
        public volatile Map<String, Object> exposure;
        public final List<Event> events = Collections.synchronizedList(new ArrayList<>());
        public volatile JudgeService.Verdict lastVerdict;

        Rollout(String id, Request request) {
            this.id = id;
            this.request = request;
        }

        void event(String type, double weight, String detail) {
            events.add(new Event(System.currentTimeMillis(), type, weight, detail));
        }
    }

    public Rollout start(Request raw) {
        Request req = raw.withDefaults();
        Rollout r = new Rollout(UUID.randomUUID().toString(), req);
        rollouts.put(r.id, r);
        laneGauges.computeIfAbsent(req.lane(), lane -> {
            double[] holder = new double[2];
            registry.gauge("canaryjudge_rollout_canary_weight", Tags.of("lane", lane), holder, h -> h[0]);
            registry.gauge("canaryjudge_rollout_state", Tags.of("lane", lane), holder, h -> h[1]);
            return holder;
        });
        Thread.startVirtualThread(() -> run(r));
        return r;
    }

    public Rollout get(String id) { return rollouts.get(id); }

    public List<Rollout> all() { return new ArrayList<>(rollouts.values()); }

    private void run(Rollout r) {
        Request q = r.request;
        long step = q.checkSeconds();
        try {
            post("/admin/lanes/" + q.lane() + "/reset");
            r.startedMillis = System.currentTimeMillis();
            r.analysisStartSec = ((r.startedMillis / 1000 + q.warmupSeconds()) / step + 1) * step;
            for (double w : q.steps()) {
                setWeight(r, w);
                long stepStart = System.currentTimeMillis() / 1000;
                long stepEnd = stepStart + q.stepSeconds();
                while (System.currentTimeMillis() / 1000 < stepEnd) {
                    Thread.sleep(step * 1000);
                    if (q.mode().equals("sequential")) {
                        JudgeService.Verdict v = judgeNow(r, r.analysisStartSec, ((System.currentTimeMillis() / 1000 - 3) / step) * step);
                        if (v != null && v.verdict().equals("fail")) {
                            rollback(r, "sequential judge failed " + v.sequentialMetric() + " after " + v.sequentialPointsToFail() + " points");
                            return;
                        }
                    }
                }
                if (q.mode().equals("fixed")) {
                    long from = Math.max(r.analysisStartSec, (stepStart / step + 1) * step);
                    Thread.sleep(4000); // let the last scrape of the step land
                    JudgeService.Verdict v = judgeNow(r, from, (stepEnd / step) * step);
                    if (v != null && !v.scoreClassification().equals("Pass")) {
                        rollback(r, "fixed-horizon judge scored " + v.score() + " (" + v.scoreClassification() + ") at " + w * 100 + "%");
                        return;
                    }
                }
            }
            setWeight(r, 1.0);
            finish(r, "promoted", "all steps passed");
        } catch (Exception e) {
            log.warn("rollout {} failed", r.id, e);
            try {
                rollback(r, "controller error: " + e.getMessage());
            } catch (Exception ignored) {
                finish(r, "error", String.valueOf(e.getMessage()));
            }
        }
    }

    private JudgeService.Verdict judgeNow(Rollout r, long startSec, long endSec) throws IOException, InterruptedException {
        Request q = r.request;
        if (endSec - startSec < q.checkSeconds()) return null;
        JudgeService.Verdict v = judge.judge(q.config(), new MetricFetcher.Scope(q.baselineScope(), ""),
                new MetricFetcher.Scope(q.canaryScope(), ""), startSec, endSec, q.checkSeconds(), q.mode(), q.alpha(),
                q.pass(), q.marginal());
        r.lastVerdict = v;
        r.event("judge", r.weight, v.verdict() + (q.mode().equals("fixed") ? " score " + v.score() : "") + " over " + v.points() + " points");
        return v;
    }

    private void setWeight(Rollout r, double w) throws IOException, InterruptedException {
        double baseline = w >= 1.0 ? 0.0 : w;
        post("/admin/lanes/" + r.request.lane() + "?canary=" + w + "&baseline=" + baseline);
        r.weight = w;
        laneGauges.get(r.request.lane())[0] = w;
        r.event("weight", w, "canary " + w * 100 + "%, baseline " + baseline * 100 + "%");
    }

    private void rollback(Rollout r, String reason) throws IOException, InterruptedException {
        post("/admin/lanes/" + r.request.lane() + "?canary=0&baseline=0");
        r.event("rollback", 0, reason);
        laneGauges.get(r.request.lane())[0] = 0;
        finish(r, "rolled_back", reason);
    }

    private void finish(Rollout r, String status, String reason) {
        r.endedMillis = System.currentTimeMillis();
        r.reason = reason;
        try {
            JsonNode stats = JSON.readTree(httpGet("/admin/lanes/" + r.request.lane()));
            Map<String, Object> e = new LinkedHashMap<>();
            long canaryUsers = stats.path("canaryUsersSinceEpoch").asLong(), allUsers = stats.path("usersSinceEpoch").asLong();
            e.put("canaryUsers", canaryUsers);
            e.put("allUsers", allUsers);
            e.put("canaryUserShare", allUsers == 0 ? 0 : canaryUsers / (double) allUsers);
            e.put("splitter", JSON.convertValue(stats, Map.class));
            r.exposure = e;
        } catch (Exception ex) {
            log.warn("could not read splitter stats", ex);
        }
        r.status = status;
        laneGauges.get(r.request.lane())[1] = status.equals("promoted") ? 1 : status.equals("rolled_back") ? -1 : 0;
        r.event(status, r.weight, reason);
        log.info("rollout {} {}: {}", r.id, status, reason);
    }

    private void post(String path) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(splitter + path))
                .POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("splitter " + resp.statusCode() + ": " + resp.body());
    }

    private String httpGet(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(splitter + path)).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
