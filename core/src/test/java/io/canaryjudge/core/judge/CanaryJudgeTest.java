package io.canaryjudge.core.judge;

import io.canaryjudge.core.config.AnalysisSettings;
import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.model.MetricSetPair;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanaryJudgeTest {
    static final Random RND = new Random(7);

    static double[] noisy(int n, double mean, double sd) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = mean + sd * RND.nextGaussian();
        return v;
    }

    static AnalysisSettings settings(String canaryJson) throws IOException {
        return AnalysisSettings.from(CanaryConfig.MAPPER.readTree("{\"canary\":" + canaryJson + "}"));
    }

    static Classification classify(String canaryJson, double[] control, double[] experiment) throws IOException {
        MetricResult r = new MetricClassifier().classify("m", List.of("g"), settings(canaryJson), control, experiment);
        return Classification.valueOf(r.classification());
    }

    @Test
    void defaultsFollowKayentasDocs() throws IOException {
        AnalysisSettings s = settings("{}");
        assertEquals(AnalysisSettings.Direction.EITHER, s.direction());
        assertEquals(AnalysisSettings.NanStrategy.REMOVE, s.nanStrategy());
        assertEquals(1.0, s.allowedIncrease());
        assertEquals(3.0, s.outlierFactor());
        AnalysisSettings cles = settings("{\"effectSize\":{\"measure\":\"cles\"}}");
        assertEquals(0.5, cles.allowedIncrease());
    }

    @Test
    void clearIncreaseIsHighAndDirectionDecidesWhatFails() throws IOException {
        double[] base = noisy(40, 100, 5), worse = noisy(40, 130, 5);
        assertEquals(Classification.High, classify("{\"direction\":\"increase\"}", base, worse));
        assertEquals(Classification.High, classify("{\"direction\":\"either\"}", base, worse));
        assertEquals(Classification.Pass, classify("{\"direction\":\"decrease\"}", base, worse));
        assertEquals(Classification.Low, classify("{\"direction\":\"decrease\"}", worse, base));
    }

    @Test
    void sameDistributionPasses() throws IOException {
        double[] a = noisy(40, 100, 5);
        assertEquals(Classification.Pass, classify("{}", a, a.clone()));
    }

    @Test
    void effectSizeThresholdIgnoresSmallButSignificantChanges() throws IOException {
        double[] base = noisy(60, 100, 1), worse = noisy(60, 120, 1);
        assertEquals(Classification.High, classify("{\"direction\":\"increase\"}", base, worse));
        assertEquals(Classification.Pass, classify("{\"direction\":\"increase\",\"effectSize\":{\"allowedIncrease\":1.5}}", base, worse));
    }

    @Test
    void toleranceBandPassesShiftsThatAreSmallAgainstTheEstimate() throws IOException {
        // a tiny but very consistent shift: the interval excludes zero but its lower end sits inside
        // 25% of the estimate only when the spread is wide relative to the shift
        double[] base = noisy(30, 100, 20), worse = noisy(30, 104, 20);
        assertEquals(Classification.Pass, classify("{\"direction\":\"increase\"}", base, worse));
    }

    @Test
    void missingDataFollowsTheNanStrategy() throws IOException {
        double[] empty = {Double.NaN, Double.NaN};
        double[] some = {1, 2, 3};
        assertEquals(Classification.Nodata, classify("{}", some, empty));
        assertEquals(Classification.NodataFailMetric, classify("{\"mustHaveData\":true}", some, empty));
        assertEquals(Classification.Pass, classify("{\"nanStrategy\":\"replace\"}", new double[0], new double[0]));
        // replaced NaNs become zeros and are compared
        assertEquals(Classification.High, classify("{\"nanStrategy\":\"replace\",\"direction\":\"increase\"}",
                new double[]{Double.NaN, 0, 0, Double.NaN, 0, 0, 0, 0}, new double[]{5, 6, 7, 5, 6, 7, 5, 6}));
    }

    @Test
    void constantButDifferentSamplesAreFullySeparated() throws IOException {
        assertEquals(Classification.High, classify("{\"direction\":\"increase\"}", new double[]{2, 2, 2, 2}, new double[]{3, 3, 3, 3}));
        assertEquals(Classification.Pass, classify("{}", new double[]{2, 2, 2}, new double[]{2, 2, 2, 2}));
    }

    static CanaryConfig config() throws IOException {
        return CanaryConfig.parse("""
                {"name":"t","judge":{"name":"NetflixACAJudge-v1.0","judgeConfigurations":{}},
                 "classifier":{"groupWeights":{"Latency":60,"Errors":40}},
                 "metrics":[
                  {"name":"p50","groups":["Latency"],"analysisConfigurations":{"canary":{"direction":"increase"}}},
                  {"name":"p99","groups":["Latency"],"analysisConfigurations":{"canary":{"direction":"increase"}}},
                  {"name":"errors","groups":["Errors"],"analysisConfigurations":{"canary":{"direction":"increase","critical":true,"nanStrategy":"replace"}}},
                  {"name":"cpu","groups":["Other"],"analysisConfigurations":{"canary":{"direction":"increase"}}}
                 ]}""");
    }

    static MetricSetPair pair(String name, double[] c, double[] e) {
        return MetricSetPair.of(name, c, e, 0, 10_000);
    }

    @Test
    void weightedScoreAndThresholds() throws IOException {
        CanaryConfig cfg = config();
        double[] base = noisy(40, 100, 5), same = noisy(40, 100, 5), worse = noisy(40, 150, 5);
        double[] zeros = new double[40];
        // p99 fails: Latency group 50; the Other group has no weight and gets the leftover 0
        JudgeResult r = new CanaryJudge().judge(cfg, 95, 75, List.of(pair("p50", base, same), pair("p99", base, worse),
                pair("errors", zeros, zeros), pair("cpu", base, same)));
        assertEquals(70.0, r.score().score(), 1e-9);
        assertEquals("Fail", r.score().classification());
        JudgeResult marginal = new CanaryJudge().judge(cfg, 95, 65, List.of(pair("p50", base, same), pair("p99", base, worse),
                pair("errors", zeros, zeros), pair("cpu", base, same)));
        assertEquals("Marginal", marginal.score().classification());
        JudgeResult pass = new CanaryJudge().judge(cfg, 95, 75, List.of(pair("p50", base, same), pair("p99", base, same),
                pair("errors", zeros, zeros), pair("cpu", base, worse)));
        assertEquals(100.0, pass.score().score(), 1e-9);
    }

    @Test
    void criticalFailureZeroesTheScore() throws IOException {
        double[] base = noisy(40, 100, 5);
        double[] errBase = noisy(40, 0.001, 0.0005), errWorse = noisy(40, 0.05, 0.005);
        JudgeResult r = new CanaryJudge().judge(config(), 95, 75, List.of(pair("p50", base, base.clone()), pair("p99", base, base.clone()),
                pair("errors", errBase, errWorse), pair("cpu", base, base.clone())));
        assertEquals(0.0, r.score().score());
        assertTrue(r.score().classificationReason().startsWith("Canary Failed"));
    }

    @Test
    void halfTheMetricsWithoutDataFailsTheCanary() throws IOException {
        double[] base = noisy(40, 100, 5), nan = {Double.NaN};
        JudgeResult r = new CanaryJudge().judge(config(), 95, 75, List.of(pair("p50", base, nan), pair("p99", base, nan),
                pair("errors", new double[]{0}, new double[]{0}), pair("cpu", base, base.clone())));
        assertEquals(0.0, r.score().score());
    }
}
