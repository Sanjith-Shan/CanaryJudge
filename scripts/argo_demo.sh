#!/bin/bash
# Argo Rollouts end to end on a local k3d cluster: the target service as a Rollout whose every update runs an
# experiment (fresh baseline + fresh canary) analysed by CanaryJudge through a web metric. Runs a healthy update
# (promoted) and an update with a 50% latency regression (aborted), captures `kubectl argo rollouts get` output
# along the way into docs/argo/, and appends one row per update to results/argo.jsonl.
#
# Needs: docker, k3d, kubectl, kubectl-argo-rollouts on PATH, the canaryjudge:dev image, and Argo Rollouts'
# install.yaml (ARGO_INSTALL, default ~/canaryjudge-work/k8s/argo-rollouts-install.yaml).
set -euo pipefail
cd "$(dirname "$0")/.."
CLUSTER=cj-argo
ARGO_INSTALL=${ARGO_INSTALL:-$HOME/canaryjudge-work/k8s/argo-rollouts-install.yaml}
OUT=docs/argo
mkdir -p "$OUT" results
if [ "${GITHUB_ACTIONS:-}" = true ]; then MACHINE="GitHub Actions runner ($(nproc) vCPU), k3d single-node cluster"; else MACHINE="mini PC (Ryzen 3 4300U), k3d single-node cluster in WSL2"; fi
k() { kubectl --context "k3d-$CLUSTER" "$@"; }
ts() { date -Iseconds; }

k3d cluster delete "$CLUSTER" >/dev/null 2>&1 || true
k3d cluster create "$CLUSTER" --servers 1 --agents 0 --no-lb --wait \
  --k3s-arg "--disable=traefik@server:0" --k3s-arg "--disable=metrics-server@server:0"
k3d image import -c "$CLUSTER" canaryjudge:dev prom/prometheus:v3.15.0

k create namespace argo-rollouts
k apply --server-side -n argo-rollouts -f "$ARGO_INSTALL" >/dev/null
k -n argo-rollouts rollout status deploy/argo-rollouts --timeout=300s

k create configmap canaryjudge-configs --from-file=configs/canary-config.json --from-file=configs/recording-extra-series.json
k apply -f deploy/argo/prometheus.yaml -f deploy/argo/canaryjudge.yaml -f deploy/argo/analysis-template.yaml -f deploy/argo/rollout.yaml
k rollout status deploy/prometheus --timeout=300s
k rollout status deploy/canaryjudge --timeout=400s
for i in $(seq 1 120); do
  [ "$(k get rollout target -o jsonpath='{.status.phase}' 2>/dev/null)" = Healthy ] && break
  sleep 5
done
k get rollout target -o jsonpath='{.status.phase}'; echo " (first revision)"
sleep 60  # stable pods warm, Prometheus scraping

capture() { # name
  kubectl-argo-rollouts --context "k3d-$CLUSTER" get rollout target --no-color > "$OUT/$1.txt" 2>&1 || true
}

update() { # name inject_type inject_size expected
  local name=$1 type=$2 size=$3
  local t0; t0=$(date +%s)
  k patch rollout target --type json -p "[
    {\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env/3/value\",\"value\":\"$type\"},
    {\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env/4/value\",\"value\":\"$size\"},
    {\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env/5/value\",\"value\":\"$name\"}]"
  local phase="" n=0 shot=0
  while :; do
    sleep 10
    n=$((n + 10))
    phase=$(k get rollout target -o jsonpath='{.status.phase}')
    local aborted; aborted=$(k get rollout target -o jsonpath='{.status.abort}')
    if [ $((n % 60)) -eq 0 ]; then shot=$((shot + 1)); capture "$name-$(printf %02d $shot)-$((n / 60))min"; fi
    if [ "$aborted" = true ] || { [ "$phase" = Healthy ] && [ $n -gt 60 ]; } || [ $n -gt 900 ]; then break; fi
  done
  capture "$name-final"
  local secs=$(( $(date +%s) - t0 ))
  local ar; ar=$(k get analysisrun --sort-by=.metadata.creationTimestamp -o name | tail -1)
  local measurements; measurements=$(k get "$ar" -o jsonpath='{.status.metricResults[0].count} measurements, {.status.metricResults[0].failed} failed, phase {.status.phase}')
  local last; last=$(k get "$ar" -o jsonpath='{.status.metricResults[0].measurements[-1:].value}')
  local aborted; aborted=$(k get rollout target -o jsonpath='{.status.abort}')
  local outcome
  if [ "$aborted" = true ]; then outcome="aborted after ${secs}s (${measurements}; last verdict ${last})";
  else outcome="promoted after ${secs}s, phase ${phase} (${measurements}; last verdict ${last})"; fi
  echo "$name: $outcome"
  printf '{"exp":"argo","ts":"%s","update":"%s","inject_type":"%s","inject_size":%s,"aborted":%s,"seconds":%d,"analysis":"%s","last_verdict":"%s","description":"Argo Rollouts %s update (%s %s), experiment with a fresh baseline and canary judged by CanaryJudge every 10 s","outcome":"%s","machine":"%s"}\n' \
    "$(ts)" "$name" "$type" "$size" "${aborted:-false}" "$secs" "$measurements" "$last" "$name" "$type" "$size" "$outcome" "$MACHINE" >> results/argo.jsonl
  k get "$ar" -o yaml > "$OUT/$name-analysisrun.yaml"
}

update healthy none 0
update regressed latency 0.5
k get rollout target -o jsonpath='{.status.phase} {.status.message}'; echo
k3d cluster delete "$CLUSTER"
