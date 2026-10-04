package io.canaryjudge.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {
    @TempDir
    Path dir;

    static final String CONFIG = """
            {"name":"cli","judge":{"name":"NetflixACAJudge-v1.0","judgeConfigurations":{}},
             "classifier":{"groupWeights":{"Latency":60,"Errors":40}},
             "metrics":[
              {"name":"p99","groups":["Latency"],"analysisConfigurations":{"canary":{"direction":"increase"},
                 "canaryjudge":{"sequential":{"type":"mean","logScale":true}}}},
              {"name":"error_rate","groups":["Errors"],"analysisConfigurations":{"canary":{"direction":"increase","critical":true,"nanStrategy":"replace"},
                 "canaryjudge":{"sequential":{"type":"rate","countSeries":"errors_total","totalSeries":"requests_total"}}}}
             ]}""";

    Path csv(String name, double p99, double errorRate, long seed) throws IOException {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder("timestamp,p99,error_rate,errors_total,requests_total\n");
        long errors = 0, requests = 0;
        for (int i = 0; i < 36; i++) {
            int n = 400;
            int e = 0;
            for (int k = 0; k < n; k++) if (r.nextDouble() < errorRate) e++;
            errors += e;
            requests += n;
            sb.append(i * 10).append(',').append(p99 * Math.exp(0.08 * r.nextGaussian())).append(',')
                    .append(e / (double) n).append(',').append(errors).append(',').append(requests).append('\n');
        }
        Path p = dir.resolve(name);
        Files.writeString(p, sb.toString());
        return p;
    }

    int run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return Cli.run(args, new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void healthyCanaryPassesAndBadCanaryFailsTheGate() throws IOException {
        Path cfg = dir.resolve("config.json");
        Files.writeString(cfg, CONFIG);
        Path base = csv("b.csv", 0.08, 0.001, 1);
        assertEquals(0, run("judge", "--config", cfg.toString(), "--baseline", base.toString(), "--canary", csv("ok.csv", 0.08, 0.001, 2).toString()));
        assertEquals(1, run("judge", "--config", cfg.toString(), "--baseline", base.toString(), "--canary", csv("slow.csv", 0.12, 0.001, 3).toString()));
        assertEquals(1, run("judge", "--config", cfg.toString(), "--baseline", base.toString(), "--canary", csv("errors.csv", 0.08, 0.02, 4).toString(), "--mode", "sequential"));
    }

    @Test
    void usageErrorsExitWith64() {
        assertEquals(64, run());
        assertEquals(64, run("judge", "--config"));
        assertTrue(run("nonsense") == 64);
    }
}
