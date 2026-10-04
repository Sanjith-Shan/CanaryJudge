package io.canaryjudge.server;

import io.canaryjudge.core.prom.PromClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The CanaryJudge service: Kayenta-compatible canary API, a native judge endpoint for rollout tools such as
 * Argo Rollouts, and a rollout controller that moves traffic step by step and rolls back on a failed judgment.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CanaryJudgeServer.Settings.class)
public class CanaryJudgeServer {

    @ConfigurationProperties("canaryjudge")
    public record Settings(String prometheusUrl, String splitterUrl, String configDir) {}

    public static void main(String[] args) {
        SpringApplication.run(CanaryJudgeServer.class, args);
    }

    @Bean
    PromClient promClient(Settings s) {
        return new PromClient(s.prometheusUrl());
    }
}
