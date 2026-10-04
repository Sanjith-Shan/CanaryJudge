package io.canaryjudge.core.sequential;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Monte Carlo checks of the always-valid tests: false alarms stay under alpha however long they watch. */
class SequentialTest {
    static final double ALPHA = 0.05;

    @Test
    void tTestHoldsAlphaUnderContinuousMonitoring() {
        Random rnd = new Random(1);
        int runs = 2000, steps = 200, alarms = 0;
        double threshold = Math.log(1 / ALPHA);
        for (int r = 0; r < runs; r++) {
            SequentialTTest t = new SequentialTTest(1.0);
            double scale = Math.exp(rnd.nextGaussian() * 3); // the test must not care about the noise scale
            for (int i = 0; i < steps; i++) {
                t.add(scale * rnd.nextGaussian());
                if (t.logBayesFactor() >= threshold) {
                    alarms++;
                    break;
                }
            }
        }
        double rate = alarms / (double) runs;
        assertTrue(rate <= ALPHA + 0.012, "false alarm rate " + rate);
    }

    @Test
    void tTestDetectsAShift() {
        Random rnd = new Random(2);
        int detected = 0;
        for (int r = 0; r < 200; r++) {
            SequentialTTest t = new SequentialTTest(1.0);
            for (int i = 0; i < 60; i++) {
                t.add(0.8 + rnd.nextGaussian());
                if (t.logBayesFactor() >= Math.log(1 / ALPHA)) {
                    detected++;
                    break;
                }
            }
        }
        assertTrue(detected > 190, "detected " + detected);
    }

    @Test
    void rateEProcessHoldsAlphaUnderContinuousMonitoring() {
        Random rnd = new Random(3);
        int runs = 2000, alarms = 0;
        for (int r = 0; r < runs; r++) {
            RateRatioEProcess e = new RateRatioEProcess(RateRatioEProcess.Side.INCREASE);
            for (int i = 0; i < 200; i++) {
                int nc = 300 + rnd.nextInt(200), nb = 300 + rnd.nextInt(200);
                e.add(binomial(rnd, nc, 0.003), nc, binomial(rnd, nb, 0.003), nb);
                if (e.logE() >= Math.log(1 / ALPHA)) {
                    alarms++;
                    break;
                }
            }
        }
        double rate = alarms / (double) runs;
        assertTrue(rate <= ALPHA + 0.012, "false alarm rate " + rate);
    }

    @Test
    void rateEProcessDetectsADoubledErrorRate() {
        Random rnd = new Random(4);
        int detected = 0;
        for (int r = 0; r < 200; r++) {
            RateRatioEProcess e = new RateRatioEProcess(RateRatioEProcess.Side.INCREASE);
            for (int i = 0; i < 100; i++) {
                e.add(binomial(rnd, 400, 0.02), 400, binomial(rnd, 400, 0.01), 400);
                if (e.logE() >= Math.log(1 / ALPHA)) {
                    detected++;
                    break;
                }
            }
        }
        assertTrue(detected > 190, "detected " + detected);
    }

    static int binomial(Random rnd, int n, double p) {
        int k = 0;
        for (int i = 0; i < n; i++) if (rnd.nextDouble() < p) k++;
        return k;
    }
}
