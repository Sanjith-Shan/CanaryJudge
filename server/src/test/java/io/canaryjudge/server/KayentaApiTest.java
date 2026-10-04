package io.canaryjudge.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.canaryjudge.core.model.MetricSetPair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Kayenta-compatible endpoints, driven the way a Kayenta client drives them. */
@SpringBootTest(properties = "canaryjudge.config-dir=../configs")
@AutoConfigureMockMvc
class KayentaApiTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    static double[] series(long seed, double mean, double sd) {
        Random r = new Random(seed);
        double[] v = new double[36];
        for (int i = 0; i < v.length; i++) v[i] = mean + sd * r.nextGaussian();
        return v;
    }

    JsonNode postJson(String path, String body) throws Exception {
        String out = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(out);
    }

    @Test
    void storeConfigStorePairsAndJudge() throws Exception {
        String config = Files.readString(Path.of("../configs/canary-config.json"));
        String configId = postJson("/canaryConfig", config).get("canaryConfigId").asText();
        mvc.perform(get("/canaryConfig/" + configId)).andExpect(status().isOk());

        List<MetricSetPair> pairs = List.of(
                MetricSetPair.of("latency_p50", series(1, 0.020, 0.001), series(2, 0.030, 0.001), 0, 10_000),
                MetricSetPair.of("latency_p90", series(3, 0.040, 0.002), series(4, 0.040, 0.002), 0, 10_000),
                MetricSetPair.of("latency_p99", series(5, 0.070, 0.005), series(6, 0.070, 0.005), 0, 10_000),
                MetricSetPair.of("error_rate", new double[36], new double[36], 0, 10_000),
                MetricSetPair.of("cpu", series(7, 0.05, 0.01), series(8, 0.05, 0.01), 0, 10_000),
                MetricSetPair.of("heap_growth", series(9, 2e4, 1e4), series(10, 2e4, 1e4), 0, 10_000));
        String listId = postJson("/metricSetPairList", json.writeValueAsString(pairs)).get("metricSetPairListId").asText();
        JsonNode result = postJson("/judges/judge?canaryConfigId=" + configId + "&metricSetPairListId=" + listId
                + "&passThreshold=95&marginalThreshold=75", "");
        assertEquals("NetflixACAJudge-v1.0", result.get("judgeName").asText());
        assertEquals(6, result.get("results").size());
        assertEquals("High", result.get("results").get(0).get("classification").asText());
        // one of three latency metrics failed: Latency group 66.67 * 0.4 + 40 + 20
        assertEquals(86.67, result.get("score").get("score").asDouble(), 1e-9);
        assertEquals("Marginal", result.get("score").get("classification").asText());
    }

    @Test
    void unknownIdsAre404() throws Exception {
        mvc.perform(get("/canaryConfig/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/canary/nope")).andExpect(status().isNotFound());
    }
}
