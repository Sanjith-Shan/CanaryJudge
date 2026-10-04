package io.canaryjudge.core.stats;

import java.util.Arrays;

/**
 * The Mann-Whitney U test (Wilcoxon rank-sum), its exact null distribution, the Hodges-Lehmann shift
 * estimate, and the confidence interval for the shift that Kayenta's judge uses.
 *
 * <p>Conventions: U is the statistic for {@code x}, the number of pairs (x_i, y_j) with x_i > y_j plus
 * half the ties. {@link Alternative#GREATER} means x tends to be larger than y.
 */
public final class MannWhitney {
    private MannWhitney() {}

    public enum Alternative { TWO_SIDED, GREATER, LESS }

    public record Test(double u, double z, double pValue, String method) {}

    public record Interval(double lower, double upper, double estimate) {}

    /** U for x from ranks: R_x - n(n+1)/2. */
    public static double uStatistic(double[] x, double[] y) {
        Ranks r = Ranks.of(concat(x, y));
        double rx = 0;
        for (int i = 0; i < x.length; i++) rx += r.ranks()[i];
        return rx - x.length * (x.length + 1) / 2.0;
    }

    /** Normal approximation with tie correction, optionally with the 0.5 continuity correction (scipy's "asymptotic"). */
    public static Test asymptotic(double[] x, double[] y, Alternative alt, boolean continuity) {
        int n = x.length, m = y.length;
        if (n == 0 || m == 0) throw new IllegalArgumentException("both samples must be non-empty");
        Ranks r = Ranks.of(concat(x, y));
        double rx = 0;
        for (int i = 0; i < n; i++) rx += r.ranks()[i];
        double u1 = rx - n * (n + 1) / 2.0;
        double mu = n * (double) m / 2.0;
        int nm = n + m;
        double var = n * (double) m / 12.0 * ((nm + 1) - r.tieTerm() / ((double) nm * (nm - 1)));
        double sd = Math.sqrt(var);
        double cc = continuity ? 0.5 : 0.0;
        if (sd == 0) return new Test(u1, 0, 1.0, "asymptotic");
        double z, p;
        switch (alt) {
            case GREATER -> { z = (u1 - mu - cc) / sd; p = Normal.upperTail(z); }
            case LESS -> { z = (u1 - mu + cc) / sd; p = Normal.cdf(z); }
            default -> {
                double u = Math.max(u1, n * (double) m - u1);
                z = (u - mu - cc) / sd;
                p = Math.min(1.0, 2 * Normal.upperTail(z));
            }
        }
        return new Test(u1, z, p, "asymptotic");
    }

    /** Exact p-value from the permutation distribution of U. Only valid without ties. */
    public static Test exact(double[] x, double[] y, Alternative alt) {
        int n = x.length, m = y.length;
        double u1 = uStatistic(x, y);
        if (u1 != Math.rint(u1)) throw new IllegalArgumentException("exact test needs untied data");
        double[] pmf = exactUDistribution(n, m);
        int u = (int) u1;
        double p = switch (alt) {
            case GREATER -> tailAtLeast(pmf, u);
            case LESS -> tailAtMost(pmf, u);
            default -> {
                int big = Math.max(u, n * m - u);
                yield Math.min(1.0, 2 * tailAtLeast(pmf, big));
            }
        };
        return new Test(u1, Double.NaN, p, "exact");
    }

    private static double tailAtLeast(double[] pmf, int u) {
        double s = 0;
        for (int k = Math.max(u, 0); k < pmf.length; k++) s += pmf[k];
        return Math.min(1.0, s);
    }

    private static double tailAtMost(double[] pmf, int u) {
        double s = 0;
        for (int k = 0; k <= Math.min(u, pmf.length - 1); k++) s += pmf[k];
        return Math.min(1.0, s);
    }

    /**
     * Null distribution of U for sample sizes n and m: P(U = k) for k = 0..nm. The counts are the
     * coefficients of the Gaussian binomial [n+m choose n] in q, built as prod_{i=1..n} (1 - q^(m+i)) / (1 - q^i),
     * dividing first so every intermediate stays non-negative.
     */
    public static double[] exactUDistribution(int n, int m) {
        int max = n * m;
        double[] p = new double[max + 1];
        p[0] = 1;
        for (int i = 1; i <= n; i++) {
            // divide by (1 - q^i): running sum with stride i (truncated at degree nm, exact for the final result)
            for (int k = i; k <= max; k++) p[k] += p[k - i];
            // multiply by (1 - q^(m+i))
            int s = m + i;
            for (int k = max; k >= s; k--) p[k] -= p[k - s];
        }
        double total = 0;
        for (double v : p) total += v;
        for (int k = 0; k <= max; k++) p[k] = Math.max(0, p[k]) / total;
        return p;
    }

