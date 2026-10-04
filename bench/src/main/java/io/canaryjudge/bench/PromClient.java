package io.canaryjudge.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/** Minimal Prometheus HTTP API client: range queries returned as a dense array on the step grid. */
public final class PromClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public PromClient(String base) { this.base = base; }

    /**
     * Values at start, start + step, ..., end (seconds), NaN where the query returned nothing. A query that
     * returns several series is an error: canary queries must aggregate to one.
     */
    public double[] range(String query, long startSec, long endSec, long stepSec) throws IOException, InterruptedException {
        String url = base + "/api/v1/query_range?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&start=" + startSec + "&end=" + endSec + "&step=" + stepSec;
        JsonNode root = get(url);
        int n = (int) ((endSec - startSec) / stepSec) + 1;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        JsonNode result = root.path("data").path("result");
        if (result.size() > 1) throw new IOException("query returned " + result.size() + " series: " + query);
        for (JsonNode series : result) {
            for (JsonNode v : series.path("values")) {
                long t = Math.round(v.get(0).asDouble());
                int i = (int) ((t - startSec) / stepSec);
                if (i >= 0 && i < n) out[i] = parse(v.get(1).asText());
            }
        }
        return out;
    }

    /** Number of series an instant query returns now. */
    public int count(String query) throws IOException, InterruptedException {
        JsonNode root = get(base + "/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        return root.path("data").path("result").size();
    }

    public double scalar(String query) throws IOException, InterruptedException {
        JsonNode r = get(base + "/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)).path("data").path("result");
        if (r.isEmpty()) return Double.NaN;
        return parse(r.get(0).path("value").get(1).asText());
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("prometheus " + r.statusCode() + ": " + r.body());
        return JSON.readTree(r.body());
    }

    static double parse(String s) {
        return switch (s) {
            case "NaN" -> Double.NaN;
            case "+Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(s);
        };
    }
}
