"""Turns the NASA-KSC HTTP log (July 1995) into a per-minute request-rate trace for the load generator.

Source: Internet Traffic Archive, NASA-HTTP (https://ita.ee.lbl.gov/html/contrib/NASA-HTTP.html), collected by
Jim Dumoulin (Kennedy Space Center), contributed by Martin Arlitt and Carey Williamson. "The traces may be
freely redistributed." Only per-minute request counts are kept (no hosts, no requests), in line with the
archive's request not to analyze anything beyond general traffic patterns.

Usage: python3 bench/make_trace.py NASA_access_log_Jul95.gz configs/traces/nasa-jul95-per-minute.csv [days]
"""
import collections
import datetime
import gzip
import re
import sys

STAMP = re.compile(r"\[(\d\d)/(\w\w\w)/(\d{4}):(\d\d):(\d\d):\d\d")


def main(src, out, days=7):
    counts = collections.Counter()
    start = datetime.datetime(1995, 7, 1)
    with gzip.open(src, "rt", encoding="latin-1") as f:
        for line in f:
            m = STAMP.search(line)
            if not m:
                continue
            d, mon, y, hh, mm = m.groups()
            if mon != "Jul":
                continue
            minute = (int(d) - 1) * 1440 + int(hh) * 60 + int(mm)
            if minute < days * 1440:
                counts[minute] += 1
    n = days * 1440
    total = sum(counts[i] for i in range(n))
    mean = total / n
    with open(out, "w") as f:
        f.write("# NASA-KSC WWW server, requests per minute from 1995-07-01 00:00 (-0400) for %d days.\n" % days)
        f.write("# Internet Traffic Archive NASA-HTTP trace (Dumoulin; Arlitt and Williamson). Freely redistributable.\n")
        f.write("# Only counts are kept. relative_rate = requests / mean (%.1f requests per minute).\n" % mean)
        f.write("minute,requests,relative_rate\n")
        for i in range(n):
            f.write("%d,%d,%.4f\n" % (i, counts[i], counts[i] / mean))
    peak = max(counts[i] for i in range(n)) / mean
    trough = min(counts[i] for i in range(n)) / mean
    print("minutes", n, "requests", total, "mean/min %.1f" % mean, "peak x%.2f" % peak, "trough x%.2f" % trough)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 7)
