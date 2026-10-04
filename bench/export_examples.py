"""Writes example CSV pairs for the CLI and the GitHub Action from recorded runs.

Usage: python3 bench/export_examples.py results/trials.jsonl examples/
Picks the first recorded A/A run (healthy) and the first 25% latency regression (regressed).
"""
import json
import math
import pathlib
import sys

COLUMNS = ["latency_p50", "latency_p90", "latency_p99", "error_rate", "cpu", "heap_after_gc",
           "errors_total", "requests_total"]


def write(rec, side, path):
    step = rec["stepMillis"] // 1000
    start = rec["startMillis"] // 1000
    n = len(rec["series"]["latency_p50"][side])
    with open(path, "w") as f:
        f.write(f"# {rec['trial']} ({rec['scenario']}), {side} side, recorded by bench trials\n")
        f.write("timestamp," + ",".join(COLUMNS) + "\n")
        for i in range(n):
            vals = []
            for c in COLUMNS:
                v = rec["series"][c][side][i]
                vals.append("" if v is None or v == "NaN" or (isinstance(v, float) and math.isnan(v)) else repr(float(v)))
            f.write(f"{start + i * step}," + ",".join(vals) + "\n")


def main(trials, out):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    picks = {"healthy": "aa", "regressed": "latency_25"}
    with open(trials) as f:
        recs = [json.loads(l) for l in f if l.strip()]
    for name, scenario in picks.items():
        rec = next(r for r in recs if r["scenario"] == scenario)
        write(rec, "control", out / f"{name}-baseline.csv")
        write(rec, "experiment", out / f"{name}-canary.csv")
        print(f"{name}: {rec['trial']} ({scenario})")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
