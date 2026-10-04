package io.canaryjudge.bench;

import com.fasterxml.jackson.databind.JsonNode;
import io.canaryjudge.core.prom.PromClient;

import java.io.IOException;
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
 * exp5: live rollouts. On a rollout lane the primary (old version) serves everyone; for each run a fresh
 * baseline and a fresh canary start together, then the CanaryJudge server's rollout controller moves the
 * canary through 1%, 5% and 25% of users with the baseline at the same share, judging as it goes, and either
 * promotes it or rolls it back. Users are assigned by a hash of their id, so the share of users the canary
 * reached is counted exactly by the splitter. One row per rollout.
 */
public final class RolloutRunner {
    record Run(String id, String scenario, String type, double size, String mode) {}

    static void main(Map<String, String> a) throws Exception {
        JsonNode plan = TrialRunner.JSON.readTree(Files.readString(Path.of(a.getOrDefault("plan", "configs/rollout-plan.json"))));
        Path out = Path.of(a.getOrDefault("out", "results/exp5.jsonl"));
        String server = a.getOrDefault("server", "http://localhost:18090");
        String loadgen = a.getOrDefault("loadgen", "http://localhost:18001");
        PromClient prom = new PromClient(a.getOrDefault("prom", "http://localhost:19090"));
        Docker docker = new Docker(a.getOrDefault("image", "canaryjudge:dev"), "cj-net");
        List<String> lanes = List.of(a.getOrDefault("lanes", "r1,r2").split(","));
        KayentaClient http = new KayentaClient(server);

        List<Run> runs = new ArrayList<>();
        for (String mode : List.of("sequential", "fixed")) {
            for (JsonNode c : plan.get("conditions")) {
                int repeats = c.path(mode + "Repeats").asInt(0);
                for (int i = 0; i < repeats; i++)
                    runs.add(new Run(null, c.get("scenario").asText(), c.get("type").asText(), c.path("size").asDouble(), mode));
            }
        }
        Collections.shuffle(runs, new Random(plan.path("seed").asLong(1)));
        Set<String> done = new HashSet<>();
        if (Files.exists(out)) for (String l : Files.readAllLines(out)) if (!l.isBlank()) done.add(TrialRunner.JSON.readTree(l).get("run").asText());
        ConcurrentLinkedQueue<Run> queue = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            String id = String.format("ro%03d", i + 1);
            if (!done.contains(id)) queue.add(new Run(id, r.scenario(), r.type(), r.size(), r.mode()));
        }
        System.out.printf("%d rollouts to run on lanes %s%n", queue.size(), lanes);
        double rps = plan.path("laneRps").asDouble(200);
        List<Thread> threads = new ArrayList<>();
        for (String lane : lanes) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    String primary = "cj-" + lane + "-primary";
                    docker.remove(primary);
                    docker.runTarget(primary, Map.of("CJ_SCOPE", lane + "-primary", "CJ_ROLE", "primary", "CJ_LANE", lane, "CJ_TRIAL", "primary"),
                            plan.path("primaryHeap").asText("320m"), plan.path("primaryMemLimit").asText("520m"));
                    waitScraped(prom, lane + "-primary");
                    post(loadgen + "/rate?lane=" + lane + "&rps=" + rps);
                    Run r;
                    while ((r = queue.poll()) != null) runOne(plan, lane, r, docker, prom, http, out);
                    post(loadgen + "/rate?lane=" + lane + "&rps=0");
                    docker.remove("cj-" + lane + "-primary");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
            Thread.sleep(plan.path("laneStaggerSeconds").asLong(60) * 1000);
        }
        for (Thread t : threads) t.join();
    }

    static void runOne(JsonNode plan, String lane, Run r, Docker docker, PromClient prom, KayentaClient server, Path out) throws Exception {
        String bName = "cj-" + lane + "-baseline", cName = "cj-" + lane + "-canary";
        String bScope = r.id() + "-baseline", cScope = r.id() + "-canary";
        docker.remove(bName);
        docker.remove(cName);
        Map<String, Object> loadBefore = Machine.load();
        String heap = plan.path("heap").asText("256m"), mem = plan.path("memLimit").asText("420m");
        Thread tb = Thread.ofVirtual().start(() -> unchecked(() -> docker.runTarget(bName,
                Map.of("CJ_SCOPE", bScope, "CJ_ROLE", "baseline", "CJ_LANE", lane, "CJ_TRIAL", r.id()), heap, mem)));
        Thread tc = Thread.ofVirtual().start(() -> unchecked(() -> docker.runTarget(cName,
                Map.of("CJ_SCOPE", cScope, "CJ_ROLE", "canary", "CJ_LANE", lane, "CJ_TRIAL", r.id(),
                        "INJECT_TYPE", r.type(), "INJECT_SIZE", Double.toString(r.size()), "INJECT_ONSET_S", "0"), heap, mem)));
        tb.join();
        tc.join();
        waitScraped(prom, bScope);
        waitScraped(prom, cScope);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("lane", lane);
        req.put("baselineScope", bScope);
        req.put("canaryScope", cScope);
        req.put("config", "canary-config");
        req.put("steps", TrialRunner.JSON.convertValue(plan.get("steps"), List.class));
        req.put("stepSeconds", plan.path("stepSeconds").asLong(120));
        req.put("warmupSeconds", plan.path("warmupSeconds").asLong(30));
        req.put("checkSeconds", 10);
        req.put("mode", r.mode());
        JsonNode started = server.post("/api/v1/rollouts", TrialRunner.JSON.writeValueAsString(req));
        String rid = started.get("id").asText();
        long t0 = System.currentTimeMillis();
        JsonNode state;
        do {
            Thread.sleep(5000);
            state = server.get("/api/v1/rollouts/" + rid);
        } while (state.get("status").asText().equals("running") && System.currentTimeMillis() - t0 < 30 * 60_000);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("exp", "exp5");
        row.put("run", r.id());
        row.put("scenario", r.scenario());
        row.put("condition", Map.of("type", r.type(), "size", r.size()));
        row.put("mode", r.mode());
        row.put("lane", lane);
        row.put("status", state.get("status").asText());
        row.put("reason", state.path("reason").asText());
        row.put("seconds", (state.get("endedMillis").asLong() - state.get("startedMillis").asLong()) / 1000.0);
        row.put("weight_at_end", state.get("weight").asDouble());
        JsonNode exp = state.path("exposure");
        row.put("canary_users", exp.path("canaryUsers").asLong());
        row.put("all_users", exp.path("allUsers").asLong());
        row.put("canary_user_share", exp.path("canaryUserShare").asDouble());
        JsonNode sp = exp.path("splitter");
        row.put("canary_requests_total", sp.path("canaryRequests").asLong());
        row.put("events", TrialRunner.JSON.convertValue(state.get("events"), List.class));
        row.put("steps", req.get("steps"));
        row.put("step_seconds", req.get("stepSeconds"));
        row.put("machine", Machine.describe());
        row.put("load_before", loadBefore);
        row.put("load_after", Machine.load());
        row.put("note", "splitter counters are cumulative per lane; canary_user_share counts distinct users from the rollout start, the exposure measure");
        synchronized (RolloutRunner.class) {
            Files.writeString(out, TrialRunner.JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        System.out.printf("[%s] %s %s %s: %s after %.0fs, %d of %d users (%.2f%%) reached the canary%n", lane, r.id(), r.mode(), r.scenario(),
                row.get("status"), (Double) row.get("seconds"), exp.path("canaryUsers").asLong(), exp.path("allUsers").asLong(),
                100 * exp.path("canaryUserShare").asDouble());
        docker.remove(bName);
        docker.remove(cName);
    }

    interface IoAction { void run() throws Exception; }

    static void unchecked(IoAction a) {
        try {
            a.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void waitScraped(PromClient prom, String scope) throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            if (prom.count("process_uptime_seconds{cj_scope=\"" + scope + "\"}") > 0) return;
            Thread.sleep(1000);
        }
        throw new IOException(scope + " never showed up in Prometheus");
    }

    static void post(String url) throws Exception {
        java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
    }
}
