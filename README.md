# CanaryJudge

CanaryJudge decides whether a new release is worse than the old one from live metrics, and rolls it back when it
is. It runs a fresh baseline of the old version next to the canary, judges every metric with a Mann-Whitney test
under Kayenta's canary-config semantics (checked against Kayenta itself, run from its own image on the same
series), watches the canary with an always-valid sequential test that can stop it within a minute or two without
raising the false-alarm rate, and drives a 1% to 5% to 25% rollout that rolls back on a failed judgment. It
speaks Kayenta's REST API, plugs into Argo Rollouts as an analysis metric, and ships as a CLI and a GitHub
Action. **What it does not claim:** every number below comes from regressions injected into a small sample
service under generated load on one shared mini PC, with 6-minute canaries; none of it is production traffic or
a production canary, and the judge's semantics are Kayenta's, not new. Credits are in [`DESIGN.md`](DESIGN.md).

{{HEADLINE_TABLE}}

Every figure is in [`NUMBERS.md`](NUMBERS.md) with the `results/*.jsonl` file it came from. The story behind them,
including what lost, is in [`docs/WRITEUP.md`](docs/WRITEUP.md).

{{CHARTS}}

## How it works

```
 load generator --> splitter --+--> primary  (old version)
 (open loop,       (by user or  +--> baseline (old version, fresh)      Prometheus <-- CanaryJudge
  Zipf users,       request)    +--> canary   (new version, fresh) --> (2 s scrapes)    judge, sequential judge,
  replayable)                                                                           rollout controller
```

* **Judge** (`core/`). Per metric: NaN handling, optional outlier removal, a 98% confidence interval for the shift
  canary minus baseline from the rank-sum test, a tolerance band and an effect-size threshold, direction, critical
  metrics; then weighted group scores and pass/marginal thresholds. Kayenta's canary config JSON loads unchanged.
* **Sequential judge** (`core/sequential`). A sequential t-test with a closed-form Bayes factor (a test martingale
  under the null) for latency, CPU and memory, and a rate-ratio e-process for errors, each at alpha/m, so the
  canary's false-alarm budget holds at 5% however often it is checked.
* **Server** (`server/`). Kayenta's canary API (`/canaryConfig`, `/metricSetPairList`, `/judges/judge`, `/canary`),
  a polling endpoint for rollout tools (`/api/v1/judge`), and the rollout controller (`/api/v1/rollouts`).
* **Target and traffic** (`target/`, `traffic/`). The sample service with a regression injector (latency, errors,
  a memory leak, CPU burn, each with a size and an onset), the splitter, the load generator.
* **Experiments** (`bench/`). Live trials recorded from Prometheus, replayed through every judge; the Kayenta
  differential test; live rollouts; the A/A simulation; `ledger.py` and `charts.py`.

Run it: [`docs/USAGE.md`](docs/USAGE.md). Design and credits: [`DESIGN.md`](DESIGN.md). Every bug found on
the way, with what found it: [`BUG_LOG.md`](BUG_LOG.md).

## Integrations

* **Kayenta-compatible API.** A client written for Kayenta's `/canary` and `/judges/judge` endpoints works
  against CanaryJudge; the differential test drives both servers with the same client.
* **Argo Rollouts.** `deploy/argo/` has an `AnalysisTemplate` whose web metric polls CanaryJudge every 10 s, and
  a `Rollout` whose updates run an experiment with a fresh baseline and a fresh canary.
  `scripts/argo_demo.sh` runs it on a local k3d cluster: {{ARGO}}
* **CLI and GitHub Action.** `canaryjudge judge --baseline b.csv --canary c.csv` exits 0, 1 or 2 for pass, fail
  or marginal; `action.yml` wraps it so a workflow can gate a deploy step on the verdict. CI runs the action on a
  recorded healthy canary (passes) and a recorded 25% latency regression (blocked).
* **Grafana.** `deploy/grafana/` provisions a dashboard: canary against baseline on every metric, the canary's
  traffic share, the sequential judge's evidence against its threshold, and the share of users reached.

{{DASHBOARD}}

## Testing

* Statistics checked against scipy golden values (normal CDF, tail and quantile to 1e-12; Mann-Whitney p-values,
  asymptotic and exact, on 300 random cases with and without ties) and with property tests (jqwik): the exact
  null distribution against brute-force enumeration, rank invariance, symmetry, interval shift equivariance, and
  that the interval ends sit exactly where the test flips.
* Monte Carlo tests that both sequential tests keep false alarms under 5% over 200 checks.
* The Kayenta differential test in Testcontainers, in CI, on recorded runs.
* CI also re-judges every committed recording and checks the results match the committed `results/` rows.
