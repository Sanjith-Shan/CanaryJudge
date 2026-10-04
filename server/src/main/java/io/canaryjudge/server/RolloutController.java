package io.canaryjudge.server;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * {@code POST /api/v1/rollouts} starts a rollout ({@link RolloutService.Request}); {@code GET /api/v1/rollouts/{id}}
 * shows its weight, events, last judgment and, once finished, how many users the canary reached.
 */
@RestController
public class RolloutController {
    private final RolloutService service;

    public RolloutController(RolloutService service) { this.service = service; }

    @PostMapping("/api/v1/rollouts")
    public RolloutService.Rollout start(@RequestBody RolloutService.Request request) {
        if (request.lane() == null || request.baselineScope() == null || request.canaryScope() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lane, baselineScope and canaryScope are required");
        return service.start(request);
    }

    @GetMapping("/api/v1/rollouts/{id}")
    public RolloutService.Rollout get(@PathVariable String id) {
        RolloutService.Rollout r = service.get(id);
        if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no rollout " + id);
        return r;
    }

    @GetMapping("/api/v1/rollouts")
    public List<RolloutService.Rollout> list() {
        return service.all();
    }
}
