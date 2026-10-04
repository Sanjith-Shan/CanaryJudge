package io.canaryjudge.traffic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaneTest {
    @Test
    void stickyRoutingOnlyAddsUsersAsTheCanaryGrows() {
        Lane lane = new Lane("r1", 0.01, 0.01, true);
        int users = 200_000;
        boolean[] wasCanary = new boolean[users];
        int canary1 = 0;
        for (int u = 0; u < users; u++) if (wasCanary[u] = lane.route(u).equals("canary")) canary1++;
        lane.setWeights(0.05, 0.05);
        int canary5 = 0;
        for (int u = 0; u < users; u++) {
            boolean c = lane.route(u).equals("canary");
            if (wasCanary[u]) assertTrue(c, "user " + u + " left the canary when its share grew");
            if (c) canary5++;
            assertEquals(lane.route(u), lane.route(u), "routing must be sticky");
        }
        assertEquals(0.01, canary1 / (double) users, 0.002);
        assertEquals(0.05, canary5 / (double) users, 0.003);
    }

    @Test
    void requestModeSplitsRequestsEvenly() {
        Lane lane = new Lane("l1", 0.5, 0.5, false);
        int canary = 0, n = 100_000;
        for (int i = 0; i < n; i++) if (lane.route(1).equals("canary")) canary++; // one very active user
        assertEquals(0.5, canary / (double) n, 0.01);
    }

    @Test
    void weightsAreValidated() {
        Lane lane = new Lane("x", 0, 0, true);
        try {
            lane.setWeights(0.6, 0.6);
            throw new AssertionError("accepted weights above 1");
        } catch (IllegalArgumentException expected) {
            assertEquals("primary", lane.route(42));
        }
    }

    @Test
    void zipfCdfPutsTheMostActiveUserFirst() {
        double[] cdf = LoadGen.zipfCdf(50_000, 1.0);
        assertEquals(1.0, cdf[cdf.length - 1], 1e-12);
        assertTrue(cdf[0] > 0.08 && cdf[0] < 0.10, "top user share " + cdf[0]);
    }
}
