package io.canaryjudge.traffic;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load generator. Each lane gets a Poisson arrival process at its own rate; every request names a
 * user drawn from a Zipf distribution over a fixed population (a few users send most requests, as on real
 * sites) and an item drawn uniformly. Everything comes from a seeded generator per lane, so a run replays
 * the same sequence of users, items and gaps.
 *
 * <p>With {@code --trace file}, the rate follows a recorded shape: one relative rate per line (mean about 1),
 * each held for {@code --trace-step-s} seconds of wall time and cycled. That replays the time-of-day shape
 * of a public request trace instead of a flat rate.
 *
 * <p>Control on {@code --control-port}: {@code POST /rate?lane=l1&rps=40} changes or adds a lane,
 * {@code GET /stats} reports sent, failed and in-flight counts.
 */
public final class LoadGen {
    static final int MAX_IN_FLIGHT_PER_LANE = 1000;
    private final String target;
    /** False when the target is a service, not the splitter: then requests go to /api/... without the lane. */
    volatile boolean lanePrefix = true;
    private final HttpClient client;
    private final double[] zipfCdf;
    private final double[] trace;
    private final double traceStepSeconds;
    private final long startNanos = System.nanoTime();
    private final Map<String, LaneLoad> lanes = new ConcurrentHashMap<>();
    private final long seed;

    final class LaneLoad implements Runnable {
        final String name;
        volatile double rps;
        final LongAdder sent = new LongAdder(), failed = new LongAdder(), inFlight = new LongAdder(), dropped = new LongAdder();
        final SplittableRandom rnd;
        volatile boolean stopped;

        LaneLoad(String name, double rps, long seed) {
            this.name = name;
            this.rps = rps;
            this.rnd = new SplittableRandom(seed);
        }

        @Override
        public void run() {
            long next = System.nanoTime();
            while (!stopped) {
                double rate = rps * traceMultiplier();
                if (rate <= 0) {
                    LockSupport.parkNanos(100_000_000L);
                    next = System.nanoTime();
                    continue;
                }
                double gap = -Math.log(1 - rnd.nextDouble()) / rate;
                next += (long) (gap * 1e9);
                long user = zipf(rnd.nextDouble());
                long item = 1 + rnd.nextInt(10_000);
                long wait = next - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                else if (wait < -1_000_000_000L) next = System.nanoTime(); // fell behind by > 1 s: do not burst
                if (inFlight.sum() >= MAX_IN_FLIGHT_PER_LANE) {
                    dropped.increment(); // the splitter is stuck: drop rather than pile up requests
                    continue;
                }
                Thread.startVirtualThread(() -> send(user, item));
            }
        }

        void send(long user, long item) {
            inFlight.increment();
            try {
                URI uri = URI.create(target + (lanePrefix ? "/" + name : "") + "/api/items/" + item + "?user=" + user);
                HttpResponse<Void> r = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() >= 500) failed.increment();
            } catch (IOException | InterruptedException e) {
                failed.increment();
            } finally {
                sent.increment();
                inFlight.decrement();
            }
        }
    }

    LoadGen(String target, int users, double zipfS, double[] trace, double traceStepSeconds, long seed) {
        this.target = target;
        this.trace = trace;
        this.traceStepSeconds = traceStepSeconds;
        this.seed = seed;
        this.zipfCdf = zipfCdf(users, zipfS);
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    static double[] zipfCdf(int n, double s) {
        double[] cdf = new double[n];
        double sum = 0;
        for (int k = 1; k <= n; k++) {
            sum += 1.0 / Math.pow(k, s);
            cdf[k - 1] = sum;
        }
        for (int k = 0; k < n; k++) cdf[k] /= sum;
        return cdf;
    }

    /** User id (1-based rank) for a uniform draw u. */
    long zipf(double u) {
        int lo = 0, hi = zipfCdf.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (zipfCdf[mid] < u) lo = mid + 1;
            else hi = mid;
        }
        return lo + 1;
    }

    double traceMultiplier() {
        if (trace == null || trace.length == 0) return 1.0;
        double t = (System.nanoTime() - startNanos) / 1e9;
        int i = (int) ((traceOffset + (long) (t / traceStepSeconds)) % trace.length);
        return trace[i];
    }

    /** Index of the trace step to start from (for example a busy hour rather than midnight). */
    volatile long traceOffset;

    void setLane(String name, double rps) {
        LaneLoad l = lanes.get(name);
        if (l != null) {
            l.rps = rps;
            return;
        }
        LaneLoad n = new LaneLoad(name, rps, seed * 31 + name.hashCode());
        lanes.put(name, n);
        Thread.ofPlatform().daemon().name("lane-" + name).start(n);
    }

    static double[] readTrace(String path) throws IOException {
        List<Double> v = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(path))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(",");
            try {
                v.add(Double.parseDouble(f[f.length - 1]));
            } catch (NumberFormatException header) {
                // a CSV header line
            }
        }
        return v.stream().mapToDouble(Double::doubleValue).toArray();
    }

    static void run(Args a) throws Exception {
        String tracePath = a.get("trace", "");
        double[] trace = tracePath.isBlank() ? null : readTrace(tracePath);
        LoadGen g = new LoadGen(a.get("target", "http://localhost:8000"), a.getInt("users", 50_000),
                a.getDouble("zipf", 1.0), trace, a.getDouble("trace-step-s", 10), a.getLong("seed", 1));
        g.lanePrefix = !Boolean.parseBoolean(a.get("no-lane-prefix", "false"));
        g.traceOffset = a.getLong("trace-offset", 0);
        for (String spec : a.get("lanes", "").split(",")) {
            if (spec.isBlank()) continue;
            String[] p = spec.split(":");
            g.setLane(p[0], Double.parseDouble(p[1]));
        }
        String bind = a.get("bind", "");
        HttpServer control = HttpServer.create(bind.isBlank() ? new InetSocketAddress(a.getInt("control-port", 8001))
                : new InetSocketAddress(bind, a.getInt("control-port", 8001)), 64);
        control.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        control.createContext("/", ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                Map<String, String> q = Splitter.query(ex.getRequestURI().getRawQuery());
                String body;
                if (path.equals("/rate") && ex.getRequestMethod().equals("POST")) {
                    g.setLane(q.get("lane"), Double.parseDouble(q.get("rps")));
                    body = g.stats();
                } else {
                    body = g.stats();
                }
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            } catch (RuntimeException e) {
                ex.sendResponseHeaders(400, -1);
            } finally {
                ex.close();
            }
        });
        control.start();
        System.out.println("loadgen -> " + g.target + " lanes " + g.lanes.keySet() + (trace == null ? "" : " trace " + tracePath + " (" + trace.length + " steps)"));
        long last = System.nanoTime();
        while (true) {
            Thread.sleep(60_000);
            long now = System.nanoTime();
            System.out.println(g.stats() + " after " + (now - last) / 1_000_000_000L + "s");
            last = now;
        }
    }

    String stats() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (LaneLoad l : lanes.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(l.name).append("\":{\"rps\":").append(l.rps)
                    .append(",\"sent\":").append(l.sent.sum())
                    .append(",\"failed\":").append(l.failed.sum())
                    .append(",\"inFlight\":").append(l.inFlight.sum())
                    .append(",\"dropped\":").append(l.dropped.sum()).append('}');
        }
        return sb.append(first ? "" : ",").append("\"traceMultiplier\":").append(traceMultiplier()).append('}').toString();
    }
}
