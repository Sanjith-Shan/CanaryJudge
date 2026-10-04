package io.canaryjudge.target;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A small catalog-style service to run canaries against. Each request does a little real work (hashing a
 * rendered item), waits on a simulated downstream call whose time is log-normal (median 20 ms), and fails
 * with a small background error rate. The injector adds the regressions.
 */
@SpringBootApplication
@RestController
public class TargetApplication {
    static final double DEPENDENCY_MEDIAN_MS = 20.0;
    static final double DEPENDENCY_SIGMA = 0.5;
    static final double BASE_ERROR_RATE = 0.001;

    private final Injector injector;

    public TargetApplication(Injector injector) {
        this.injector = injector;
    }

    public static void main(String[] args) {
        SpringApplication.run(TargetApplication.class, args);
    }

    @GetMapping("/api/items/{id}")
    public Map<String, Object> item(@PathVariable long id, @RequestParam(defaultValue = "0") long user) throws Exception {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String rendered = "{\"id\":" + id + ",\"user\":" + user + ",\"title\":\"item-" + id + "\",\"price\":" + (id % 997) + "}";
        String etag = digest(rendered.repeat(16));

        double dependencyMs = DEPENDENCY_MEDIAN_MS * Math.exp(DEPENDENCY_SIGMA * rnd.nextGaussian());
        dependencyMs = Math.min(dependencyMs, 2000) * injector.latencyMultiplier();
        long micros = (long) (dependencyMs * 1000);
        Thread.sleep(micros / 1000, (int) (micros % 1000) * 1000);

        injector.maybeBurn();
        injector.maybeLeak();
        if (rnd.nextDouble() < BASE_ERROR_RATE + injector.extraErrorRate())
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "dependency failed");
        return Map.of("id", id, "etag", etag, "body", rendered);
    }

    @PostMapping("/admin/inject")
    public Map<String, String> inject(@RequestParam String type, @RequestParam(defaultValue = "0") double size,
                                      @RequestParam(defaultValue = "0") double onset) {
        injector.set(type, size, onset);
        return Map.of("injector", injector.describe());
    }

    @GetMapping("/admin/inject")
    public Map<String, String> injection() {
        return Map.of("injector", injector.describe());
    }

    private static String digest(String s) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)), 0, 8);
    }
}
