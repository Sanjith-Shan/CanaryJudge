package io.canaryjudge.bench;

import java.io.IOException;
import java.util.Map;

/** Starts and stops target service instances: as containers ({@link Docker}) or as local JVMs ({@link ProcessLauncher}). */
public interface Launcher {
    /** Starts a target instance named {@code name}; returns an id. */
    String runTarget(String name, Map<String, String> env, String heap, String memLimit) throws IOException, InterruptedException;

    /** Stops and removes it, if it exists. */
    void remove(String name) throws IOException, InterruptedException;

    static Launcher from(Map<String, String> a) {
        if ("process".equals(a.get("launcher")))
            return new ProcessLauncher(a.getOrDefault("target-jar", "target/build/libs/target-service.jar"),
                    a.getOrDefault("ports", ""), a.getOrDefault("log-dir", "build/run"));
        return new Docker(a.getOrDefault("image", "canaryjudge:dev"), a.getOrDefault("network", "cj-net"));
    }
}
