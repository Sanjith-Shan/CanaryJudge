package io.canaryjudge.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.model.Recording;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs live canaries. For each trial a fresh baseline and a fresh canary (the canary carrying the injected
 * regression, or nothing for an A/A trial) start together on one lane behind the splitter, take half the
 * lane's traffic each, warm up, and are measured for the analysis window. The runner then fetches every
 * metric of the canary config plus the extra series from Prometheus for both and appends one
 * {@link Recording} per trial to the output file. Lanes run in parallel; the trial order is a seeded shuffle,
 * so every condition sees the same mix of host load. Trials already in the output file are skipped, so a
 * stopped run resumes.
 */
public final class TrialRunner {
    static final ObjectMapper JSON = CanaryConfig.MAPPER;

    record Condition(String scenario, String type, double size, int repeats) {}

    record Trial(String id, Condition condition) {}

    private final JsonNode plan;
    private final CanaryConfig config;
    private final Map<String, String> extra;
    private final PromClient prom;
    private final Docker docker;
    private final String loadgen;
    private final Path out;
    private final double maxHostCpu;
    private final HttpClient http = HttpClient.newHttpClient();

    TrialRunner(JsonNode plan, CanaryConfig config, Map<String, String> extra, PromClient prom, Docker docker,
                String loadgen, Path out, double maxHostCpu) {
        this.plan = plan;
        this.config = config;
        this.extra = extra;
        this.prom = prom;
        this.docker = docker;
        this.loadgen = loadgen;
        this.out = out;
        this.maxHostCpu = maxHostCpu;
    }

    static void main(Map<String, String> a) throws Exception {
        JsonNode plan = JSON.readTree(Files.readString(Path.of(a.getOrDefault("plan", "configs/trial-plan.json"))));
        CanaryConfig config = CanaryConfig.read(Path.of(a.getOrDefault("config", "configs/canary-config.json")));
        @SuppressWarnings("unchecked")
        Map<String, String> extra = JSON.readValue(Files.readString(Path.of(a.getOrDefault("extra", "configs/recording-extra-series.json"))), LinkedHashMap.class);
        TrialRunner r = new TrialRunner(plan, config, extra,
                new PromClient(a.getOrDefault("prom", "http://localhost:19090")),
                new Docker(a.getOrDefault("image", "canaryjudge:dev"), a.getOrDefault("network", "cj-net")),
                a.getOrDefault("loadgen", "http://localhost:18001"),
                Path.of(a.getOrDefault("out", "results/trials.jsonl")),
                Double.parseDouble(a.getOrDefault("max-host-cpu", "70")));
        List<String> lanes = List.of(a.getOrDefault("lanes", "l1,l2,l3").split(","));
        int limit = Integer.parseInt(a.getOrDefault("limit", "100000"));
        r.run(lanes, limit);
    }

    List<Trial> trials() {
        List<Trial> list = new ArrayList<>();
        String prefix = plan.path("prefix").asText("t");
        List<Condition> expanded = new ArrayList<>();
        for (JsonNode c : plan.get("conditions")) {
            Condition cond = new Condition(c.get("scenario").asText(), c.get("type").asText(), c.path("size").asDouble(0), c.get("repeats").asInt());
            for (int i = 0; i < cond.repeats(); i++) expanded.add(cond);
        }
        Collections.shuffle(expanded, new Random(plan.path("seed").asLong(1)));
        for (int i = 0; i < expanded.size(); i++) list.add(new Trial(String.format("%s%03d", prefix, i + 1), expanded.get(i)));
        return list;
    }

    Set<String> done() throws IOException {
        Set<String> s = new HashSet<>();
        if (!Files.exists(out)) return s;
        for (String line : Files.readAllLines(out)) {
            if (line.isBlank()) continue;
            s.add(JSON.readTree(line).get("trial").asText());
        }
        return s;
    }

