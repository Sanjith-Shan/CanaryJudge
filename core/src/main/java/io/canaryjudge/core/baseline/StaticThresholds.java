package io.canaryjudge.core.baseline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.canaryjudge.core.model.Recording;

import java.util.Map;

/**
 * The common alternative to a statistical judge: fixed limits on the canary's own metrics, checked every
 * interval, with a rollback after a number of failed checks (counted cumulatively, the way Flagger's
 * analysis {@code threshold} works). The canary is never compared with the baseline.
 *
 * @param max       metric name to the highest acceptable value
 * @param min       metric name to the lowest acceptable value
 * @param failLimit failed checks that trigger a rollback
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StaticThresholds(String name, Map<String, Double> max, Map<String, Double> min, int failLimit) {

    /** Returns the point count at which the canary is rolled back, or -1 if it survives maxPoints. */
    public int evaluate(Recording rec, int maxPoints) {
        int n = Math.min(maxPoints, rec.length());
        int failed = 0;
        for (int i = 0; i < n; i++) {
            if (breached(rec, i)) failed++;
            if (failed >= failLimit) return i + 1;
        }
        return -1;
    }

    public boolean breached(Recording rec, int i) {
        if (max != null) for (var e : max.entrySet()) {
            double[] v = rec.get(e.getKey()).experiment();
            if (i < v.length && !Double.isNaN(v[i]) && v[i] > e.getValue()) return true;
        }
        if (min != null) for (var e : min.entrySet()) {
            double[] v = rec.get(e.getKey()).experiment();
            if (i < v.length && !Double.isNaN(v[i]) && v[i] < e.getValue()) return true;
        }
        return false;
    }
}
