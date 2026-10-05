package io.canaryjudge.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.model.Recording;
import io.canaryjudge.core.prom.PromClient;

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
    private final Launcher docker;
    private final String loadgen;
    private final Path out;
    private final double maxHostCpu;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Integer> attempts = new java.util.concurrent.ConcurrentHashMap<>();

    TrialRunner(JsonNode plan, CanaryConfig config, Map<String, String> extra, PromClient prom, Launcher docker,
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
                Launcher.from(a),
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
        // Lanes run in lockstep batches: every lane's pair starts at the same moment, all warm up together and all
        // are measured over the same window. Staggered lanes were tried first: each lane's JVM start-up then
        // saturated the two vCPUs during the other lanes' measurement windows (BUG_LOG #4).
        while (!queue.isEmpty()) {
            waitForQuietHost();
            List<Started> batch = new ArrayList<>();
            for (String lane : lanes) {
                Trial t = queue.poll();
                if (t == null) break;
                batch.add(new Started(lane, t));
            }
            List<Thread> starters = new ArrayList<>();
            for (Started s : batch) starters.add(Thread.ofVirtual().start(() -> run(() -> start(s))));
            for (Thread th : starters) th.join();
            long step = plan.path("stepSeconds").asLong(10);
            long warmup = plan.path("warmupSeconds").asLong(90);
            long window = plan.path("windowSeconds").asLong(360);
            List<Started> ok = new ArrayList<>();
            for (Started s : batch) {
                try {
                    s.bReady = waitScraped(s.bScope());
                    s.cReady = waitScraped(s.cScope());
                    ok.add(s);
                } catch (IOException e) {
                    System.out.printf("[%s] %s did not start: %s%n", s.lane, s.trial.id(), e.getMessage());
                    requeue(queue, s.trial);
                }
            }
            long ready = ok.stream().mapToLong(s -> Math.max(s.bReady, s.cReady)).max().orElse(System.currentTimeMillis());
            long startSec = ((ready / 1000 + warmup) / step + 1) * step; // first point covers (start - step, start]
            long endSec = startSec + window - step;
            for (Started s : ok)
                System.out.printf("[%s] %s %s %s size=%s: ready after %ds (skew %ds), window %d..%d%n", s.lane, s.trial.id(),
                        s.trial.condition().scenario(), s.trial.condition().type(), s.trial.condition().size(),
                        (Math.max(s.bReady, s.cReady) - s.launch) / 1000, Math.abs(s.bReady - s.cReady) / 1000, startSec, endSec);
            sleepUntil((endSec + 4) * 1000);
            for (Started s : ok) if (!finish(s, startSec, endSec, step, warmup)) requeue(queue, s.trial);
            for (Started s : batch) {
                post(loadgen + "/rate?lane=" + s.lane + "&rps=0");
                docker.remove(s.bName());
                docker.remove(s.cName());
            }
        }
    }

    private void requeue(ConcurrentLinkedQueue<Trial> queue, Trial t) {
        int n = attempts.merge(t.id(), 1, Integer::sum);
        if (n < 3) queue.add(t);
    }

    /** A trial whose two containers have been launched. */
    static final class Started {
        final String lane;
        final Trial trial;
        long launch, bReady, cReady;
        Map<String, Object> loadBefore;

        Started(String lane, Trial trial) {
            this.lane = lane;
            this.trial = trial;
        }

        String bName() { return "cj-" + lane + "-baseline"; }

        String cName() { return "cj-" + lane + "-canary"; }

        String bScope() { return trial.id() + "-baseline"; }

        String cScope() { return trial.id() + "-canary"; }
    }

    /**
     * Waits (up to 15 minutes) while the host is busy with other jobs: Windows CPU above the limit, or less than
     * {@code minAvailableMb} of memory left in the shared WSL VM (a batch of three lanes needs about 2.2 GB).
     */
    void waitForQuietHost() throws InterruptedException {
        long minMb = plan.path("minAvailableMb").asLong(2600);
        for (int i = 0; i < 30; i++) {
            Double cpu = Machine.windowsCpu();
            long availMb = Machine.availableMb();
            boolean cpuOk = cpu == null || cpu <= maxHostCpu, memOk = availMb == 0 || availMb >= minMb;
            if (cpuOk && memOk) return;
            System.out.printf("host busy (CPU %s%%, %d MB available in WSL), waiting%n", cpu, availMb);
            Thread.sleep(30_000);
        }
    }

    /** Launches a trial's baseline and canary at the same moment and turns its lane's traffic on. */
    void start(Started s) throws Exception {
        Trial t = s.trial;
        s.loadBefore = Machine.load();
        docker.remove(s.bName());
        docker.remove(s.cName());
        Map<String, String> common = Map.of("CJ_LANE", s.lane, "CJ_TRIAL", t.id());
        Map<String, String> bEnv = new LinkedHashMap<>(common);
        bEnv.put("CJ_SCOPE", s.bScope());
        bEnv.put("CJ_ROLE", "baseline");
        Map<String, String> cEnv = new LinkedHashMap<>(common);
        cEnv.put("CJ_SCOPE", s.cScope());
        cEnv.put("CJ_ROLE", "canary");
        cEnv.put("INJECT_TYPE", t.condition().type());
        cEnv.put("INJECT_SIZE", Double.toString(t.condition().size()));
        cEnv.put("INJECT_ONSET_S", "0");
        String heap = plan.path("heap").asText("224m"), mem = plan.path("memLimit").asText("460m");
        s.launch = System.currentTimeMillis();
        Thread tb = Thread.ofVirtual().start(() -> run(() -> docker.runTarget(s.bName(), bEnv, heap, mem)));
        Thread tc = Thread.ofVirtual().start(() -> run(() -> docker.runTarget(s.cName(), cEnv, heap, mem)));
        tb.join();
        tc.join();
        post(loadgen + "/rate?lane=" + s.lane + "&rps=" + plan.path("laneRps").asDouble(80));
    }

    /** Fetches and stores a trial; returns false (and records only a reject line) if the lane did not get its traffic. */
    boolean finish(Started s, long startSec, long endSec, long step, long warmup) throws Exception {
        Trial t = s.trial;
        String lane = s.lane, bScope = s.bScope(), cScope = s.cScope();
        long launch = s.launch, bReady = s.bReady, cReady = s.cReady;
        Map<String, Object> loadBefore = s.loadBefore;
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
        String problem = trafficProblem(rec, plan.path("laneRps").asDouble(80) / 2, plan.path("minRateShare").asDouble(0.6));
        synchronized (TrialRunner.class) {
            if (problem == null) {
                Files.writeString(out, JSON.writeValueAsString(rec) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                System.out.printf("[%s] %s rejected: %s%n", lane, t.id(), problem);
                Map<String, Object> reject = new LinkedHashMap<>();
                reject.put("rejected", problem);
                reject.put("recording", rec);
                Path rejects = out.resolveSibling(out.getFileName().toString().replace(".jsonl", "_rejected.jsonl"));
                Files.writeString(rejects, JSON.writeValueAsString(reject) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }
        return problem == null;
    }

    /**
     * A run only counts if both sides got their share of traffic for the whole window: every interval's
     * request rate must be at least minShare (60% by default) of the expected per-instance rate, and at most 5% of the
     * intervals may be missing (a missed scrape).
     */
    static String trafficProblem(Recording rec, double expectedPerInstance, double minShare) {
        Recording.Pair p = rec.series().get("request_rate");
        if (p == null) return null;
        for (double[] side : new double[][]{p.control(), p.experiment()}) {
            int missing = 0;
            for (double v : side) {
                if (Double.isNaN(v)) {
                    missing++; // a missed scrape; judges drop NaN intervals, a few are tolerable
                } else if (v < minShare * expectedPerInstance) {
                    return String.format(java.util.Locale.ROOT, "request rate %.1f/s in an interval, expected about %.0f/s", v, expectedPerInstance);
                }
            }
            if (missing > Math.max(1, side.length / 20))
                return missing + " intervals without data";
        }
        return null;
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
        long deadline = System.currentTimeMillis() + 300_000;
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
