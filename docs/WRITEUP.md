# Judging a canary: rank tests, peeking, and how fast a bad release can be stopped

*A walk through CanaryJudge: what it does, the statistics underneath, what the measurements say, and what
did not work. Every number below is in [`NUMBERS.md`](../NUMBERS.md) with the results file it came from.*

## The problem

A release that passes every test can still be worse in production: slower for some requests, a little more
error-prone, leaking memory, burning more CPU. The usual defense is a canary. Send a slice of traffic to the new
release, compare it with the old one on live metrics, and roll back if it is worse. Two questions make that hard.

1. **Is it worse, or is that noise?** Two identical instances on the same machine do not produce the same
   numbers. Latency quantiles jump around from interval to interval, a garbage collection lands on one instance
   and not the other, a neighbor on the host takes the CPU for a minute.
2. **How long do you wait?** Waiting longer gives more evidence but exposes more users to a bad release.
   Checking early and often is natural, and it quietly breaks the statistics.

{{RESULTS_SUMMARY}}

## How the judge decides

CanaryJudge follows the rules of Kayenta, the open-source canary judge from Netflix and Google that Spinnaker
uses. Each metric is a series of values, one per 10-second interval, for the baseline and for the canary. The
judge asks one question per metric: do the canary's values tend to be larger (or smaller) than the baseline's?

**A rank test, not a t-test.** Per-interval latency is skewed and spiky. A single slow interval can move a mean
and blow up a variance, which either hides a real shift or invents one. The Mann-Whitney test only uses the
order of the values: it counts how often a canary value beats a baseline value. It needs no normality and
ignores how far out the spikes are.

**A confidence interval, a tolerance band and an effect size.** Rather than a bare p-value, the judge builds a
98% confidence interval for the shift between the two series, by inverting the rank test. A metric is High only
if that whole interval sits above a band of 25% of the estimated shift, and the ratio of means is at least the
configured allowed increase. The band keeps tiny but consistent differences from failing a canary; the effect
size lets an operator say "a 10% rise in memory is fine".

**Groups, weights and critical metrics.** Metrics sit in weighted groups (here Latency 40, Errors 40,
Saturation 20). A group's score is the share of its metrics that passed; the canary's score is the weighted
sum. A metric marked critical (errors, here) fails the whole canary outright. A score of 95 or more passes.

I wrote the judge in Java from Kayenta's documentation, then checked it against Kayenta itself: Kayenta's own
image judges the same recorded series through its API, and every classification is compared.

{{EXP4}}

## Why start the baseline with the canary

The obvious comparison is the canary against the production fleet. It is wrong in ways that are easy to miss.
The fleet has been running for days: its JIT has compiled the hot paths, its heap has settled, its caches are
warm. A canary that just started is slower and leaner for reasons that have nothing to do with the code change.
So every canary here gets its own baseline: a fresh instance of the old version, started at the same moment,
given the same share of traffic. Whatever start-up does to one, it does to the other.

Even that is not enough for memory. The first A/A run (two identical builds) failed on heap usage: the canary
used 44% more heap on average. Heap saws up and down with every collection, and leftover start-up garbage stays
until a full collection, which each JVM runs at its own moment. Switching to heap after collection, with a full
collection at start-up, still left identical JVMs 8% to 15% apart, because the old generation fills at its own
pace in each process. The fix was the one Kayenta provides for exactly this: an effect-size floor of 25% on
memory. It costs sensitivity to small leaks, and the results show how much.

## Peeking, and the fix

A canary is watched while it runs. The natural thing is to run the test every interval and stop as soon as it
says the canary is worse. That is peeking, and it inflates false alarms: a test at 5% is a 5% chance of a false
alarm *each time it is run*, and noise has many chances to cross the line.

{{SIM}}

The sequential judge is built to be checked after every interval. For latency, CPU and memory it runs a
sequential t-test on the per-interval differences (canary minus baseline, on a log scale for latency). The
evidence is a Bayes factor whose prior on the noise scale makes it a *test martingale* when the canary is
healthy: its expected value never grows, so by Ville's inequality the chance it ever reaches 20 is at most 5%,
however long you watch. For errors it uses an e-process on counts: given how many errors happened in an
interval, the canary's share of them is binomial, with a probability set by the two sides' traffic and the ratio
of their error rates. Six metrics each run at 5%/6, so the whole canary keeps a 5% false-alarm budget.

The guarantee assumes the per-interval differences are independent. Real metrics are autocorrelated: a slow
interval tends to be followed by another. The simulation shows what that does, and the A/A canaries measure it
on live data.

## What the measurements say

{{EXP1}}

{{EXP2}}

{{EXP3}}

{{EXP5}}

## What lost, and why

{{LOST}}

## How it was measured

All of it ran on one shared mini PC (AMD Ryzen 3 4300U, WSL2 with 2 vCPUs and 6 GB), with other jobs on the
same box; every run records the host load. Each live canary is a fresh baseline and canary of a small Spring Boot
service, behind a splitter, under an open-loop load generator, scraped by Prometheus every 2 s. Regressions are
injected into the canary. The recordings are replayed through every judge, so all of them see the same numbers.
None of this is production traffic, and a 6-minute window on one machine is not a production canary.
