package io.canaryjudge.bench;

import com.fasterxml.jackson.databind.JsonNode;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.model.MetricSetPair;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Client for the Kayenta endpoints the differential test uses. The same calls work against CanaryJudge's
 * Kayenta-compatible API, which is how the compatibility of that API is checked too.
 */
public final class KayentaClient {
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public KayentaClient(String base) { this.base = base; }

    public String storeConfig(CanaryConfig config) throws IOException, InterruptedException {
        return post("/canaryConfig", CanaryConfig.MAPPER.writeValueAsString(config)).get("canaryConfigId").asText();
    }

    public String storePairs(List<MetricSetPair> pairs) throws IOException, InterruptedException {
        return post("/metricSetPairList", CanaryConfig.MAPPER.writeValueAsString(pairs)).get("metricSetPairListId").asText();
    }

    public JsonNode judge(String configId, String pairListId, double pass, double marginal) throws IOException, InterruptedException {
        return post("/judges/judge?canaryConfigId=" + configId + "&metricSetPairListId=" + pairListId
                + "&passThreshold=" + pass + "&marginalThreshold=" + marginal, "");
    }

    public JsonNode post(String path, String body) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IOException(base + path + " -> " + r.statusCode() + ": " + r.body());
        return CanaryConfig.MAPPER.readTree(r.body());
    }

    public JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IOException(base + path + " -> " + r.statusCode() + ": " + r.body());
        return CanaryConfig.MAPPER.readTree(r.body());
    }
}
