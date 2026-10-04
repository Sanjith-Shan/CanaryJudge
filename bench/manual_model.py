"""The manual 30-minute canary of exp5, as a model (not a measurement).

A person runs the canary on 5% of users for 30 minutes, watches dashboards, and promotes it unless something
crosses a limit. Caught: about 5% of users saw the bad release. Missed: it is promoted and every user does.
The chance of catching each regression is the tuned static-limit judge's detection rate from exp1, the closest
recorded stand-in for a person reading fixed lines on a dashboard (and generous, since that judge checks every
10 s and never looks away).

Usage: python3 bench/manual_model.py [results_dir]   (writes results/exp5_manual_model.jsonl)
"""
import json
import os
import sys

R = sys.argv[1] if len(sys.argv) > 1 else "results"
SHARE = 0.05


def main():
    exp1 = [json.loads(l) for l in open(os.path.join(R, "exp1.jsonl")) if l.strip()]
    rows = []
    for r in exp1:
        p = r["static_tuned"]["rate"]
        rows.append({
            "exp": "exp5_manual_model", "scenario": r["scenario"], "canary_share": SHARE, "minutes": 30,
            "catch_probability": p, "catch_probability_source": "exp1 static_tuned rate",
            "expected_user_share": SHARE * p + 1.0 * (1 - p),
            "note": "model: 5% of users if caught at 30 minutes, 100% if missed and promoted",
        })
    with open(os.path.join(R, "exp5_manual_model.jsonl"), "w") as f:
        for row in rows:
            f.write(json.dumps(row) + "\n")
    for row in rows:
        print(f"{row['scenario']}: catch {row['catch_probability']:.0%}, expected share {row['expected_user_share']:.0%}")


if __name__ == "__main__":
    main()
