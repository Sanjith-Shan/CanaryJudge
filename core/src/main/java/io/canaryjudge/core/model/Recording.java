package io.canaryjudge.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.canaryjudge.core.config.CanaryConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The metric series of one live canary run, as fetched from Prometheus: every series is sampled at
 * {@code startMillis + i * stepMillis}, missing points are NaN. Judges replay recordings, so every judge
 * sees exactly the same numbers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Recording(
        String trial,
        String scenario,
        Map<String, Object> condition,
        long startMillis,
        long stepMillis,
        Map<String, Pair> series,
        Map<String, Object> meta) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pair(double[] control, double[] experiment) {
        public Pair prefix(int n) {
            return new Pair(Arrays.copyOf(control, Math.min(n, control.length)),
                    Arrays.copyOf(experiment, Math.min(n, experiment.length)));
        }
    }

    public int length() {
        int n = 0;
        for (Pair p : series.values()) n = Math.max(n, Math.max(p.control().length, p.experiment().length));
        return n;
    }

    public Pair get(String name) {
        Pair p = series.get(name);
        if (p == null) throw new IllegalArgumentException("recording " + trial + " has no series " + name);
        return p;
    }

    /** The config's metrics over the first n points, as Kayenta metric set pairs. */
    public List<MetricSetPair> pairs(CanaryConfig config, int n) {
        List<MetricSetPair> out = new ArrayList<>();
        for (CanaryConfig.MetricConfig m : config.metrics()) {
            Pair p = get(m.name()).prefix(n);
            out.add(MetricSetPair.of(m.name(), p.control(), p.experiment(), startMillis, stepMillis));
        }
        return out;
    }
}
