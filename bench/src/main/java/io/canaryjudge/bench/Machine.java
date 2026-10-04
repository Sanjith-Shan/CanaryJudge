package io.canaryjudge.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Describes the machine and its current load for every results row. */
public final class Machine {
    private Machine() {}

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", "mini PC (Acemagic K1), shared with other jobs");
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
        m.put("ts", java.time.OffsetDateTime.now().toString());
        return m;
    }

    static Double windowsCpu() {
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                    "(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return out.isEmpty() ? null : Double.parseDouble(out.lines().reduce((a, b) -> b).orElse("").trim());
        } catch (Exception e) {
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
