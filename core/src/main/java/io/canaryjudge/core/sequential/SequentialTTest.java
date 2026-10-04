package io.canaryjudge.core.sequential;

/**
 * An always-valid one-sample t-test on paired differences d_t = canary_t - baseline_t (or their logs).
 *
 * <p>The evidence after n pairs is the Bayes factor for a mean shift with a normal prior on the
 * standardized effect, delta ~ N(0, r), and the right-Haar prior on the noise scale:
 *
 * <pre>  BF_n = (1 + n r)^(-1/2) * [ (1 + t^2/v) / (1 + t^2/(v (1 + n r))) ]^(n/2),   v = n - 1</pre>
 *
 * where t is the usual t statistic. Because the scale prior is the right-Haar measure of the scale group,
 * this ratio is a nonnegative martingale under the null (mean zero, any variance) with respect to the
 * scale-invariant filtration, so by Ville's inequality P(sup_n BF_n >= 1/alpha) <= alpha: the test can be
 * checked after every interval and stopped at the first crossing without inflating false alarms. The
 * guarantee assumes the differences are independent and normal; the A/A experiments measure how far the
 * real, autocorrelated series stray from that.
 *
 * <p>References: Gonen, Johnson, Lu and Westfall (2005) for the closed form; Perez-Ortiz, Lardy, de Heide
 * and Grunwald (2022), "E-statistics, group invariance and anytime valid testing", for its validity as a
 * test martingale; Johari, Pekelis and Walsh (2017) for the mixture SPRT idea in A/B testing.
 */
public final class SequentialTTest {
    private final double r;
    private int n;
    private double mean;
    private double m2;

    /** @param r prior variance of the standardized effect; larger r favors detecting large shifts sooner. */
    public SequentialTTest(double r) {
        this.r = r;
    }

    public void add(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return;
        n++;
        double delta = d - mean;
        mean += delta / n;
        m2 += delta * (d - mean);
    }

    public int n() { return n; }

    public double mean() { return mean; }

    /** The t statistic, or NaN while it is undefined (n < 2 or zero variance). */
    public double t() {
        if (n < 2) return Double.NaN;
        double var = m2 / (n - 1);
        if (var <= 0) return Double.NaN;
        return mean / Math.sqrt(var / n);
    }

    /** log BF_n (two-sided evidence for a nonzero mean); 0 while undefined. */
    public double logBayesFactor() {
        double t = t();
        if (Double.isNaN(t)) return 0.0;
        return logBayesFactor(t, n, r);
    }

    public static double logBayesFactor(double t, int n, double r) {
        double v = n - 1;
        double g = 1 + n * r;
        return -0.5 * Math.log(g) + (n / 2.0) * (Math.log1p(t * t / v) - Math.log1p(t * t / (v * g)));
    }
}
