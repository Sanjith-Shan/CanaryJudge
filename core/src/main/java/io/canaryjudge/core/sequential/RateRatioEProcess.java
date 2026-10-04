package io.canaryjudge.core.sequential;

/**
 * An always-valid test that the canary's event rate (errors per request) is higher than the baseline's.
 *
 * <p>In each interval the canary serves n requests with k events and the baseline serves m requests with
 * j events. If events arrive as Poisson processes whose rates are proportional to traffic, then given the
 * total k + j, the canary's share k is Binomial(k + j, p) with p = theta n / (theta n + m), where theta is
 * the ratio of the canary's per-request rate to the baseline's. Under the null theta = 1. The product over
 * intervals of the likelihood ratio for a fixed theta has expectation one under the null at every step, and
 * so does any mixture of such products over a prior on theta. That mixture is an e-process: rejecting when
 * it reaches 1/alpha holds the false-alarm rate at alpha however often it is checked (Ville's inequality).
 *
 * <p>The prior is uniform over a grid of ratios. The grid covers increases of 10% to 10x, or the mirror image
 * for a metric that fails on a decrease, or half of each for either direction.
 */
public final class RateRatioEProcess {
    private static final double[] RATIOS = {1.1, 1.25, 1.5, 2.0, 3.0, 5.0, 10.0};

    private final double[] thetas;
    private final double[] logProducts;
    private long canaryEvents, baselineEvents;

    public enum Side { INCREASE, DECREASE, EITHER }

    public RateRatioEProcess(Side side) {
        int k = RATIOS.length;
        thetas = switch (side) {
            case INCREASE -> RATIOS.clone();
            case DECREASE -> invert(RATIOS);
            case EITHER -> {
                double[] t = new double[2 * k];
                System.arraycopy(RATIOS, 0, t, 0, k);
                System.arraycopy(invert(RATIOS), 0, t, k, k);
                yield t;
            }
        };
        logProducts = new double[thetas.length];
    }

    private static double[] invert(double[] a) {
        double[] b = new double[a.length];
        for (int i = 0; i < a.length; i++) b[i] = 1 / a[i];
        return b;
    }

    /** One interval: canary events and requests, baseline events and requests. */
    public void add(double canaryEvents, double canaryTotal, double baselineEvents, double baselineTotal) {
        if (!(canaryTotal > 0) || !(baselineTotal > 0)) return;
        double k = Math.max(0, Math.rint(canaryEvents)), j = Math.max(0, Math.rint(baselineEvents));
        if (k + j == 0) return;
        this.canaryEvents += (long) k;
        this.baselineEvents += (long) j;
        double p0 = canaryTotal / (canaryTotal + baselineTotal);
        for (int i = 0; i < thetas.length; i++) {
            double p = thetas[i] * canaryTotal / (thetas[i] * canaryTotal + baselineTotal);
            logProducts[i] += k * Math.log(p / p0) + j * Math.log((1 - p) / (1 - p0));
        }
    }

    /** log of the mixture e-value. */
    public double logE() {
        double max = Double.NEGATIVE_INFINITY;
        for (double l : logProducts) max = Math.max(max, l);
        double s = 0;
        for (double l : logProducts) s += Math.exp(l - max);
        return max + Math.log(s / logProducts.length);
    }

    public long canaryEvents() { return canaryEvents; }

    public long baselineEvents() { return baselineEvents; }
}
