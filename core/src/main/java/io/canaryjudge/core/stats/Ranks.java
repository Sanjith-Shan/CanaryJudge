package io.canaryjudge.core.stats;

import java.util.Arrays;

/** Average ranks (1-based) with the sizes of tie groups, for rank tests. */
public record Ranks(double[] ranks, int[] tieGroupSizes) {

    /** Ranks the values in order; NaN must already be removed or replaced. */
    public static Ranks of(double[] values) {
        int n = values.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(values[a], values[b]));
        double[] ranks = new double[n];
        int[] groups = new int[n];
        int groupCount = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && values[idx[j + 1]] == values[idx[i]]) j++;
            double avg = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) ranks[idx[k]] = avg;
            groups[groupCount++] = j - i + 1;
            i = j + 1;
        }
        return new Ranks(ranks, Arrays.copyOf(groups, groupCount));
    }

    /** Sum over tie groups of (t^3 - t), the tie correction term in the rank-sum variance. */
    public double tieTerm() {
        double s = 0;
        for (int t : tieGroupSizes) s += (double) t * t * t - t;
        return s;
    }

    public boolean hasTies() {
        for (int t : tieGroupSizes) if (t > 1) return true;
        return false;
    }
}
