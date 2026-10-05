package io.canaryjudge.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Describes the machine and its current load for every results row. */
public final class Machine {
    private Machine() {}

    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
            m.put("host", "GitHub Actions runner (" + System.getenv().getOrDefault("RUNNER_OS", "?") + ", " + System.getenv().getOrDefault("ImageOS", "?") + ")");
            m.put("cpu", cpuModel());
            m.put("cpus_visible", Runtime.getRuntime().availableProcessors());
            m.put("mem_gb", Math.round(memTotalKb() / 1024.0 / 1024.0 * 10) / 10.0);
            m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
            m.put("java", System.getProperty("java.version"));
            m.put("git", System.getenv().getOrDefault("GITHUB_SHA", "unknown"));
            return m;
        }
        m.put("host", "mini PC (Acemagic K1), shared with other jobs");
        if (WINDOWS) {
            // native Windows run (no WSL): every process shares the host's 4 cores and 16 GB
            m.put("cpu", "AMD Ryzen 3 4300U with Radeon Graphics");
            m.put("cores_host", 4);
            m.put("ram_gb_host", 16);
            m.put("cpus_visible", Runtime.getRuntime().availableProcessors());
            m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (native, no WSL)");
            m.put("java", System.getProperty("java.version"));
            m.put("git", git());
            return m;
        }
        m.put("cpu", cpuModel());
        m.put("cores_host", 4);
        m.put("ram_gb_host", 16);
        m.put("wsl_vcpus", Runtime.getRuntime().availableProcessors());
        m.put("wsl_mem_gb", Math.round(memTotalKb() / 1024.0 / 1024.0 * 10) / 10.0);
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (WSL2)");
        m.put("java", System.getProperty("java.version"));
        m.put("git", git());
        return m;
    }

    public static Map<String, Object> load() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            String[] la = Files.readString(Path.of("/proc/loadavg")).trim().split("\\s+");
            m.put("wsl_load1", Double.parseDouble(la[0]));
            m.put("wsl_load5", Double.parseDouble(la[1]));
        } catch (IOException | RuntimeException e) {
            m.put("wsl_load1", null);
        }
        long total = memTotalKb(), avail = memAvailableKb();
        if (total > 0) m.put("wsl_mem_used_pct", Math.round((total - avail) * 1000.0 / total) / 10.0);
        m.put("windows_host_cpu_pct", windowsCpu());
        if (WINDOWS) {
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            m.put("host_mem_used_pct", Math.round((1 - os.getFreeMemorySize() / (double) os.getTotalMemorySize()) * 1000) / 10.0);
        }
        m.put("ts", java.time.OffsetDateTime.now().toString());
        return m;
    }

    /**
     * Windows host CPU %, read from the file scripts/host_cpu_sampler.ps1 keeps up to date (CJ_HOST_CPU_FILE);
     * null when the file is missing or older than a minute. Starting powershell.exe from inside WSL for this, as
     * an earlier version did, is the likely cause of the WSL service failures in BUG_LOG #9.
     */
    static Double windowsCpu() {
        String f = System.getenv("CJ_HOST_CPU_FILE");
        if (f == null) return null;
        try {
            String[] p = Files.readString(Path.of(f)).trim().split("\\s+");
            long age = System.currentTimeMillis() / 1000 - Long.parseLong(p[0]);
            return age > 60 ? null : Double.parseDouble(p[1]);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String cpuModel() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/cpuinfo")))
                if (l.startsWith("model name")) return l.substring(l.indexOf(':') + 1).trim();
        } catch (IOException ignored) {
        }
        return "unknown";
    }

    /** MemAvailable of the VM in MB (0 if unknown). */
    public static long availableMb() { return memAvailableKb() / 1024; }

    private static long memTotalKb() { return meminfo("MemTotal"); }

    private static long memAvailableKb() { return meminfo("MemAvailable"); }

    private static long meminfo(String key) {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/meminfo")))
                if (l.startsWith(key + ":")) return Long.parseLong(l.replaceAll("[^0-9]", ""));
        } catch (IOException ignored) {
        }
        return 0;
    }

    static String git() {
        String g = System.getenv("CJ_GIT");
        return g == null ? "unknown" : g;
    }
}
