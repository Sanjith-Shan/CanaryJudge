# Design

CanaryJudge decides whether a new release (the canary) is worse than the old one (the baseline) from live
metrics, and rolls it back when it is. This file explains how it is built, why, and whose work it follows.

## The parts

```
                      +-------------+        /api/v1/judge, Kayenta-compatible API, /api/v1/rollouts
 load generator ----> |  splitter   | ----> primary (old)      +--------------------+
 (Zipf users,         | (per-user   | ----> baseline (old) --> | Prometheus         | <--- CanaryJudge server
  Poisson arrivals,   |  or per-    | ----> canary (new,       | (2 s scrapes,      |      (judge, sequential
  replayable)         |  request)   |       injected bug)      |  PromQL)           |       judge, rollout
                      +-------------+                          +--------------------+       controller)
```

| Module | What it is |
|---|---|
| `core` | The statistics and the judges, no framework: Mann-Whitney U (normal approximation with tie and continuity corrections, exact null distribution, Hodges-Lehmann shift, the confidence interval Kayenta's judge uses), Kayenta's canary config model, the fixed-horizon judge and scorer, the sequential judge, the baselines it is compared with (static thresholds, a fixed-horizon test, naive repeated checks), a Prometheus range-query client |
| `server` | Spring Boot service: Kayenta-compatible REST API, a native judge endpoint for tools that poll (Argo Rollouts), the rollout controller, Prometheus metrics about its own decisions |
| `target` | The service the canaries run: a small Spring Boot catalog endpoint with a regression injector (added latency, errors, a memory leak, CPU burn, each with a size and an onset) |
| `traffic` | The splitter (weighted routing to primary, baseline and canary, per user or per request, with distinct-user counts) and the open-loop load generator |
| `bench` | Experiment drivers: live trials, offline replay through every judge, the Kayenta differential test, live rollouts, the A/A simulation |
| `cli` | `canaryjudge judge` and `canaryjudge fetch`, used by the GitHub Action in `action.yml` |

## Canary method

* **Baseline and canary start together.** Every trial starts a fresh baseline (old version) and a fresh canary
  (new version) at the same moment, and they get the same share of traffic. Comparing a canary with the
  long-running primary would compare a cold JVM with a warm one, a near-empty heap with a full one, and an
  instance that started in a quiet minute with one that started in a busy one. Starting both together makes
  every one of those effects hit both sides alike; the A/A runs measure what is left.
* **Per-interval values.** Each metric is a time series sampled every 10 s (the PromQL query computes the
  value over the last 10 s, from 2 s scrapes). The judge compares the distribution of the canary's interval
  values with the baseline's, as Kayenta does.
* **Pairing in time.** Both sides are measured over the same intervals, so the sequential judge works on the
  difference canary minus baseline in each interval, which cancels load that hits both (other jobs on the
  same machine, garbage collection in the load generator, bursts in the trace).

## The fixed-horizon judge (Kayenta semantics)

The judge follows Kayenta's documentation (`docs/canary-config.md`, the FAQ) and the `NetflixACAJudge-v1.0`
judge the docs link to as the definition of the rules. It was written in Java from that description; the
differential test (exp4) then runs Kayenta's own image on the same series to check every classification.

For each metric:

1. NaN values are removed, or replaced by zero for metrics marked `nanStrategy: replace` (counts, where no
   data means no events). Outliers are optionally removed outside the wider of an IQR fence and the 1st/99th
   percentiles.
2. No data on either side is `Nodata` (or a pass when NaNs are replaced, or `NodataFailMetric` when the metric
   is marked `mustHaveData`). Identical samples pass.
3. A 98% confidence interval for the shift canary minus baseline is found by inverting the rank-sum test with
   its normal approximation (tie and continuity corrected, as R's `wilcox.test(conf.int = TRUE, exact = FALSE)`).
   The canary is `High` when the whole interval lies above a tolerance band of plus or minus 25% of the
   estimated shift and the effect size (ratio of means, or the common-language effect size) reaches
   `allowedIncrease`; `Low` mirrors it. The metric's `direction` says which of the two counts as a failure.
4. Critical metrics that fail beyond their critical effect size fail the whole canary.

The canary's score is the weighted sum of group scores (a group's score is the share of its metrics that
passed), forced to 0 by any critical failure or by `Nodata` on half the metrics. Score at or above the pass
threshold is Pass, at or above the marginal threshold Marginal, below it Fail. Spinnaker promotes a canary
only when its final score reaches the pass threshold, so the experiments count anything but Pass as caught.

**Why Mann-Whitney and not a t-test.** Latency per interval is skewed and heavy-tailed (a p99 series has
spikes several times its median), and a few outliers can move a mean and inflate a variance enough to
hide a real shift or invent one. The rank test asks whether canary values tend to be larger than baseline
values; it does not care about the scale of the spikes and needs no normality.

**One deliberate difference from Kayenta.** The interval ends are where the test statistic jumps from one
side of the critical value to the other. That function is a step function that only changes at the pairwise
differences between canary and baseline values, so CanaryJudge searches those differences and returns the
exact jump. Kayenta runs a numeric root finder with an absolute tolerance of 1e-4 and returns a point within
that tolerance of the jump. The two can only disagree when a tolerance boundary falls within 1e-4 of an
interval end; exp4 counts how often that happened.

## The sequential judge

Checking a fixed-horizon test repeatedly while a canary runs and stopping at the first significant result
(peeking) raises the false-alarm rate far above its nominal level: every check is another chance for noise
to cross the line. The sequential judge is built so that it can be checked after every interval and still
fail a healthy canary with probability at most alpha (5%) over the whole run.

* **Scale metrics (latency quantiles, CPU, heap growth).** A sequential t-test on the per-interval differences
  (log differences for latency, so a 10% slowdown is the same shift at any latency). The evidence is the
  Bayes factor with a normal prior on the standardized effect and the right-Haar prior on the noise scale,
  which has a closed form; with that scale prior it is a test martingale under the null, so Ville's
  inequality bounds the chance it ever reaches 1/alpha. It assumes independent, normal differences; exp2
  measures how the real series (which are autocorrelated) behave, and the simulation shows the effect of
  autocorrelation directly.
* **Rate metrics (errors).** Given the total number of errors in an interval, the canary's share of them is
  binomial with a success probability set by the two sides' request counts and the ratio of their error
  rates. The likelihood ratio for a fixed ratio, multiplied over intervals and mixed over a grid of ratios
  from 1.1 to 10, is an e-process: it reaches 1/alpha on a healthy canary with probability at most alpha.
* **Many metrics.** Each of the m metrics runs at alpha / m, so the chance that any of them ever fires is at
  most alpha. A metric only fails in its harmful direction.

## Memory

Heap level turned out not to be usable as a canary metric. Plain heap use saws with every collection; heap
right after a collection still differed by up to 30% between two identical JVMs, because the level is set by
whatever start-up happens to promote into the old generation. A leak shows up as growth, so the config judges
`heap_growth`, the change in heap-after-GC over each 10-second interval (`x - x offset 10s` in PromQL). Every
target runs one full collection when it is ready so both sides start clean. The cost: a leak must grow fast
enough to show within the window. Bugs 2, 5 and 6 in `BUG_LOG.md` are the path to this.

## Rollout controller

The controller runs the canary at 1%, then 5%, then 25% of users, with a baseline at the same share, then
promotes it to 100%. Users are assigned by a hash of their id into 10,000 buckets, so a user stays on one
side and raising the share only adds users. In sequential mode the judge looks after every interval at
everything since the analysis started, across steps, and a failure rolls back at once. In fixed mode the
fixed-horizon judge scores each step at its end. The splitter counts distinct users per side from the start
of the rollout, which gives the share of users a bad canary reached before it was stopped.

## What is measured, and how

Live trials record every metric of the config for both sides from Prometheus into `results/trials.jsonl`,
one run per line, with the machine and the host load before and after. All judges then replay the same
recordings (`bench evaluate`), so every judge sees exactly the same numbers. Kayenta judges the same
recordings through its own API (`bench kayenta-diff`). Rollouts (exp5) are live end to end.

The experiments started in Docker inside WSL2 on a shared mini PC. When WSL on that box began hanging under
load, the remaining live work (rollouts, the trace replay, a second A/A set) moved to native Windows processes
on the same machine: `scripts/native_stack.ps1` runs Prometheus's Windows binary, the splitter, the load
generator and the server, and `bench --launcher process` starts targets as plain JVMs on loopback ports. The
differential test against Kayenta and the Argo Rollouts demo need Docker, so they ran on GitHub Actions runners
(`.github/workflows/kayenta-diff.yml`, `argo-demo.yml`). Every row names its machine.

**Real-shaped traffic.** The load generator can follow a recorded request-rate shape: `configs/traces/` holds
per-minute request counts of the NASA Kennedy Space Center web server for 1 to 7 July 1995 (Internet Traffic
Archive, NASA-HTTP, collected by Jim Dumoulin, contributed by Martin Arlitt and Carey Williamson; "the traces may
be freely redistributed"). Only counts are kept, no hosts or URLs, as the archive asks. One trace minute plays per
10 seconds of wall time.

## Credits

* **Kayenta** (Netflix and Google, Apache 2.0), the open-source automated canary analysis service used by
  Spinnaker: the canary config format, the judge's rules, its REST API shapes, and the reference judge for
  the differential test. Its documentation, `docs/canary-config.md` and the `NetflixACAJudge` judge it points
  to, define the semantics CanaryJudge implements. The differential test runs the official Spinnaker image
  `us-docker.pkg.dev/spinnaker-community/docker/kayenta:2026.0.4-slim` unmodified.
* **Spinnaker's canary documentation and best practices**: that baseline and canary should be new instances
  started at the same time and sized alike, and that the final score must reach the pass threshold.
* **Argo Rollouts**: the analysis and experiment model the integration plugs into (an `AnalysisTemplate`
  with a web metric, and an experiment step that runs a baseline and a canary side by side).
* **Flagger**: the static-threshold check this project compares against (request success rate at least 99%,
  request duration p99 at most 500 ms, rollback after a number of failed checks).
* **Google SRE book and workbook, "Canarying Releases"**: canarying as an experiment with a control group,
  the choice of metrics, and why canary population and duration matter.
* **Google Cloud and Waze, "Canary analysis: lessons learned and best practices"**: baseline versus
  production comparisons and metric selection.
* **Sequential testing and always-valid inference.** Wald's sequential probability ratio test; Robbins'
  mixture martingales; Johari, Koomen, Pekelis and Walsh, "Peeking at A/B tests" (KDD 2017) and "Always valid
  inference" (Operations Research 2022) for the mixture SPRT in experimentation; Gonen, Johnson, Lu and
  Westfall, "The Bayesian two-sample t test" (2005), for the closed-form Bayes factor with a normal prior on
  the effect; Perez-Ortiz, Lardy, de Heide and Grunwald, "E-statistics, group invariance and anytime valid
  testing" (2022), for why that Bayes factor is a test martingale; Ramdas, Grunwald, Vovk and Shafer, "Game-theoretic
  statistics and safe anytime-valid inference" (Statistical Science 2023); Howard, Ramdas, McAuliffe and
  Sekhon, "Time-uniform, nonparametric, nonasymptotic confidence sequences" (Annals of Statistics 2021).
* **Netflix Technology Blog, "Sequential A/B Testing Keeps the World Streaming Netflix" (2024, parts 1 and
  2)**: sequential tests on canary metrics, and modeling counts such as errors as rates.
* **R's `wilcox.test`** for the confidence interval construction Kayenta also follows, and **scipy**, used once
  to produce the golden values the Mann-Whitney and normal-distribution tests compare against.
* **Micrometer and Prometheus** for the service metrics and the queries.