    void run(List<String> lanes, int limit) throws Exception {
        Set<String> done = done();
        ConcurrentLinkedQueue<Trial> queue = new ConcurrentLinkedQueue<>();
        int queued = 0;
        for (Trial t : trials()) {
            if (done.contains(t.id()) || queued >= limit) continue;
            queue.add(t);
            queued++;
        }
        System.out.printf("%d trials to run (%d already done) on lanes %s%n", queue.size(), done.size(), lanes);
        double rps = plan.path("laneRps").asDouble(80);
        for (String lane : lanes) post(loadgen + "/rate?lane=" + lane + "&rps=" + rps);
        List<Thread> threads = new ArrayList<>();
        int k = 0;
        for (String lane : lanes) {
            final int delay = k++ * plan.path("laneStaggerSeconds").asInt(20);
            threads.add(Thread.ofPlatform().name("lane-" + lane).start(() -> {
                try {
                    Thread.sleep(delay * 1000L);
                    Trial t;
                    while ((t = queue.poll()) != null) runOne(lane, t);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }
        for (Thread t : threads) t.join();
        for (String lane : lanes) post(loadgen + "/rate?lane=" + lane + "&rps=0");
    }

    void waitForQuietHost() throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            Double cpu = Machine.windowsCpu();
            if (cpu == null || cpu <= maxHostCpu) return;
            System.out.printf("host CPU %.0f%% > %.0f%%, waiting%n", cpu, maxHostCpu);
            Thread.sleep(30_000);
        }
    }

    void runOne(String lane, Trial t) throws Exception {
        waitForQuietHost();
        Map<String, Object> loadBefore = Machine.load();
        String bName = "cj-" + lane + "-baseline", cName = "cj-" + lane + "-canary";
        String bScope = t.id() + "-baseline", cScope = t.id() + "-canary";
        docker.remove(bName);
        docker.remove(cName);
        Map<String, String> common = Map.of("CJ_LANE", lane, "CJ_TRIAL", t.id());
        Map<String, String> bEnv = new LinkedHashMap<>(common);
        bEnv.put("CJ_SCOPE", bScope);
        bEnv.put("CJ_ROLE", "baseline");
        Map<String, String> cEnv = new LinkedHashMap<>(common);
        cEnv.put("CJ_SCOPE", cScope);
        cEnv.put("CJ_ROLE", "canary");
        cEnv.put("INJECT_TYPE", t.condition().type());
        cEnv.put("INJECT_SIZE", Double.toString(t.condition().size()));
        cEnv.put("INJECT_ONSET_S", "0");
        String heap = plan.path("heap").asText("256m"), mem = plan.path("memLimit").asText("420m");
        long launch = System.currentTimeMillis();
        Thread tb = Thread.ofVirtual().start(() -> run(() -> docker.runTarget(bName, bEnv, heap, mem)));
        Thread tc = Thread.ofVirtual().start(() -> run(() -> docker.runTarget(cName, cEnv, heap, mem)));
        tb.join();
        tc.join();
        long bReady = waitScraped(bScope), cReady = waitScraped(cScope);
        long ready = Math.max(bReady, cReady);
        long step = plan.path("stepSeconds").asLong(10);
        long warmup = plan.path("warmupSeconds").asLong(60);
        long window = plan.path("windowSeconds").asLong(360);
        long startSec = ((ready / 1000 + warmup) / step + 1) * step; // first point covers (start - step, start]
        long endSec = startSec + window - step;
        System.out.printf("[%s] %s %s %s size=%s: ready after %ds (skew %ds), window %d..%d%n", lane, t.id(),
                t.condition().scenario(), t.condition().type(), t.condition().size(),
                (ready - launch) / 1000, Math.abs(bReady - cReady) / 1000, startSec, endSec);
        sleepUntil((endSec + 4) * 1000);
        Map<String, Recording.Pair> series = new LinkedHashMap<>();
        for (CanaryConfig.MetricConfig m : config.metrics()) {
            String tpl = m.promQlTemplate();
            series.put(m.name(), new Recording.Pair(
                    prom.range(tpl.replace("${scope}", bScope), startSec, endSec, step),
                    prom.range(tpl.replace("${scope}", cScope), startSec, endSec, step)));
        }
        for (var e : extra.entrySet()) {
            series.put(e.getKey(), new Recording.Pair(
                    prom.range(e.getValue().replace("${scope}", bScope), startSec, endSec, step),
                    prom.range(e.getValue().replace("${scope}", cScope), startSec, endSec, step)));
        }
        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("type", t.condition().type());
        cond.put("size", t.condition().size());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("lane", lane);
        meta.put("baseline_scope", bScope);
        meta.put("canary_scope", cScope);
        meta.put("launch_ms", launch);
        meta.put("baseline_ready_ms", bReady);
        meta.put("canary_ready_ms", cReady);
        meta.put("warmup_s", warmup);
        meta.put("lane_rps", plan.path("laneRps").asDouble(80));
        meta.put("machine", Machine.describe());
        meta.put("load_before", loadBefore);
        meta.put("load_after", Machine.load());
        Recording rec = new Recording(t.id(), t.condition().scenario(), cond, startSec * 1000, step * 1000, series, meta);
        String line = JSON.writeValueAsString(rec) + "\n";
        synchronized (TrialRunner.class) {
            Files.writeString(out, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        if (!plan.path("keepContainers").asBoolean(false)) {
            docker.remove(bName);
            docker.remove(cName);
        }
    }

    interface IoAction { void run() throws Exception; }

    private static void run(IoAction a) {
        try {
            a.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    long waitScraped(String scope) throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            if (prom.count("process_uptime_seconds{cj_scope=\"" + scope + "\"}") > 0) return System.currentTimeMillis();
            Thread.sleep(1000);
        }
        throw new IOException("target " + scope + " never showed up in Prometheus");
    }

    static void sleepUntil(long epochMillis) throws InterruptedException {
        long d = epochMillis - System.currentTimeMillis();
        if (d > 0) Thread.sleep(d);
    }

    void post(String url) throws IOException, InterruptedException {
        http.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
    }
}
