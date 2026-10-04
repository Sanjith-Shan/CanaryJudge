package io.canaryjudge.bench;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Starts and removes target containers through the docker CLI. */
public final class Docker {
    private final String image;
    private final String network;

    public Docker(String image, String network) {
        this.image = image;
        this.network = network;
    }

    public static String exec(List<String> cmd, int timeoutSeconds) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("timed out: " + String.join(" ", cmd));
        }
        String out = new String(p.getInputStream().readAllBytes()).trim();
        if (p.exitValue() != 0) throw new IOException("exit " + p.exitValue() + ": " + String.join(" ", cmd) + "\n" + out);
        return out;
    }

    public void remove(String name) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("docker", "rm", "-f", name).redirectErrorStream(true).start();
        p.waitFor(60, TimeUnit.SECONDS);
    }

    /** Runs a target service container; returns its id. */
    public String runTarget(String name, Map<String, String> env, String heap, String memLimit) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", name, "--network", network,
                "--memory", memLimit, "--label", "project=canaryjudge"));
        for (var e : env.entrySet()) {
            cmd.add("-e");
            cmd.add(e.getKey() + "=" + e.getValue());
        }
        cmd.addAll(List.of(image, "java", "-Xmx" + heap, "-XX:+UseSerialGC", "-Xss512k", "-jar", "/app/target-service.jar"));
        return exec(cmd, 120);
    }
}
