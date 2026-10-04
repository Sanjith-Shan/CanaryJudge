package io.canaryjudge.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.judge.CanaryJudge;
import io.canaryjudge.core.judge.JudgeResult;
import io.canaryjudge.core.judge.MetricResult;
import io.canaryjudge.core.model.Recording;
import io.canaryjudge.core.prom.PromClient;
import io.canaryjudge.core.sequential.SequentialJudge;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code canaryjudge} on the command line, for CI gates and for judging recorded series offline.
 *
 * <pre>
 * canaryjudge judge --config canary-config.json (--baseline b.csv --canary c.csv | --recording run.json)
 *                   [--mode fixed|sequential|both] [--pass 95] [--marginal 75] [--alpha 0.05] [--json]
 * canaryjudge fetch --config canary-config.json --prometheus URL --baseline-scope S --canary-scope S
 *                   --start EPOCH --end EPOCH [--step 10] [--extra recording-extra-series.json] --out run.json
 * </pre>
 *
 * CSV files have one row per interval and one column per metric (named as in the config; extra columns such
 * as the counters a rate metric needs are kept; a {@code timestamp} column is ignored; empty cells are NaN).
 * Exit status: 0 pass, 1 fail, 2 marginal, 64 usage error.
 */
public final class Cli {
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("usage: canaryjudge judge|fetch [--key value ...]  (see the README)");
            return 64;
        }
        try {
            Map<String, String> a = parse(args);
            return switch (args[0]) {
                case "judge" -> judge(a, out);
                case "fetch" -> fetch(a, out);
                default -> {
                    err.println("unknown command " + args[0]);
                    yield 64;
                }
            };
        } catch (IllegalArgumentException | IOException e) {
            err.println("canaryjudge: " + e.getMessage());
            return 64;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 64;
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("expected --key, got " + args[i]);
            String k = args[i].substring(2);
            String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            m.put(k, v);
        }
        return m;
    }

    static String required(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null) throw new IllegalArgumentException("--" + key + " is required");
        return v;
    }

    static int judge(Map<String, String> a, PrintStream out) throws IOException {
        CanaryConfig config = CanaryConfig.read(Path.of(required(a, "config")));
        Recording rec;
        if (a.containsKey("recording")) {
            rec = CanaryConfig.MAPPER.readValue(Files.readString(Path.of(a.get("recording"))), Recording.class);
        } else {
            rec = fromCsv(Path.of(required(a, "baseline")), Path.of(required(a, "canary")), Long.parseLong(a.getOrDefault("step", "10")));
        }
        String mode = a.getOrDefault("mode", "both");
        double pass = Double.parseDouble(a.getOrDefault("pass", "95")), marginal = Double.parseDouble(a.getOrDefault("marginal", "75"));
        double alpha = Double.parseDouble(a.getOrDefault("alpha", "0.05"));
        int n = rec.length();
        JudgeResult fixed = new CanaryJudge().judge(config, pass, marginal, rec.pairs(config, n));
        SequentialJudge.Decision seq = new SequentialJudge(alpha).replay(config, rec, n);
        String fixedVerdict = fixed.score().classification().toLowerCase(Locale.ROOT);
        String verdict = switch (mode) {
            case "fixed" -> fixedVerdict;
            case "sequential" -> seq.failed() ? "fail" : "pass";
            default -> seq.failed() || fixedVerdict.equals("fail") ? "fail" : fixedVerdict;
        };
        if (a.containsKey("json")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("verdict", verdict);
            m.put("mode", mode);
            m.put("points", n);
            m.put("fixed", fixed);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("failed", seq.failed());
            s.put("metric", seq.metric());
            s.put("pointsToFail", seq.pointsToFail());
            s.put("logThreshold", seq.logThreshold());
            s.put("logEvidence", seq.finalLogEvidence());
            m.put("sequential", s);
            out.println(CanaryConfig.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(m));
        } else {
            out.printf(Locale.ROOT, "verdict: %s (%s judge, %d intervals)%n", verdict.toUpperCase(Locale.ROOT), mode, n);
            out.printf(Locale.ROOT, "fixed-horizon score %.2f (%s)%s%n", fixed.score().score(), fixed.score().classification(),
                    fixed.score().classificationReason().isEmpty() ? "" : ": " + fixed.score().classificationReason());
            for (MetricResult r : fixed.results()) {
                Object ratio = r.resultMetadata().get("ratio");
                out.printf(Locale.ROOT, "  %-16s %-6s ratio %s%s%n", r.name(), r.classification(),
                        ratio instanceof Double d && !d.isNaN() ? String.format(Locale.ROOT, "%.3f", d) : "n/a",
                        r.critical() ? "  CRITICAL" : "");
            }
            if (seq.failed())
                out.printf(Locale.ROOT, "sequential: FAIL on %s after %d intervals%n", seq.metric(), seq.pointsToFail());
            else
                out.printf(Locale.ROOT, "sequential: no evidence of harm (threshold log e-value %.2f)%n", seq.logThreshold());
        }
        return switch (verdict) {
            case "pass" -> 0;
            case "marginal" -> 2;
            default -> 1;
        };
    }

    static int fetch(Map<String, String> a, PrintStream out) throws IOException, InterruptedException {
        CanaryConfig config = CanaryConfig.read(Path.of(required(a, "config")));
        PromClient prom = new PromClient(required(a, "prometheus"));
        String b = required(a, "baseline-scope"), c = required(a, "canary-scope");
        long start = Long.parseLong(required(a, "start")), end = Long.parseLong(required(a, "end"));
        long step = Long.parseLong(a.getOrDefault("step", "10"));
        Map<String, String> queries = new LinkedHashMap<>();
        for (CanaryConfig.MetricConfig m : config.metrics()) {
            String t = m.promQlTemplate();
            if (t == null) throw new IllegalArgumentException("metric " + m.name() + " has no PromQL customInlineTemplate");
            queries.put(m.name(), t);
        }
        if (a.containsKey("extra"))
            queries.putAll(CanaryConfig.MAPPER.readValue(Files.readString(Path.of(a.get("extra"))), new TypeReference<LinkedHashMap<String, String>>() {}));
        Map<String, Recording.Pair> series = new LinkedHashMap<>();
        for (var q : queries.entrySet())
            series.put(q.getKey(), new Recording.Pair(prom.range(q.getValue().replace("${scope}", b), start, end, step),
                    prom.range(q.getValue().replace("${scope}", c), start, end, step)));
        Recording rec = new Recording(c, "fetched", Map.of("baseline", b, "canary", c), start * 1000, step * 1000, series, Map.of());
        Files.writeString(Path.of(required(a, "out")), CanaryConfig.MAPPER.writeValueAsString(rec));
        out.printf("wrote %d series x %d points to %s%n", series.size(), rec.length(), a.get("out"));
        return 0;
    }

    static Recording fromCsv(Path baseline, Path canary, long stepSec) throws IOException {
        Map<String, double[]> b = readCsv(baseline), c = readCsv(canary);
        Map<String, Recording.Pair> series = new LinkedHashMap<>();
        for (String k : b.keySet()) {
            if (!c.containsKey(k)) throw new IllegalArgumentException("column " + k + " is in the baseline file but not the canary file");
            series.put(k, new Recording.Pair(b.get(k), c.get(k)));
        }
        return new Recording(canary.getFileName().toString(), "csv", Map.of(), 0, stepSec * 1000, series, Map.of());
    }

    static Map<String, double[]> readCsv(Path p) throws IOException {
        List<String> lines = Files.readAllLines(p).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        if (lines.isEmpty()) throw new IllegalArgumentException(p + " is empty");
        String[] header = lines.get(0).split(",", -1);
        List<double[]> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            double[] v = new double[header.length];
            for (int i = 0; i < header.length; i++) {
                String s = i < f.length ? f[i].trim() : "";
                v[i] = s.isEmpty() || s.equalsIgnoreCase("nan") ? Double.NaN : Double.parseDouble(s);
            }
            rows.add(v);
        }
        Map<String, double[]> cols = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            String name = header[i].trim();
            if (name.equals("timestamp") || name.isEmpty()) continue;
            double[] col = new double[rows.size()];
            for (int r = 0; r < rows.size(); r++) col[r] = rows.get(r)[i];
            cols.put(name, col);
        }
        return cols;
    }
}
