package io.canaryjudge.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * The traffic splitter. Requests arrive as {@code /{lane}/path?user=N}; the lane's weights pick primary,
 * baseline or canary by user, and the request is proxied to {@code http://cj-{lane}-{role}:8080/path}
 * (pattern configurable). Admin endpoints set weights and read per-role request, error and distinct-user
 * counts, which is how the rollout experiment measures how many users a bad canary reached.
 *
 * <pre>
 *   POST /admin/lanes/{lane}?canary=0.05&amp;baseline=0.05   set weights (creates the lane; mode=request to split per request)
 *   POST /admin/lanes/{lane}/reset                         start a new distinct-user epoch
 *   GET  /admin/lanes/{lane}                               stats as JSON
 *   GET  /metrics                                          Prometheus text format
 * </pre>
 */
public final class Splitter {
    private static final ObjectMapper JSON = new ObjectMapper();

    static final int MAX_IN_FLIGHT = 2000;

    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore inFlight = new java.util.concurrent.Semaphore(MAX_IN_FLIGHT);
    private final java.util.concurrent.atomic.LongAdder shed = new java.util.concurrent.atomic.LongAdder();
    private final String hostPattern;
    private final HttpClient client;

    Splitter(String hostPattern) {
        this.hostPattern = hostPattern;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    static void run(Args a) throws IOException {
        int port = a.getInt("port", 8000);
        Splitter s = new Splitter(a.get("host-pattern", "http://cj-%s-%s:8080"));
        for (String spec : a.get("lanes", "").split(",")) {
            if (spec.isBlank()) continue;
            String[] p = spec.split(":");
            s.lanes.put(p[0], new Lane(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), p.length < 4 || !p[3].equals("request")));
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 1024);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", s::handle);
        server.start();
        System.out.println("splitter listening on " + port + " lanes " + s.lanes.keySet());
    }

    void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/metrics")) {
                StringBuilder sb = new StringBuilder();
                lanes.values().forEach(l -> l.prometheus(sb));
                Runtime rt = Runtime.getRuntime();
                sb.append("cj_splitter_in_flight ").append(MAX_IN_FLIGHT - inFlight.availablePermits()).append('\n');
                sb.append("cj_splitter_shed_total ").append(shed.sum()).append('\n');
                sb.append("cj_splitter_heap_used_bytes ").append(rt.totalMemory() - rt.freeMemory()).append('\n');
                reply(ex, 200, sb.toString(), "text/plain; version=0.0.4");
            } else if (path.equals("/health")) {
                reply(ex, 200, "{\"status\":\"UP\"}", "application/json");
            } else if (path.startsWith("/admin/lanes/")) {
                admin(ex, path.substring("/admin/lanes/".length()));
            } else if (inFlight.tryAcquire()) {
                try {
                    proxy(ex, path);
                } finally {
                    inFlight.release();
                }
            } else {
                shed.increment(); // never queue without bound: an overloaded proxy answers 503 at once
                reply(ex, 503, "{\"error\":\"splitter overloaded\"}", "application/json");
            }
        } catch (IllegalArgumentException e) {
            reply(ex, 400, "{\"error\":\"" + e.getMessage() + "\"}", "application/json");
        } finally {
            ex.close();
        }
    }

    private void admin(HttpExchange ex, String rest) throws IOException {
        String[] parts = rest.split("/");
        String lane = parts[0];
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        if (ex.getRequestMethod().equals("POST") && parts.length == 1) {
            double canary = Double.parseDouble(q.getOrDefault("canary", "0"));
            double baseline = Double.parseDouble(q.getOrDefault("baseline", q.getOrDefault("canary", "0")));
            Lane l = lanes.computeIfAbsent(lane, n -> new Lane(n, 0, 0, !"request".equals(q.get("mode"))));
            l.setWeights(canary, baseline);
            reply(ex, 200, JSON.writeValueAsString(l.stats()), "application/json");
        } else if (ex.getRequestMethod().equals("POST") && parts.length == 2 && parts[1].equals("reset")) {
            Lane l = lanes.get(lane);
            if (l == null) throw new IllegalArgumentException("no lane " + lane);
            l.resetUsers();
            reply(ex, 200, JSON.writeValueAsString(l.stats()), "application/json");
        } else {
            Lane l = lanes.get(lane);
            if (l == null) throw new IllegalArgumentException("no lane " + lane);
            reply(ex, 200, JSON.writeValueAsString(l.stats()), "application/json");
        }
    }

    private void proxy(HttpExchange ex, String path) throws IOException {
        int slash = path.indexOf('/', 1);
        if (slash < 0) throw new IllegalArgumentException("path must be /{lane}/...");
        String laneName = path.substring(1, slash);
        Lane lane = lanes.get(laneName);
        if (lane == null) throw new IllegalArgumentException("no lane " + laneName);
        String rawQuery = ex.getRequestURI().getRawQuery();
        long user = Long.parseLong(query(rawQuery).getOrDefault("user", "0"));
        String role = lane.route(user);
        String url = String.format(hostPattern, laneName, role) + path.substring(slash) + (rawQuery == null ? "" : "?" + rawQuery);
        int status;
        byte[] body;
        try {
            HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            status = r.statusCode();
            body = r.body();
        } catch (IOException | InterruptedException e) {
            status = 599; // upstream unreachable, kept apart from the service's own 5xx
            body = ("{\"error\":\"upstream " + role + " unreachable\"}").getBytes(StandardCharsets.UTF_8);
        }
        lane.record(role, user, status);
        ex.getResponseHeaders().set("X-Canary-Role", role);
        ex.sendResponseHeaders(status == 599 ? 502 : status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new java.util.HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void reply(HttpExchange ex, int status, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
