package io.canaryjudge.core.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compares the statistics code with golden values computed by scipy (bench/golden/make_golden.py). */
class GoldenTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode golden(String name) throws IOException {
        try (InputStream in = GoldenTest.class.getResourceAsStream("/golden/" + name)) {
            return JSON.readTree(in);
        }
    }

    static void assertClose(double expected, double actual, double rel, String what) {
        double tol = Math.max(rel * Math.abs(expected), 1e-300);
        assertTrue(Math.abs(expected - actual) <= tol || (expected == actual),
                () -> what + ": expected " + expected + " got " + actual);
    }

    @Test
    void normalMatchesScipy() throws IOException {
        JsonNode g = golden("normal.json");
        for (JsonNode p : g.get("cdf")) assertClose(p.get(1).asDouble(), Normal.cdf(p.get(0).asDouble()), 1e-12, "cdf(" + p.get(0) + ")");
        for (JsonNode p : g.get("sf")) assertClose(p.get(1).asDouble(), Normal.upperTail(p.get(0).asDouble()), 1e-12, "sf(" + p.get(0) + ")");
        for (JsonNode p : g.get("ppf")) assertClose(p.get(1).asDouble(), Normal.quantile(p.get(0).asDouble()), 1e-12, "ppf(" + p.get(0) + ")");
    }

    @Test
    void mannWhitneyMatchesScipy() throws IOException {
        JsonNode g = golden("mannwhitney.json");
        int exactChecked = 0, cases = 0;
        for (JsonNode c : g.get("cases")) {
            double[] x = JSON.convertValue(c.get("x"), double[].class);
            double[] y = JSON.convertValue(c.get("y"), double[].class);
            for (var alt : MannWhitney.Alternative.values()) {
                String key = switch (alt) {
                    case TWO_SIDED -> "two-sided";
                    case GREATER -> "greater";
                    case LESS -> "less";
                };
                JsonNode a = c.get("asymptotic_" + key);
                MannWhitney.Test t = MannWhitney.asymptotic(x, y, alt, true);
                assertEquals(a.get(0).asDouble(), t.u(), 1e-9, "U");
                assertClose(a.get(1).asDouble(), t.pValue(), 1e-9, "asymptotic " + key + " p for case " + cases);
                JsonNode e = c.get("exact_" + key);
                if (e != null) {
                    MannWhitney.Test te = MannWhitney.exact(x, y, alt);
                    assertClose(e.get(1).asDouble(), te.pValue(), 1e-9, "exact " + key + " p for case " + cases);
                    exactChecked++;
                }
            }
            assertEquals(c.get("hodges_lehmann").asDouble(), MannWhitney.hodgesLehmann(x, y), 1e-12);
            cases++;
        }
        assertTrue(cases >= 290 && exactChecked > 100, "cases " + cases + " exact " + exactChecked);
    }
}