    /** Median of the pairwise differences x_i - y_j. */
    public static double hodgesLehmann(double[] x, double[] y) {
        double[] d = pairwiseDifferences(x, y);
        Arrays.sort(d);
        int k = d.length;
        return k % 2 == 1 ? d[k / 2] : (d[k / 2 - 1] + d[k / 2]) / 2.0;
    }

    /** Common language effect size P(x > y) + P(x = y)/2, which is U / (nm). */
    public static double cles(double[] x, double[] y) {
        return uStatistic(x, y) / ((double) x.length * y.length);
    }

    static double[] pairwiseDifferences(double[] x, double[] y) {
        double[] d = new double[x.length * y.length];
        int k = 0;
        for (double a : x) for (double b : y) d[k++] = a - b;
        return d;
    }

    /**
     * Standardized Wilcoxon statistic for the shift mu, minus zq: the function whose roots give the
     * asymptotic confidence limits (as in R's wilcox.test with exact = FALSE and correct = TRUE, which
     * Kayenta's judge follows). Decreasing in mu, up to ties.
     */
    public static double wilcoxonDiff(double mu, double zq, double[] x, double[] y) {
        int n = x.length, m = y.length;
        double[] shifted = new double[n + m];
        for (int i = 0; i < n; i++) shifted[i] = x[i] - mu;
        System.arraycopy(y, 0, shifted, n, m);
        Ranks r = Ranks.of(shifted);
        double rx = 0;
        for (int i = 0; i < n; i++) rx += r.ranks()[i];
        double dz = rx - n * (n + 1) / 2.0 - n * (double) m / 2.0;
        double correction = Math.signum(dz) * 0.5;
        double sigma = Math.sqrt(n * (double) m / 12.0 * ((n + m + 1) - r.tieTerm() / ((double) (n + m) * (n + m - 1))));
        if (sigma == 0) throw new IllegalArgumentException("cannot compute confidence interval when all observations are tied");
        return (dz - correction) / sigma - zq;
    }

    /**
     * Confidence interval for the shift x - y at the given level, by inverting the asymptotic test.
     * The function is a step function that only changes at the pairwise differences, so instead of a
     * numeric root finder this searches those breakpoints and returns the exact jump location. The
     * estimate is the Hodges-Lehmann median of the pairwise differences.
     */
    public static Interval shiftInterval(double[] x, double[] y, double confidence) {
        double alpha = 1 - confidence;
        double zQuant = Normal.quantile(alpha / 2); // negative
        double[] d = pairwiseDifferences(x, y);
        Arrays.sort(d);
        double[] breaks = unique(d);
        double lower = findRoot(-zQuant, breaks, x, y);
        double upper = findRoot(zQuant, breaks, x, y);
        return new Interval(lower, upper, hodgesLehmann(x, y));
    }

    private static double findRoot(double zq, double[] breaks, double[] x, double[] y) {
        double muMin = breaks[0], muMax = breaks[breaks.length - 1];
        if (wilcoxonDiff(muMin, zq, x, y) <= 0) return muMin;
        if (wilcoxonDiff(muMax, zq, x, y) >= 0) return muMax;
        // Interval i is (breaks[i], breaks[i+1]); f is constant on it. Find the first interval where f <= 0.
        int lo = 0, hi = breaks.length - 2, first = breaks.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            double probe = breaks[mid] + (breaks[mid + 1] - breaks[mid]) / 2.0;
            if (wilcoxonDiff(probe, zq, x, y) <= 0) {
                first = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return breaks[first];
    }

    private static double[] unique(double[] sorted) {
        int k = 0;
        double[] out = new double[sorted.length];
        for (int i = 0; i < sorted.length; i++) if (i == 0 || sorted[i] != sorted[i - 1]) out[k++] = sorted[i];
        return Arrays.copyOf(out, k);
    }

    static double[] concat(double[] a, double[] b) {
        double[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
