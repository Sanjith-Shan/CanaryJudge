# Running CanaryJudge

Everything runs in Docker plus a JDK 21. On the machine the numbers came from, that was WSL2 (Ubuntu 24.04)
with Docker 29; any Linux or macOS box with Docker works the same.

## Build

```bash
./gradlew build            # unit, golden (scipy), property tests and the Kayenta differential test (Docker)
bash scripts/image.sh      # jars and the canaryjudge:dev image
```

## The canary stack

```bash
cd deploy && docker compose up -d        # Prometheus :19090, splitter :18000, load generator :18001, server :18090
```

Start a baseline and a canary on a lane yourself (the trial runner and rollout runner do this for you):

```bash
docker run -d --name cj-l1-baseline --network cj-net -e CJ_SCOPE=demo-baseline canaryjudge:dev \
  java -Xmx224m -XX:+UseSerialGC -jar /app/target-service.jar
docker run -d --name cj-l1-canary --network cj-net -e CJ_SCOPE=demo-canary \
  -e INJECT_TYPE=latency -e INJECT_SIZE=0.25 canaryjudge:dev java -Xmx224m -XX:+UseSerialGC -jar /app/target-service.jar
curl -X POST 'localhost:18001/rate?lane=l1&rps=80'
```

Ask the judge (sequential by default; `mode=fixed` for the Kayenta-style judge):

```bash
curl 'localhost:18090/api/v1/judge?baseline=demo-baseline&canary=demo-canary'
```

## Kayenta-compatible API

The paths and JSON shapes of Kayenta's canary API, so a Kayenta client can point here:

```bash
curl -X POST localhost:18090/canaryConfig -H 'Content-Type: application/json' -d @configs/canary-config.json
curl -X POST localhost:18090/metricSetPairList -H 'Content-Type: application/json' -d @pairs.json
curl -X POST 'localhost:18090/judges/judge?canaryConfigId=canaryjudge-target&metricSetPairListId=ID&passThreshold=95&marginalThreshold=75'
# or let it fetch from Prometheus, like Kayenta's POST /canary
curl -X POST localhost:18090/canary/canaryjudge-target -H 'Content-Type: application/json' -d '{
  "scopes": {"default": {
    "controlScope":    {"scope": "demo-baseline", "start": "2026-10-04T17:00:00Z", "end": "2026-10-04T17:06:00Z", "step": 10},
    "experimentScope": {"scope": "demo-canary",   "start": "2026-10-04T17:00:00Z", "end": "2026-10-04T17:06:00Z", "step": 10}}},
  "thresholds": {"pass": 95, "marginal": 75}}'
curl localhost:18090/canary/EXECUTION_ID
```

## Rollouts

```bash
curl -X POST localhost:18090/api/v1/rollouts -H 'Content-Type: application/json' -d '{
  "lane": "r1", "baselineScope": "demo-baseline", "canaryScope": "demo-canary",
  "steps": [0.01, 0.05, 0.25], "stepSeconds": 120, "mode": "sequential"}'
curl localhost:18090/api/v1/rollouts/ROLLOUT_ID
```

## Command line and GitHub Action

```bash
./gradlew :cli:jar
java -jar cli/build/libs/canaryjudge-cli.jar judge --config configs/canary-config.json \
  --baseline examples/regressed-baseline.csv --canary examples/regressed-canary.csv     # exit 1: fail
java -jar cli/build/libs/canaryjudge-cli.jar fetch --config configs/canary-config.json --prometheus http://localhost:19090 \
  --baseline-scope demo-baseline --canary-scope demo-canary --start 1791134900 --end 1791135250 \
  --extra configs/recording-extra-series.json --out run.json
```

In a workflow (`uses: Sanjith-Shan/CanaryJudge@main`), see `action.yml` for the inputs and
`.github/workflows/ci.yml` for a job that gates on a healthy and a regressed recording.

## Experiments

```bash
bench/build/install/bench/bin/bench trials --plan configs/trial-plan.json --out results/trials.jsonl   # live canaries
bench/build/install/bench/bin/bench evaluate --trials results/trials.jsonl --out-dir results            # exp1 to exp3
bench/build/install/bench/bin/bench kayenta-diff --trials results/trials.jsonl --out results/exp4.jsonl # needs deploy/kayenta up
bench/build/install/bench/bin/bench rollout --plan configs/rollout-plan.json --out results/exp5.jsonl  # live rollouts
bench/build/install/bench/bin/bench simulate --out results/sim_aa.jsonl                                # synthetic A/A
python3 bench/ledger.py > NUMBERS.md && python3 bench/charts.py
```

## Grafana dashboard and Argo Rollouts

`deploy/grafana` holds the dashboard (`docker compose -f deploy/docker-compose.yml -f deploy/grafana/compose.yml up -d`,
then http://localhost:13000). `scripts/argo_demo.sh` builds a k3d cluster with Argo Rollouts and runs a healthy and a
regressed rollout against the in-cluster CanaryJudge.
