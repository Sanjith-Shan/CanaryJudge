package io.canaryjudge.core.stats;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Summary statistics, R-7 percentiles and the IQR outlier fence used before classification. */
public final class Descriptive {
    private Descriptive() {}

    public record Summary(double min, double max, double mean, double std, int count) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("min", min);
            m.put("max", max);
            m.put("mean", mean);
            m.put("std", std);
            m.put("count", count);
            return m;
        }
    }

    public static Summary summary(double[] v) {
        if (v.length == 0) return new Summary(0, 0, 0, 0, 0);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        return new Summary(min, max, mean(v), Math.sqrt(variance(v)), v.length);
    }

    public static double mean(double[] v) {
        if (v.length == 0) return Double.NaN;
        double s = 0;
        for (double x : v) s += x;
        double m = s / v.length;
        // second pass correction, as compensated mean implementations do
        double c = 0;
        for (double x : v) c += x - m;
        return m + c / v.length;
    }

    /** Sample variance (n - 1 denominator); 0 for a single value. */
    public static double variance(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v), ss = 0, c = 0;
        for (double x : v) {
            ss += (x - m) * (x - m);
            c += x - m;
        }
        return (ss - c * c / v.length) / (v.length - 1);
    }

    /** Ratio of means, canary over baseline; NaN when either mean is zero. */
    public static double meanRatio(double[] control, double[] experiment) {
        double c = mean(control), e = mean(experiment);
        return c == 0.0 || e == 0.0 ? Double.NaN : e / c;
    }

    /** Percentile p in (0, 100] with the R-7 (linear interpolation) definition. */
    public static double percentile(double[] values, double p) {
        if (values.length == 0) return Double.NaN;
        double[] s = values.clone();
        Arrays.sort(s);
        if (s.length == 1) return s[0];
        double h = (s.length - 1) * p / 100.0;
        int lo = (int) Math.floor(h);
        if (lo >= s.length - 1) return s[s.length - 1];
        return s[lo] + (h - lo) * (s[lo + 1] - s[lo]);
    }

    /**
     * Removes values outside the fences min(p1, Q1 - k*IQR) and max(p99, Q3 + k*IQR). Taking the wider of
     * the IQR fence and the 1st/99th percentiles keeps the detector from cutting into normal tails.
     */
    public static double[] removeOutliers(double[] v, double factor) {
        double q1 = percentile(v, 25), q3 = percentile(v, 75), iqr = q3 - q1;
        double lower = Math.min(percentile(v, 1), q1 - factor * iqr);
        double upper = Math.max(percentile(v, 99), q3 + factor * iqr);
        return Arrays.stream(v).filter(x -> x >= lower && x <= upper).toArray();
    }
}
