package io.canaryjudge.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs target instances as local JVM processes on fixed loopback ports instead of containers, for a machine
 * where Docker is not available. Same JVM flags as the containers; the splitter is pointed at the ports with
 * its {@code --routes} option and Prometheus scrapes them by port.
 */
public final class ProcessLauncher implements Launcher {
    private final String jar;
    private final Map<String, Integer> ports = new HashMap<>();
    private final Path logDir;
    private final Map<String, Process> running = new ConcurrentHashMap<>();

    public ProcessLauncher(String jar, String portSpec, String logDir) {
        this.jar = jar;
        this.logDir = Path.of(logDir);
        for (String p : portSpec.split(",")) {
            int eq = p.indexOf('=');
            if (eq > 0) ports.put(p.substring(0, eq).trim(), Integer.parseInt(p.substring(eq + 1).trim()));
        }
    }

    @Override
    public String runTarget(String name, Map<String, String> env, String heap, String memLimit) throws IOException {
        Integer port = ports.get(name);
        if (port == null) throw new IOException("no port configured for " + name);
        Files.createDirectories(logDir);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx" + heap, "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                "-XX:MaxMetaspaceSize=128m", "-Xss256k", "-jar", jar));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(logDir.resolve(name + ".log").toFile());
        pb.environment().putAll(env);
        pb.environment().put("SERVER_PORT", Integer.toString(port));
        pb.environment().put("SERVER_ADDRESS", "127.0.0.1");
        Process p = pb.start();
        running.put(name, p);
        return Long.toString(p.pid());
    }

    @Override
    public void remove(String name) throws InterruptedException {
        Process p = running.remove(name);
        if (p == null) return;
        p.destroy();
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(15, TimeUnit.SECONDS);
        }
    }

}
