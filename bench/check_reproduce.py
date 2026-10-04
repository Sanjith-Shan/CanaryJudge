"""Checks that re-judging the committed recordings reproduces the committed exp1 to exp3 rows.

Usage: python3 bench/check_reproduce.py <committed results dir> <fresh results dir>
Machine and load fields are ignored (they describe where the rows were made); every judge outcome must match.
"""
import json
import sys

IGNORE = {"machine", "load_range"}


def rows(path):
    out = {}
    with open(path) as f:
        for line in f:
            if line.strip():
                r = json.loads(line)
                out[r["scenario"]] = {k: v for k, v in r.items() if k not in IGNORE}
    return out


def main(committed, fresh):
    bad = 0
    for name in ["exp1.jsonl", "exp2.jsonl", "exp3.jsonl"]:
        a, b = rows(f"{committed}/{name}"), rows(f"{fresh}/{name}")
        if a.keys() != b.keys():
            print(f"{name}: scenarios differ: {sorted(a.keys() ^ b.keys())}")
            bad += 1
            continue
        for k in a:
            if json.dumps(a[k], sort_keys=True) != json.dumps(b[k], sort_keys=True):
                print(f"{name}: {k} differs")
                bad += 1
    print("reproduced" if bad == 0 else f"{bad} differences")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
