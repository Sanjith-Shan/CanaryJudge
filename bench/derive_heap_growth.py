"""Adds the heap_growth series to recordings made before the metric existed.

heap_growth is the config's PromQL template
    max(cj_heap_after_gc_bytes{...}) - max(cj_heap_after_gc_bytes{...} offset 10s)
evaluated on the 10 s step grid. The recordings already hold max(cj_heap_after_gc_bytes{...}) on that grid
(series heap_after_gc), so the template's value at point i is exactly heap_after_gc[i] - heap_after_gc[i-1];
the first point, which needs a sample from before the window, is NaN (and removed by the NaN strategy).

Usage: python3 bench/derive_heap_growth.py in.jsonl out.jsonl
"""
import json
import math
import sys


def diff(xs):
    out = [float("nan")]
    for a, b in zip(xs, xs[1:]):
        a = float(a) if a not in (None, "NaN") else float("nan")
        b = float(b) if b not in (None, "NaN") else float("nan")
        out.append(b - a)
    return out


def main(src, dst):
    n = 0
    with open(src) as f, open(dst, "w") as g:
        for line in f:
            if not line.strip():
                continue
            r = json.loads(line)
            rec = r["recording"] if "recording" in r else r
            s = rec["series"]
            if "heap_growth" not in s and "heap_after_gc" in s:
                s["heap_growth"] = {"control": diff(s["heap_after_gc"]["control"]),
                                    "experiment": diff(s["heap_after_gc"]["experiment"])}
                rec.setdefault("meta", {})["heap_growth"] = "derived offline from heap_after_gc (bench/derive_heap_growth.py)"
                n += 1
            g.write(json.dumps(r, allow_nan=True) + "\n")
    print(f"added heap_growth to {n} recordings")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
