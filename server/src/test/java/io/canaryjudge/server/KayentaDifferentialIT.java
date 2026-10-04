package io.canaryjudge.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.judge.CanaryJudge;
import io.canaryjudge.core.judge.JudgeResult;
import io.canaryjudge.core.judge.MetricResult;
import io.canaryjudge.core.model.MetricSetPair;
import io.canaryjudge.core.model.Recording;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The differential test in CI: Kayenta's own image judges recorded canary runs (fixtures taken from
 * results/trials.jsonl) through its {@code /judges/judge} endpoint, and every per-metric classification and
 * every canary verdict must match CanaryJudge's on the same series.
 */
class KayentaDifferentialIT {
    static final String KAYENTA = "us-docker.pkg.dev/spinnaker-community/docker/kayenta:2026.0.4-slim";
    static Network network;
    static GenericContainer<?> redis, kayenta;
    static final ObjectMapper JSON = CanaryConfig.MAPPER;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        network = Network.newNetwork();
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withNetwork(network).withNetworkAliases("cj-redis");
        redis.start();
        kayenta = new GenericContainer<>(DockerImageName.parse(KAYENTA))
                .withNetwork(network)
                .withEnv("services.redis.baseUrl", "redis://cj-redis:6379")
                .withEnv("PROMETHEUS_URL", "http://localhost:9")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../deploy/kayenta/kayenta.yml")), "/opt/kayenta/config/kayenta.yml")
                .withExposedPorts(8090)
                .waitingFor(Wait.forLogMessage(".*Started Main in.*", 1).withStartupTimeout(Duration.ofMinutes(8)));
        kayenta.start();
    }

    @AfterAll
    static void stop() {
        if (kayenta != null) kayenta.stop();
        if (redis != null) redis.stop();
        if (network != null) network.close();
    }

    static JsonNode post(String path, String body) throws Exception {
        String base = "http://" + kayenta.getHost() + ":" + kayenta.getMappedPort(8090);
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body());
    }

    @Test
    void canaryJudgeAgreesWithKayentaOnRecordedRuns() throws Exception {
        CanaryConfig config = CanaryConfig.read(Path.of("../configs/canary-config.json"));
        String configId = post("/canaryConfig", JSON.writeValueAsString(config)).get("canaryConfigId").asText();
        List<Recording> recs = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/fixtures/recordings.jsonl")))
            if (!line.isBlank()) recs.add(JSON.readValue(line, Recording.class));
        assertTrue(recs.size() >= 10, "fixtures missing");
        int compared = 0;
        List<String> mismatches = new ArrayList<>();
        for (Recording rec : recs) {
            for (int points : new int[]{12, 36}) {
                List<MetricSetPair> pairs = rec.pairs(config, points);
                String listId = post("/metricSetPairList", JSON.writeValueAsString(pairs)).get("metricSetPairListId").asText();
                JsonNode k = post("/judges/judge?canaryConfigId=" + configId + "&metricSetPairListId=" + listId
                        + "&passThreshold=95&marginalThreshold=75", "");
                JudgeResult ours = new CanaryJudge().judge(config, 95, 75, pairs);
                for (JsonNode km : k.get("results")) {
                    MetricResult om = ours.result(km.get("name").asText());
                    compared++;
                    if (!km.get("classification").asText().equals(om.classification()))
                        mismatches.add(rec.trial() + "/" + points + "/" + om.name() + ": kayenta " + km.get("classification").asText() + " ours " + om.classification());
                }
                if (!k.get("score").get("classification").asText().equals(ours.score().classification()))
                    mismatches.add(rec.trial() + "/" + points + " verdict: kayenta " + k.get("score").get("classification").asText() + " ours " + ours.score().classification());
                assertEquals(k.get("score").get("score").asDouble(), ours.score().score(), 1e-9, rec.trial() + "/" + points);
            }
        }
        assertTrue(mismatches.isEmpty(), compared + " compared, mismatches: " + mismatches);
    }
}
