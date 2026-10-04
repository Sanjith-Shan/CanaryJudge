package io.canaryjudge.server;

import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.model.MetricSetPair;
import io.canaryjudge.core.model.Recording;
import io.canaryjudge.core.prom.PromClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches a canary config's metrics from Prometheus for a control scope and an experiment scope. Queries are
 * the config's {@code customInlineTemplate} values ({@code PromQL:...}) with {@code ${scope}} and
 * {@code ${location}} filled in, the subset of Kayenta's Prometheus templating CanaryJudge supports.
 * Series are put on a dense grid (start, start + step, ..., end) with NaN where Prometheus had nothing.
 */
@Component
public class MetricFetcher {
    private final PromClient prom;

    public MetricFetcher(PromClient prom) { this.prom = prom; }

    public record Scope(String scope, String location) {}

    static String expand(String template, Scope s) {
        return template.replace("${scope}", s.scope() == null ? "" : s.scope())
                .replace("${location}", s.location() == null ? "" : s.location());
    }

    public List<MetricSetPair> fetch(CanaryConfig config, Scope control, Scope experiment, long startSec, long endSec, long stepSec)
            throws IOException, InterruptedException {
        List<MetricSetPair> out = new ArrayList<>();
        for (CanaryConfig.MetricConfig m : config.metrics()) {
            String tpl = template(m);
            double[] c = prom.range(expand(tpl, control), startSec, endSec, stepSec);
            double[] e = prom.range(expand(tpl, experiment), startSec, endSec, stepSec);
            out.add(MetricSetPair.of(m.name(), c, e, startSec * 1000, stepSec * 1000));
        }
        return out;
    }

    /** Everything the sequential judge needs: the config's metrics plus the counters its rate metrics name. */
    public Recording record(CanaryConfig config, Map<String, String> extraSeries, Scope control, Scope experiment,
                            long startSec, long endSec, long stepSec) throws IOException, InterruptedException {
        Map<String, Recording.Pair> series = new LinkedHashMap<>();
        for (MetricSetPair p : fetch(config, control, experiment, startSec, endSec, stepSec))
            series.put(p.name(), new Recording.Pair(p.control(), p.experiment()));
        for (var e : extraSeries.entrySet()) {
            series.put(e.getKey(), new Recording.Pair(
                    prom.range(expand(e.getValue(), control), startSec, endSec, stepSec),
                    prom.range(expand(e.getValue(), experiment), startSec, endSec, stepSec)));
        }
        return new Recording(experiment.scope(), "live", Map.of(), startSec * 1000, stepSec * 1000, series, Map.of());
    }

    static String template(CanaryConfig.MetricConfig m) {
        String t = m.promQlTemplate();
        if (t == null)
            throw new IllegalArgumentException("metric " + m.name() + " needs query.customInlineTemplate starting with PromQL:");
        return t;
    }

    public double scalar(String query) throws IOException, InterruptedException {
        return prom.scalar(query);
    }
}
