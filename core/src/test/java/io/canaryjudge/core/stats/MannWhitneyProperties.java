package io.canaryjudge.core.stats;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Properties any correct Mann-Whitney implementation must have, checked on random samples. */
class MannWhitneyProperties {

    record Samples(double[] x, double[] y) {}

    @Provide
    Arbitrary<Samples> samples() {
        Arbitrary<double[]> arr = Arbitraries.doubles().between(-1000, 1000).ofScale(2)
                .list().ofMinSize(1).ofMaxSize(30).map(l -> l.stream().mapToDouble(Double::doubleValue).toArray());
        return Combinators.combine(arr, arr).as(Samples::new)
                .filter(s -> Arrays.stream(s.x()).distinct().count() + Arrays.stream(s.y()).distinct().count() > 2);
    }

    @Property
    void uStatisticsSumToNm(@ForAll("samples") Samples s) {
        double ux = MannWhitney.uStatistic(s.x(), s.y()), uy = MannWhitney.uStatistic(s.y(), s.x());
        assertEquals(s.x().length * (double) s.y().length, ux + uy, 1e-9);
    }

    @Property
    void uCountsPairwiseWins(@ForAll("samples") Samples s) {
        double wins = 0;
        for (double a : s.x()) for (double b : s.y()) wins += a > b ? 1 : a == b ? 0.5 : 0;
        assertEquals(wins, MannWhitney.uStatistic(s.x(), s.y()), 1e-9);
    }

    @Property
    void pValueIsRankInvariant(@ForAll("samples") Samples s) {
        // a strictly increasing transform of both samples leaves every rank, so every p-value, unchanged
        double[] tx = Arrays.stream(s.x()).map(v -> Math.exp(v / 400.0) * 3 + 7).toArray();
        double[] ty = Arrays.stream(s.y()).map(v -> Math.exp(v / 400.0) * 3 + 7).toArray();
        for (var alt : MannWhitney.Alternative.values()) {
            assertEquals(MannWhitney.asymptotic(s.x(), s.y(), alt, true).pValue(),
                    MannWhitney.asymptotic(tx, ty, alt, true).pValue(), 1e-12);
        }
    }

    @Property
    void swappingSamplesSwapsTheOneSidedTests(@ForAll("samples") Samples s) {
        double g = MannWhitney.asymptotic(s.x(), s.y(), MannWhitney.Alternative.GREATER, true).pValue();
        double l = MannWhitney.asymptotic(s.y(), s.x(), MannWhitney.Alternative.LESS, true).pValue();
        assertEquals(g, l, 1e-12);
        double two = MannWhitney.asymptotic(s.x(), s.y(), MannWhitney.Alternative.TWO_SIDED, true).pValue();
        assertTrue(two >= 0 && two <= 1);
        assertTrue(two <= 2 * Math.min(g, MannWhitney.asymptotic(s.x(), s.y(), MannWhitney.Alternative.LESS, true).pValue()) + 1e-12);
    }

    @Property
    void exactDistributionMatchesEnumeration(@ForAll @IntRange(min = 1, max = 6) int n, @ForAll @IntRange(min = 1, max = 6) int m) {
        // enumerate every way to choose which n of the n+m ranks belong to x
        int total = n + m;
        long[] counts = new long[n * m + 1];
        long ways = 0;
        for (int mask = 0; mask < (1 << total); mask++) {
            if (Integer.bitCount(mask) != n) continue;
            int u = 0;
            for (int i = 0; i < total; i++) {
                if ((mask & (1 << i)) == 0) continue;
                for (int j = 0; j < i; j++) if ((mask & (1 << j)) == 0) u++;
            }
            counts[u]++;
            ways++;
        }
        double[] pmf = MannWhitney.exactUDistribution(n, m);
        for (int k = 0; k <= n * m; k++) assertEquals(counts[k] / (double) ways, pmf[k], 1e-12, "P(U=" + k + ")");
    }

    @Property
    void exactDistributionIsSymmetric(@ForAll @IntRange(min = 1, max = 40) int n, @ForAll @IntRange(min = 1, max = 40) int m) {
        double[] pmf = MannWhitney.exactUDistribution(n, m);
        double sum = 0;
        for (int k = 0; k < pmf.length; k++) {
            assertEquals(pmf[k], pmf[pmf.length - 1 - k], 1e-12);
            sum += pmf[k];
        }
        assertEquals(1.0, sum, 1e-9);
    }

    @Property
    void intervalIsOrderedAndHoldsTheEstimate(@ForAll("samples") Samples s) {
        if (s.x().length < 2 || s.y().length < 2) return;
        try {
            MannWhitney.Interval ci = MannWhitney.shiftInterval(s.x(), s.y(), 0.98);
            assertTrue(ci.lower() <= ci.upper(), () -> ci.toString());
            assertTrue(ci.lower() <= ci.estimate() && ci.estimate() <= ci.upper(), () -> ci.toString());
        } catch (IllegalArgumentException allTied) {
            // only when every shifted observation ties, which the classifier handles before calling this
        }
    }

    @Property
    void intervalMovesWithAShift(@ForAll("samples") Samples s, @ForAll @IntRange(min = -500, max = 500) int shift) {
        if (s.x().length < 2 || s.y().length < 2) return;
        double[] moved = Arrays.stream(s.x()).map(v -> v + shift).toArray();
        try {
            MannWhitney.Interval a = MannWhitney.shiftInterval(s.x(), s.y(), 0.98);
            MannWhitney.Interval b = MannWhitney.shiftInterval(moved, s.y(), 0.98);
            assertEquals(a.lower() + shift, b.lower(), 1e-6);
            assertEquals(a.upper() + shift, b.upper(), 1e-6);
            assertEquals(a.estimate() + shift, b.estimate(), 1e-6);
        } catch (IllegalArgumentException allTied) {
            // see above
        }
    }

    @Property(tries = 200)
    void intervalBoundsAreWhereTheTestFlips(@ForAll("samples") Samples s) {
        // just inside the interval the shifted test does not reject at 2% two-sided; at the bounds' far side it does
        if (s.x().length < 3 || s.y().length < 3) return;
        MannWhitney.Interval ci;
        try {
            ci = MannWhitney.shiftInterval(s.x(), s.y(), 0.98);
        } catch (IllegalArgumentException e) {
            return;
        }
        double z = -Normal.quantile(0.01);
        List<Double> breaks = Arrays.stream(MannWhitney.pairwiseDifferences(s.x(), s.y())).sorted().distinct().boxed().toList();
        int li = breaks.indexOf(ci.lower());
        if (li > 0 && li < breaks.size() - 1) {
            double below = (breaks.get(li - 1) + breaks.get(li)) / 2, above = (breaks.get(li) + breaks.get(li + 1)) / 2;
            assertTrue(MannWhitney.wilcoxonDiff(below, z, s.x(), s.y()) > 0);
            assertTrue(MannWhitney.wilcoxonDiff(above, z, s.x(), s.y()) <= 0);
        }
    }
}
