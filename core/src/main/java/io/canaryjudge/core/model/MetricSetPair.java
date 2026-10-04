package io.canaryjudge.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One metric's series for the baseline ("control") and the canary ("experiment"), in Kayenta's
 * MetricSetPair JSON shape, so a pair list can be sent to Kayenta's {@code /metricSetPairList} unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricSetPair(
        String name,
        String id,
        Map<String, String> tags,
        Map<String, List<Double>> values,
        Map<String, Scope> scopes,
        Map<String, Map<String, String>> attributes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scope(String startTimeIso, long startTimeMillis, long stepMillis) {}

    public static MetricSetPair of(String name, double[] control, double[] experiment, long startMillis, long stepMillis) {
        Map<String, List<Double>> v = new LinkedHashMap<>();
        v.put("control", box(control));
        v.put("experiment", box(experiment));
        Scope s = new Scope(java.time.Instant.ofEpochMilli(startMillis).toString(), startMillis, stepMillis);
        Map<String, Scope> scopes = new LinkedHashMap<>();
        scopes.put("control", s);
        scopes.put("experiment", s);
        return new MetricSetPair(name, java.util.UUID.nameUUIDFromBytes(name.getBytes()).toString(), Map.of(), v, scopes, Map.of());
    }

    @JsonIgnore
    public double[] control() { return unbox(values.get("control")); }

    @JsonIgnore
    public double[] experiment() { return unbox(values.get("experiment")); }

    static List<Double> box(double[] a) {
        java.util.ArrayList<Double> l = new java.util.ArrayList<>(a.length);
        for (double d : a) l.add(d);
        return l;
    }

    static double[] unbox(List<Double> l) {
        if (l == null) return new double[0];
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i) == null ? Double.NaN : l.get(i);
        return a;
    }
}
