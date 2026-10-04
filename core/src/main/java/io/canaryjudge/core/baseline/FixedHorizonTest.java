package io.canaryjudge.core.baseline;

import io.canaryjudge.core.config.AnalysisSettings;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.judge.MetricClassifier;
import io.canaryjudge.core.model.Recording;
import io.canaryjudge.core.stats.MannWhitney;

/**
 * A plain fixed-horizon test with a known false-alarm rate: a one-sided Mann-Whitney test per metric in
 * its harmful direction at alpha / m (Bonferroni), on the first n intervals. Used two ways: once at the end
 * of the window (the fixed-horizon comparison for the sequential judge), and after every interval (naive
 * peeking, which is what inflates false alarms).
 */
public final class FixedHorizonTest {
    private final double alpha;

    public FixedHorizonTest(double alpha) { this.alpha = alpha; }

    public record Result(boolean failed, String metric, double pValue) {}

    public Result test(CanaryConfig config, Recording rec, int n) {
        long m = config.metrics().stream().filter(x -> !x.settings().muted()).count();
        double level = alpha / Math.max(1, m);
        double minP = 1.0;
        String worst = null;
        for (CanaryConfig.MetricConfig mc : config.metrics()) {
            AnalysisSettings s = mc.settings();
            if (s.muted()) continue;
            Recording.Pair p = rec.get(mc.name()).prefix(n);
            double[] c = MetricClassifier.transform(p.control(), s);
            double[] e = MetricClassifier.transform(p.experiment(), s);
            if (c.length < 2 || e.length < 2) continue;
            double margin = s.sequential().margin();
            if (margin > 0 && s.direction() == io.canaryjudge.core.config.AnalysisSettings.Direction.INCREASE) {
                // same tolerated shift as the sequential judge: compare the canary scaled back by the margin
                final double m0 = margin;
                e = s.sequential().logScale() ? java.util.Arrays.stream(e).map(v -> v / m0).toArray()
                        : java.util.Arrays.stream(e).map(v -> v - m0).toArray();
            }
            MannWhitney.Alternative alt = switch (s.direction()) {
                case INCREASE -> MannWhitney.Alternative.GREATER;
                case DECREASE -> MannWhitney.Alternative.LESS;
                case EITHER -> MannWhitney.Alternative.TWO_SIDED;
            };
            double pv = MannWhitney.asymptotic(e, c, alt, true).pValue();
            if (pv < minP) {
                minP = pv;
                worst = mc.name();
            }
        }
        return new Result(minP <= level, worst, minP);
    }

    /** Re-tests after every interval from minPoints on; returns the first failing point count, or -1. */
    public int peek(CanaryConfig config, Recording rec, int minPoints, int maxPoints) {
        int n = Math.min(maxPoints, rec.length());
        for (int k = minPoints; k <= n; k++) if (test(config, rec, k).failed()) return k;
        return -1;
    }
}
