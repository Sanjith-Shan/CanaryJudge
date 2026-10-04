package io.canaryjudge.server;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * The native judge endpoint, built for rollout tools that poll a URL during a canary step (an Argo Rollouts
 * web metric, a CI job, a script):
 *
 * <pre>
 *   GET|POST /api/v1/judge?baseline=SCOPE&amp;canary=SCOPE[&amp;config=canary-config][&amp;mode=sequential|fixed]
 *                          [&amp;start=EPOCH_SECONDS][&amp;warmup=30][&amp;step=10][&amp;alpha=0.05]
 * </pre>
 *
 * Without {@code start}, the window starts when the canary process started (from its uptime gauge) plus the
 * warm-up, and ends at the last complete step. The answer's {@code verdict} is {@code pass}, {@code fail},
 * {@code marginal} (fixed mode) or {@code pending} (no data yet), so a caller fails only on {@code fail}.
 * The sequential judge can be asked every few seconds: it replays the whole window each time, and its
 * false-alarm guarantee holds however often it is asked.
 */
@RestController
public class JudgeController {
    private final JudgeService service;

    public JudgeController(JudgeService service) { this.service = service; }

    @RequestMapping(path = "/api/v1/judge", method = {RequestMethod.GET, RequestMethod.POST})
    public JudgeService.Verdict judge(@RequestParam String baseline, @RequestParam String canary,
                                      @RequestParam(defaultValue = "canary-config") String config,
                                      @RequestParam(defaultValue = "sequential") String mode,
                                      @RequestParam(required = false) Long start,
                                      @RequestParam(required = false) Long end,
                                      @RequestParam(defaultValue = "30") long warmup,
                                      @RequestParam(defaultValue = "10") long step,
                                      @RequestParam(defaultValue = "0.05") double alpha,
                                      @RequestParam(defaultValue = "95") double pass,
                                      @RequestParam(defaultValue = "75") double marginal) throws IOException, InterruptedException {
        long now = System.currentTimeMillis() / 1000;
        long endSec = end != null ? end : ((now - 3) / step) * step;
        long startSec;
        if (start != null) {
            startSec = start;
        } else {
            double up = service.canaryUptime(canary);
            if (Double.isNaN(up)) return JudgeService.Verdict.pending(mode, "canary " + canary + " is not scraped yet");
            startSec = ((long) (now - up + warmup) / step + 1) * step;
        }
        if (endSec - startSec < step) return JudgeService.Verdict.pending(mode, "canary still warming up");
        return service.judge(config, new MetricFetcher.Scope(baseline, ""), new MetricFetcher.Scope(canary, ""),
                startSec, endSec, step, mode, alpha, pass, marginal);
    }
}
